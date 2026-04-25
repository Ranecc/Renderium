// Renderium - 光影系统 v2.0
// .rgb 二进制格式读取器 - 与 renderium-cli (Rust) rgb.rs 格式完全对齐

package com.renderium.module.impl.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * .rgb 二进制格式读取器
 * <p>
 * 与 {@code renderium-cli} 的 {@code rgb.rs} 格式完全对齐。
 * 负责从编译后的 .rgb 二进制包中高效提取：
 * <ul>
 *   <li>渲染图配置（节点状态、依赖关系）</li>
 *   <li>参数表（默认值、范围约束）</li>
 *   <li>SPIR-V 着色器字节码</li>
 *   <li>Lua 字节码（可选）</li>
 * </ul>
 *
 * <h2>文件格式（与 Rust rgb.rs 对齐）：</h2>
 * <pre>
 * ┌──────────────────────────────────────┐
 * │ Header (64 bytes)                    │
 * │ ├─ magic: u32 "RGB\0" (0x42524700)  │
 * │ ├─ version: u16                     │
 * │ ├─ flags: u16                       │
 * │ ├─ render_graph_offset/size: u32×2  │
 * │ ├─ param_table_offset/size: u32×2   │
 * │ ├─ shader_table_offset/size: u32×2  │
 * │ └─ lua_bytecode_offset/size: u32×2  │
 * ├──────────────────────────────────────┤
 * │ RenderGraph Data (序列化配置)        │
 * ├──────────────────────────────────────┤
 * │ Param Table (name→value 映射)       │
 * ├──────────────────────────────────────┤
 * │ Shader Table (SPIR-V 数据)          │
 * ├──────────────────────────────────────┤
 * │ Lua Bytecode (可选, LZ4 压缩)       │
 * └──────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * RgbBinaryReader reader = RgbBinaryReader.fromFile(path.toPath());
 *
 * // 检查基本信息
 * System.out.println("Version: " + reader.getHeader().version());
 * System.out.println("Mode: " + reader.getRunMode());
 * System.out.println("Shaders: " + reader.getShaderCount());
 *
 * // 获取 SPIR-V 着色器
 * byte[] shadowMapSpv = reader.getSpirvData("shadow_map");
 *
 * // 获取参数默认值
 * Map<String, Float> params = reader.getParameters();
 *
 * // 获取 Lua 字节码（如果存在）
 * byte[] luaBytecode = reader.getLuaBytecode();
 *
 * reader.close();
 * }</pre>
 *
 * @see RGBPackParser
 * @since 2.1.0
 */
