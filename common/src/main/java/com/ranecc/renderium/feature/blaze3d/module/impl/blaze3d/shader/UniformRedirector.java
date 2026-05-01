// Renderium - Blaze3D Shader 转译模块
// Uniform 重定向器 - Blaze3D 名称 → Vulkan set/binding/offset 透明桥接

package com.ranecc.renderium.feature.blaze3d.module.impl.blaze3d.shader;

import java.lang.foreign.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Blaze3D Uniform → Vulkan Descriptor 重定向器。
 *
 * <h2>问题背景：</h2>
 * <p>Blaze3D 用名字设置 Uniform:
 * <pre>{@code program.getUniformByName("CameraPosition").set(x, y, z); }</pre></p>
 *
 * <p>Vulkan SPIR-V 期望:
 * <pre>{@code layout(set=0, binding=2) uniform CameraBlock {
 *     vec3 position;  // offset=0
 *     mat4 viewProj;  // offset=16
 * }; }</pre></p>
 *
 * <p><b>中间的鸿沟:</b> Blaze3D 说 "CameraPosition"，Vulkan 说 "set=0, binding=2, offset=0"。
 * 谁来做翻译？就是本类。</b></p>
 *
 * <h2>工作原理：</h2>
 * <ol>
 *   <li>编译阶段: {@link GlslToVkTransformer} 产出 uniform 名称 → binding 映射表</li>
 *   <li>本类接收映射表，建立 "Blaze3D 名 → Vulkan 内存地址" 索引</li>
 *   <li>Mixin 拦截 Blaze3D 的 {@code uniform*()} 调用，转发给本类</li>
 *   <li>本类通过 Panama {@code MappedMemorySegment} 直接写入 Vulkan UBO 内存</li>
 * </ol>
 *
 * <h2>内存布局：</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ MappedMemorySegment (由 vkMapMemory 分配)                   │
 * ├──────────────────────────────────────────────────────────────┤
 * │ [CameraBlock]  set=0, binding=0                             │
 * │   offset  0: vec3  cameraPosition  (12 bytes)              │
 * │   offset 16: mat4  viewProjection  (64 bytes)              │
 * │   offset 80: float time            (4 bytes)               │
 * ├──────────────────────────────────────────────────────────────┤
 * │ [LightBlock]    set=0, binding=1                            │
 * │   offset  0: vec3  lightDirection  (12 bytes)              │
 * │   offset 16: vec3  lightColor      (12 bytes)              │
 * │   offset 28: float ambientStrength (4 bytes)               │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @see GlslToVkTransformer.TransformationResult#uniformMap() 提供映射表
 * @see ProgramUniformRedirectorMixin Mixin 注入点
 * @since 3.0.0
 */
public final class UniformRedirector {

    private static final Logger LOGGER = Logger.getLogger("Renderium-UniformRedirect");

    /** 单例实例 */
    private static volatile UniformRedirector instance;

    /**
     * Uniform 条目 — 描述一个 uniform 在 Vulkan UBO 中的位置和大小
     *
     * @param setName    Descriptor Set 索引
     * @param binding    Binding 点索引
     * @param offset     在 UBO 中的字节偏移
     * @param size       数据大小 (字节)
     * @param glslType   原始 GLSL 类型名 (如 "vec3", "mat4")
     */
    public record UniformEntry(int setName, int binding, int offset,
                               int size, String glslType) {}

    /** Uniform 名称 → Vulkan 绑定信息 (来自 GlslToVkTransformer) */
    private final ConcurrentHashMap<String, UniformEntry> uniformTable = new ConcurrentHashMap<>();

    /** UBO 内存段 (MappedMemorySegment, 由 vkMapMemory 获得) */
    private volatile MemorySegment uboMemory;

    /** UBO 总大小 (字节) */
    private int uboTotalSize = 0;

    /** VkDeviceMemory handle (用于 vkFlushMappedMemoryRanges) */
    private long deviceMemoryHandle = 0L;

    /** 是否启用重定向 */
    private volatile boolean enabled = false;

    private UniformRedirector() {}

    public static synchronized UniformRedirector getInstance() {
        if (instance == null) {
            instance = new UniformRedirector();
        }
        return instance;
    }

    // ==================== 初始化 API ====================