public final class RgbBinaryReader implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(RgbBinaryReader.class.getName());

    // ==================== 与 Rust rgb.rs 对齐的常量 ====================

    /** Magic Number: "RGB\0" (Little Endian: 0x42='B', 0x52='R', 0x47='G', 0x00='\0') */
    public static final int RGB_MAGIC = 0x42524700;

    /** 当前支持的最低格式版本 */
    public static final int MIN_SUPPORTED_VERSION = 1;

    /** Header 大小（必须与 Rust HEADER_SIZE 一致）*/
    public static final int HEADER_SIZE = 64;

    // ==================== 标志位常量（与 Rust rgb.rs::flags 对齐）====================

    public static final class Flags {
        /** 包含自定义 SPIR-V Shader */
        public static final short HAS_CUSTOM_SHADERS = 0x0001;
        /** 使用注入模式（替换特定 Pass）*/
        public static final short INJECTION_MODE = 0x0002;
        /** 使用夺舍模式（完全接管渲染管线）*/
        public static final short TAKEOVER_MODE = 0x0004;
        /** 包含 Lua 脚本 */
        public static final short HAS_LUA_SCRIPT = 0x0008;
        /** 加密的内容 */
        public static final short ENCRYPTED = 0x0010;

        private Flags() {} // 防止实例化
    }

    // ==================== 内部数据结构 ====================

    /**
     * .rgb 文件头（与 Rust RgbHeader 对齐）
     */
    public record RgbHeader(
            int magic,
            int version,
            int flags,
            long renderGraphOffset,
            long renderGraphSize,
            long paramTableOffset,
            long paramTableSize,
            long shaderTableOffset,
            long shaderTableSize,
            long luaBytecodeOffset,
            long luaBytecodeSize
    ) {
        /** 检查 Header 是否有效 */
        public boolean isValid() {
            return magic == RGB_MAGIC && version >= MIN_SUPPORTED_VERSION;
        }

        /** 是否包含 Lua 字节码 */
        public boolean hasLuaBytecode() {
            return luaBytecodeOffset != 0 && luaBytecodeSize > 0;
        }

        /** 运行模式枚举值 */
        public RunMode getRunMode() {
            if ((flags & Flags.TAKEOVER_MODE) != 0) return RunMode.TAKEOVER;
            if ((flags & Flags.INJECTION_MODE) != 0) return RunMode.INJECTION;
            return RunMode.SANDBOX;
        }

        /** 是否包含自定义着色器 */
        public boolean hasCustomShaders() {
            return (flags & Flags.HAS_CUSTOM_SHADERS) != 0;
        }
    }

    /**
     * 运行模式（对应 Rust mode 字段）
     */
    public enum RunMode {
        /** 沙盒模式：只添加新 Pass，不修改原有渲染流程 */
        SANDBOX("沙盒"),
        /** 注入模式：替换或增强特定 Pass */
        INJECTION("注入"),
        /** 夺舍模式：完全接管渲染管线（需要警告用户）*/
        TAKEOVER("夺舍");

        private final String displayName;
        RunMode(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    // ==================== 实例字段 ====================

    /** 文件路径 */
    private final Path filePath;

    /** 内存映射缓冲区（整个文件）*/
    private ByteBuffer mappedBuffer;

    /** 解析后的 Header */
    private final RgbHeader header;

    /** 缓存：已解析的着色器表 (name → SPIR-V data) */
    private volatile Map<String, byte[]> shaderCache;

    /** 缓存：已解析的参数表 (name → value) */
    private volatile Map<String, Float> paramCache;

    /** 是否已关闭 */
    private volatile boolean closed = false;

    // ==================== 构造函数 ====================

    private RgbBinaryReader(Path filePath, ByteBuffer buffer, RgbHeader header) {
        this.filePath = filePath;
        this.mappedBuffer = buffer;
        this.header = header;
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 从文件路径加载 .rgb 包
     * <p>
     * 使用内存映射 (mmap) 方式加载，适合大文件。
     * 文件内容不会全部复制到 JVM 堆内存。
     *
     * 【方法参数】
     * @param path Path - .rgb 文件路径
     *
     * 【返回值】
     * @return RgbBinaryReader - 已初始化的读取器实例
     *
     * 【异常】
     * @throws IOException 文件不存在或无法读取
     * @throws IllegalArgumentException 不是有效的 .rgb 文件
     */
    public static RgbBinaryReader fromFile(Path path) throws IOException {
        if (path == null || !path.toFile().exists()) {
            throw new IOException(".rgb 文件不存在: " + path);
        }

        try (FileChannel channel = FileChannel.open(path)) {
            long fileSize = channel.size();

            if (fileSize < HEADER_SIZE) {
                throw new IllegalArgumentException(
                        "文件太小，不是有效的 .rgb 包: " + fileSize + " bytes (至少需 " + HEADER_SIZE + ")");
            }

            // 使用 mmap 加载（零拷贝）
            ByteBuffer buffer = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    fileSize
            ).order(ByteOrder.LITTLE_ENDIAN);

            // 解析 Header
            RgbHeader header = parseHeader(buffer);

            if (!header.isValid()) {
                throw new IllegalArgumentException(
                        String.format("无效的 .rgb Header (magic=0x%08X, version=%d)",
                                header.magic(), header.version()));
            }

            LOGGER.info(String.format("✓ .rgb 加载成功: %s (v%d, %d bytes, mode=%s)",
                    path.getFileName(),
                    header.version(),
                    fileSize,
                    header.getRunMode().getDisplayName()));

            return new RgbBinaryReader(path, buffer, header);
        }
    }

    /**
     * 从字节数组加载 .rgb 包（用于嵌入资源等场景）
     *
     * @param data byte[] - .rgb 文件的完整字节数据
     * @return RgbBinaryReader - 已初始化的读取器实例
     * @throws IllegalArgumentException 数据无效
     */
    public static RgbBinaryReader fromBytes(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            throw new IllegalArgumentException("数据太小，不是有效的 .rgb 包");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data)
                .order(ByteOrder.LITTLE_ENDIAN);
        RgbHeader header = parseHeader(buffer);

        if (!header.isValid()) {
            throw new IllegalArgumentException(
                    String.format("无效的 .rgb Header (magic=0x%08X, version=%d)",
                            header.magic(), header.version()));
        }

        LOGGER.fine(String.format(".rgb 从字节数组加载 (v%d, %d bytes)", header.version(), data.length));
        return new RgbBinaryReader(null, buffer, header);
    }

    // ==================== Header 解析 ====================

    /**
     * 解析 64-byte Header（与 Rust RgbHeader::from_bytes 对齐）
     *
     * 【方法参数】
     * @param buffer ByteBuffer - 已定位到开头的 LE 字节缓冲区
     *
     * 【返回值】
     * @return RgbHeader - 解析后的 Header 记录
     */
    private static RgbHeader parseHeader(ByteBuffer buffer) {
        // 保存位置以便恢复
        int originalPos = buffer.position();

        try {
            buffer.position(0);

            int magic = buffer.getInt();              // offset 0, 4 bytes
            int version = buffer.getShort() & 0xFFFF; // offset 4, 2 bytes (unsigned)
            int flags = buffer.getShort() & 0xFFFF;   // offset 6, 2 bytes (unsigned)

            long renderGraphOffset = buffer.getInt() & 0xFFFFFFFFL;  // offset 8
            long renderGraphSize = buffer.getInt() & 0xFFFFFFFFL;    // offset 12

            long paramTableOffset = buffer.getInt() & 0xFFFFFFFFL;   // offset 16
            long paramTableSize = buffer.getInt() & 0xFFFFFFFFL;     // offset 20

            long shaderTableOffset = buffer.getInt() & 0xFFFFFFFFL;  // offset 24
            long shaderTableSize = buffer.getInt() & 0xFFFFFFFFL;    // offset 28

            long luaBytecodeOffset = buffer.getInt() & 0xFFFFFFFFL;  // offset 32
            long luaBytecodeSize = buffer.getInt() & 0xFFFFFFFFL;    // offset 36

            return new RgbHeader(
                    magic, version, flags,
                    renderGraphOffset, renderGraphSize,
                    paramTableOffset, paramTableSize,
                    shaderTableOffset, shaderTableSize,
                    luaBytecodeOffset, luaBytecodeSize
            );

        } finally {
            buffer.position(originalPos);
        }
    }

    // ==================== 公共查询 API ====================

    /** 获取解析后的 Header */
    public RgbHeader getHeader() { return header; }

    /** 获取运行模式 */
    public RunMode getRunMode() { return header.getRunMode(); }

    /** 获取格式版本 */
    public int getVersion() { return header.version(); }

    /** 获取标志位 */
    public int getFlags() { return header.flags(); }

    /** 文件大小（bytes）*/
    public long getFileSize() { return mappedBuffer.capacity(); }

    /** 是否有自定义着色器 */
    public boolean hasCustomShaders() { return header.hasCustomShaders(); }

    /** 是否有 Lua 脚本 */
    public boolean hasLuaScript() { return header.hasLuaBytecode(); }

    /** 是否加密 */
    public boolean isEncrypted() { return (header.flags() & Flags.ENCRYPTED) != 0; }

    // ==================== 数据段读取 API ====================

    /**
     * 获取原始渲染图数据段
     * <p>
     * 这是 CLI 编译时序列化的 PackConfig 数据，
     * 可用于反序列化为 Java 对象或直接传递给管线节点。
     *
     * 【返回值】
     * @return byte[] - 渲染图配置数据的副本（可能为空数组）
     */
    public byte[] getRenderGraphData() {
        return readSection(header.renderGraphOffset(), header.renderGraphSize());
    }

    /**
     * 获取参数表（延迟解析+缓存）
     * <p>
     * 解析格式与 Rust RgbParser::parse_parameters 对齐：
     * [count: u32] ([nameLen: u32][name: UTF-8][value: f32])*
     *
     * 【返回值】
     * @return Map&lt;String, Float&gt; - 参数名→默认值的映射（不可变）
     */
    public synchronized Map<String, Float> getParameters() {
        if (paramCache != null) {
            return paramCache;
        }

        Map<String, Float> params = new LinkedHashMap<>();
        byte[] section = readSection(header.paramTableOffset(), header.paramTableSize());

        if (section == null || section.length < 4) {
            paramCache = Collections.unmodifiableMap(params);
            return paramCache;
        }

        ByteBuffer buf = ByteBuffer.wrap(section).order(ByteOrder.LITTLE_ENDIAN);
        int count = buf.getInt();

        for (int i = 0; i < count && buf.remaining() >= 8; i++) {
            int nameLen = buf.getInt();
            if (buf.remaining() < nameLen + 4) break;

            byte[] nameBytes = new byte[nameLen];
            buf.get(nameBytes);
            String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

            float value = buf.getFloat();
            params.put(name, value);
        }

        paramCache = Collections.unmodifiableMap(params);
        LOGGER.fine("解析参数表: " + params.size() + " 个参数");
        return paramCache;
    }

    /**
     * 获取所有着色器名称列表
     *
     * 【返回值】
     * @return List&lt;String&gt; - 着色器名称列表（不可变）
     */
    public List<String> getShaderNames() {
        ensureShaderCacheParsed();
        return List.copyOf(shaderCache.keySet());
    }

    /**
     * 获取着色器数量
     *
     * 【返回值】
     * @return int - 着色器总数
     */
    public int getShaderCount() {
        ensureShaderCacheParsed();
        return shaderCache.size();
    }

    /**
     * 获取指定名称的 SPIR-V 着色器数据
     * <p>
     * 返回的是原始 SPIR-V 字节码的副本，
     * 可直接传给 Vulkan API 创建 VkShaderModule。
     *
     * 【方法参数】
     * @param shaderName String - 着色器名称（如 "shadow_map", "bloom" 等）
     *
     * 【返回值】
     * @return byte[] - SPIR-V 字节码，如果未找到返回 null
     */
    public byte[] getSpirvData(String shaderName) {
        if (shaderName == null) return null;
        ensureShaderCacheParsed();

        byte[] data = shaderCache.get(shaderName);
        // 返回副本以防止外部修改缓存
        return data != null ? data.clone() : null;
    }

    /**
     * 获取 Lua 字节码（如果有）
     * <p>
     * 返回的是原始字节码的副本，
     * 可能是 LZ4 压缩的数据（取决于 CLI 编译时的设置）。
     *
     * 【返回值】
     * @return byte[] - Lua 字节码，如果没有则返回 null
     */
    public byte[] getLuaBytecode() {
        return readSection(header.luaBytecodeOffset(), header.luaBytecodeSize());
    }

    // ==================== 内部方法 ====================

    /**
     * 读取一个数据段的副本
     *
     * @param offset 段偏移量
     * @param size   段大小
     * @return 数据副本，offset=0 或 size=0 时返回空数组
     */
    private byte[] readSection(long offset, long size) {
        if (offset == 0 || size == 0) {
            return new byte[0];
        }

        if (closed) {
            LOGGER.warning("尝试在已关闭的 RgbBinaryReader 上读取数据");
            return new byte[0];
        }

        long capacity = mappedBuffer.capacity();
        if (offset + size > capacity) {
            LOGGER.warning(String.format("数据段越界: offset=%d, size=%d, total=%d",
                    offset, size, capacity));
            return new byte[0];
        }

        byte[] result = new byte[(int) size];

        synchronized (mappedBuffer) {
            mappedBuffer.position((int) offset);
            mappedBuffer.get(result);
        }

        return result;
    }

    /**
     * 确保 Shader Table 已解析并缓存
     * <p>
     * 解析格式与 Rust RgbParser::parse_shader_table 对齐：
     * [count: u32] ([nameLen: u32][name: UTF-8][spirvLen: u32][spirvData: bytes])*
     */
    private void ensureShaderCacheParsed() {
        if (shaderCache != null) return;

        synchronized (this) {
            if (shaderCache != null) return; // double-check

            Map<String, byte[]> shaders = new LinkedHashMap<>();
            byte[] section = readSection(header.shaderTableOffset(), header.shaderTableSize());

            if (section == null || section.length < 4) {
                shaderCache = shaders;
                return;
            }

            ByteBuffer buf = ByteBuffer.wrap(section).order(ByteOrder.LITTLE_ENDIAN);
            int count = buf.getInt();

            for (int i = 0; i < count && buf.remaining() >= 8; i++) {
                // 读取名称
                int nameLen = buf.getInt();
                if (buf.remaining() < nameLen + 4) break;

                byte[] nameBytes = new byte[nameLen];
                buf.get(nameBytes);
                String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

                // 读取 SPIR-V 数据
                int spirvLen = buf.getInt();
                if (buf.remaining() < spirvLen) break;

                byte[] spirvData = new byte[spirvLen];
                buf.get(spirvData);

                shaders.put(name, spirvData);
            }

            shaderCache = shaders;
            LOGGER.fine(String.format("解析 Shader Table: %d 个着色器", shaders.size()));
        }
    }

    // ==================== 诊断和调试 ====================

    /**
     * 获取包信息摘要（用于日志/UI 显示）
     *
     * 【返回值】
     * @return String - 格式化的信息字符串
     */
    public String getSummary() {
        ensureShaderCacheParsed();

        return String.format("""
                ╔══════════════════════════════════════╗
                ║   .rgb Package Info                  ║
                ╠══════════════════════════════════════╣
                ║ File: %-31s ║
                ║ Version: v%-28d ║
                ║ Mode: %-32s ║
                ║ Flags: 0x%04X                         ║
                ║ Shaders: %-27d ║
                ║ Parameters: %-24d ║
                ║ Has Lua: %-29b ║
                ║ Size: %-30d bytes ║
                ╚══════════════════════════════════════╝""",
                filePath != null ? filePath.getFileName().toString() : "(embedded)",
                header.version(),
                header.getRunMode().getDisplayName(),
                header.flags(),
                shaderCache != null ? shaderCache.size() : 0,
                paramCache != null ? paramCache.size() : 0,
                header.hasLuaBytecode(),
                mappedBuffer.capacity()
        );
    }

    // ==================== 资源管理 ====================

    /**
     * 关闭读取器，释放内存映射资源
     * <p>
     * 必须在使用完毕后调用。
     * 关闭后缓存的解析结果仍可访问，但无法再从底层缓冲区读取新数据。
     */
    @Override
    public void close() {
        if (closed) return;

        closed = true;
        // ByteBuffer 的 MappedByteBuffer 由 GC 管理
        // 显式置空帮助 GC 回收
        mappedBuffer = null;

        LOGGER.fine("RgbBinaryReader 已关闭: " +
                (filePath != null ? filePath.getFileName() : "(embedded)"));
    }

    /** 是否已关闭 */
    public boolean isClosed() { return closed; }
}