    /**
     * 无参便捷初始化方法（延迟绑定模式）。
     *
     * <p>在 Shader 转译管线尚未完成、uniform 映射表不可用时，
     * 可先调用此方法启用 UniformRedirector 基础设施。
     * 后续当映射表就绪后，再调用 {@link #initialize(Map, MemorySegment, long)} 完成完整初始化。</p>
     *
     * <h3>使用场景：</h3>
     * <ul>
     *   <li>Mixin 加载阶段需要提前初始化组件</li>
     *   <li>Uniform 映射表在首帧渲染时才可用</li>
     *   <li>需要避免循环依赖（UniformRedirector ↔ GlslToVkTransformer）</li>
     * </ul>
     *
     * @see #initialize(Map, MemorySegment, long)
     */
    public void initialize() {
        // 延迟初始化模式：仅标记为已启用，实际映射表稍后通过完整版 initialize() 注入
        this.uniformTable.clear();
        this.uboMemory = null;
        this.deviceMemoryHandle = 0L;
        this.uboTotalSize = 0;
        this.enabled = true;

        LOGGER.info("✓ UniformRedirector 延迟初始化完成 (等待 uniform 映射表注入)");
    }

    /**
     * 使用编译阶段产出的映射表初始化重定向器。
     *
     * <h3>参数说明：</h3>
     * <table>
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>uniformMap</td><td>Map&lt;String, UniformBindingInfo&gt;</td><td>来自 GlslToVkTransformer</td></tr>
     *   <tr><td>uboMemory</td><td>MemorySegment</td><td>Panama 堆外内存段 (vkMapMemory)</td></tr>
     *   <tr><td>deviceMemory</td><td>long</td><td>VkDeviceMemory 句柄</td></tr>
     * </table>
     *
     * @param uniformMap  Uniform 名称 → 绑定信息映射
     * @param uboMemory   已映射的 UBO 内存段
     * @param deviceMemory VkDeviceMemory 句柄
     */
    public void initialize(Map<String, GlslToVkTransformer.UniformBindingInfo> uniformMap,
                           MemorySegment uboMemory, long deviceMemory) {

        this.uniformTable.clear();
        this.uboMemory = uboMemory;
        this.deviceMemoryHandle = deviceMemory;

        // 计算所需总大小并注册所有条目
        int maxSize = 0;
        for (var entry : uniformMap.entrySet()) {
            var info = entry.getValue();
            UniformEntry ue = new UniformEntry(
                    info.setIndex(), info.bindingPoint(),
                    info.offset(), info.sizeBytes(), info.glslType()
            );
            uniformTable.put(entry.getKey(), ue);
            int end = info.offset() + info.sizeBytes();
            if (end > maxSize) maxSize = end;
        }
        this.uboTotalSize = maxSize;
        this.enabled = true;

        LOGGER.info(String.format(
                "✓ UniformRedirector 初始化完成: %d 个 Uniform, UBO 大小 %d bytes",
                uniformMap.size(), uboTotalSize
        ));
    }

    /**
     * 禁用重定向 (回退到 Blaze3D 原版路径)
     */
    public void disable() {
        this.enabled = false;
    }

    /**
     * 注册纹理采样器绑定 (扩展: 处理 sampler2D 等)
     *
     * @param name       采样器名称
     * @param imageView  VkImageView handle
     * @param sampler    VkSampler handle
     */
    public void registerSamplerBinding(String name, long imageView, long sampler) {
        // 纹理采样器不需要写入 UBO，只需记录绑定关系供后续使用
        // 实际的 VkImageView/VkSampler 绑定在 Descriptor Set 更新时处理
        LOGGER.fine(String.format("注册采样器: %s [view=0x%s, sampler=0x%s]",
                name, Long.toHexString(imageView), Long.toHexString(sampler)));
    }

    // ==================== 重定向接口 (由 Mixin 调用) ====================

    /**
     * 重定向 uniform3f (vec3) 调用。
     *
     * <h3>Mixin 调用示例：</h3>
     * <pre>{@code
     * // Blaze3D: program.uniform3f("CameraPosition", x, y, z)
     * // → Mixin 截获 → 调用此方法
     * }</pre>
     *
     * @param name Uniform 名称 (如 "CameraPosition", "LightColor")
     * @param x    第一个分量
     * @param y    第二个分量
     * @param z    第三个分量
     * @return true 如果成功重定向到 Vulkan (Mixin 应 cancel 原版调用)
     *         false 如果该 uniform 不在表中 (Mixin 应放行原版调用)
     */
    public boolean redirectUniform3f(String name, float x, float y, float z) {
        if (!enabled) return false;

        UniformEntry entry = uniformTable.get(name);
        if (entry == null) return false;

        writeVec3(entry.offset(), x, y, z);
        return true;
    }

    /**
     * 重定向 uniform4f (vec4) 调用
     *
     * @param name Uniform 名称
     * @param x, y, z, w 四个分量
     * @return true 如果成功重定向
     */
    public boolean redirectUniform4f(String name, float x, float y, float z, float w) {
        if (!enabled) return false;

        UniformEntry entry = uniformTable.get(name);
        if (entry == null) return false;

        writeVec4(entry.offset(), x, y, z, w);
        return true;
    }

    /**
     * 重定向 uniform1f (float) 调用
     *
     * @param name   Uniform 名称
     * @param value 浮点数值
     * @return true 如果成功重定向
     */
    public boolean redirectUniform1f(String name, float value) {
        if (!enabled) return false;

        UniformEntry entry = uniformTable.get(name);
        if (entry == null) return false;

        writeFloat(entry.offset(), value);
        return true;
    }

    /**
     * 重定向 uniformMatrix4fv (mat4) 调用
     *
     * @param name   Uniform 名称
     * @param values 16 个浮点数的矩阵 (列优先)
     * @return true 如果成功重定向
     */
    public boolean redirectUniformMatrix4fv(String name, float[] values) {
        if (!enabled || values == null || values.length != 16) return false;

        UniformEntry entry = uniformTable.get(name);
        if (entry == null) return false;

        writeMat4(entry.offset(), values);
        return true;
    }

    /**
     * 重定向 uniform1i / uniform2i / uniform3i / uniform4i (int) 调用
     *
     * @param name   Uniform 名称
     * @param values int 数组
     * @return true 如果成功重定向
     */
    public boolean redirectUniformiv(String name, int[] values) {
        if (!enabled || values == null) return false;

        UniformEntry entry = uniformTable.get(name);
        if (entry == null) return false;

        writeInts(entry.offset(), values);
        return true;
    }

    // ==================== 内存写入 (Panama MappedMemorySegment) ====================

    /** 写入 vec3 (12 bytes, 无填充) */
    private void writeVec3(int offset, float x, float y, float z) {
        validateBounds(offset, 12);
        MemorySegment seg = uboMemory.asSlice(offset, 12);
        seg.set(ValueLayout.JAVA_FLOAT, 0, x);
        seg.set(ValueLayout.JAVA_FLOAT, 4, y);
        seg.set(ValueLayout.JAVA_FLOAT, 8, z);
    }

    /** 写入 vec4 (16 bytes) */
    private void writeVec4(int offset, float x, float y, float z, float w) {
        validateBounds(offset, 16);
        MemorySegment seg = uboMemory.asSlice(offset, 16);
        seg.set(ValueLayout.JAVA_FLOAT, 0, x);
        seg.set(ValueLayout.JAVA_FLOAT, 4, y);
        seg.set(ValueLayout.JAVA_FLOAT, 8, z);
        seg.set(ValueLayout.JAVA_FLOAT, 12, w);
    }

    /** 写入 float (4 bytes) */
    private void writeFloat(int offset, float value) {
        validateBounds(offset, 4);
        uboMemory.set(ValueLayout.JAVA_FLOAT, offset, value);
    }

    /** 写入 mat4 (64 bytes, 列优先) */
    private void writeMat4(int offset, float[] m) {
        validateBounds(offset, 64);
        MemorySegment seg = uboMemory.asSlice(offset, 64);
        for (int i = 0; i < 16; i++) {
            seg.set(ValueLayout.JAVA_FLOAT, (long) i * 4, m[i]);
        }
    }

    /** 写入 int 数组 */
    private void writeInts(int offset, int[] values) {
        int totalBytes = values.length * 4;
        validateBounds(offset, totalBytes);
        MemorySegment seg = uboMemory.asSlice(offset, totalBytes);
        for (int i = 0; i < values.length; i++) {
            seg.set(ValueLayout.JAVA_INT, (long) i * 4, values[i]);
        }
    }

    /** 验证写入边界 */
    private void validateBounds(int offset, int size) {
        if (uboMemory == null) {
            throw new IllegalStateException("UBO 内存未初始化");
        }
        if (offset < 0 || offset + size > uboTotalSize) {
            throw new IndexOutOfBoundsException(
                    String.format("Uniform 写入越界: offset=%d, size=%d, uboSize=%d",
                            offset, size, uboTotalSize));
        }
    }

    // ==================== 查询接口 ====================

    /** 获取指定 Uniform 的绑定信息 */
    public UniformEntry getEntry(String name) {
        return uniformTable.get(name);
    }

    /** 是否包含指定 Uniform */
    public boolean hasUniform(String name) {
        return uniformTable.containsKey(name);
    }

    /** 获取所有已注册的 Uniform 名称 */
    public java.util.Set<String> getUniformNames() {
        return uniformTable.keySet();
    }

    /** 是否启用 */
    public boolean isEnabled() { return enabled; }

    /** 获取统计信息 */
    public String getStats() {
        return String.format("UniformRedirector{entries=%d, uboSize=%d bytes, enabled=%s}",
                uniformTable.size(), uboTotalSize, enabled);
    }

    @Override
    public String toString() {
        return getStats();
    }
}
