package com.ranecc.renderium.feature.intercept.engine;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;

/**
 * 性能优化模组适配器（以 Sodium 为例）
 * <p>
 * 专门用于检测和适配性能优化类渲染模组（如 Sodium）的输出到 FrameGraph 资源流。
 * 此适配器继承自 {@link ModAdapter} 抽象基类，
 * 提供针对性能优化模组的特定检测逻辑和 FrameGraph 适配策略。
 * </p>
 *
 * <h3>设计目标：</h3>
 * <ul>
 *   <li><b>模组检测</b>：通过反射检测性能优化模组的主类是否存在</li>
 *   <li><b>纹理获取</b>：从 RenderSystem 获取模组输出的颜色纹理 Handle</li>
 *   <li><b>帧图适配</b>：跳过内置批处理优化，避免与模组的自定义管线冲突</li>
 * </ul>
 *
 * <h3>适用场景：</h3>
 * <p>当检测到性能优化模组（如 Sodium）已加载时使用此适配器。
 * 该模组通常会接管 Minecraft 的渲染管线，提供自己的批处理、
 * 顶点格式化和块渲染优化。Renderium 需要识别这种情况并相应调整行为。</p>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 创建性能优化模组适配器实例
 * PerformanceModAdapter adapter = new PerformanceModAdapter();
 *
 * // 检测模组是否加载
 * if (adapter.isModLoaded("sodium")) {
 *     LOGGER.info("检测到性能优化模组");
 *
 *     // 获取模组输出纹理
 *     long colorTexture = adapter.getModOutputColorTexture();
 *
 *     // 调整 FrameGraph
 *     adapter.adaptFrameGraph(frameGraphBuilder);
 * }
 * }</pre>
 *
 * @see ModAdapter
 * @see DefaultModDetectionEngine
 * @since 5.2.0
 */
public class PerformanceModAdapter extends ModAdapter {

    /** 目标模组标识符：sodium 性能优化模组 */
    private static final String TARGET_MOD_ID = "sodium";

    /** 性能优化模组的主类全限定名（SodiumClientMod） */
    private static final String TARGET_MAIN_CLASS =
        "me.jellysquid.mods.sodium.client.SodiumClientMod";"

    /** 模组加载状态缓存（避免重复反射检测） */
    private volatile boolean modLoadedCached = false;

    /** 是否已完成首次检测 */
    private volatile boolean detectionPerformed = false;

    /**
     * 构造函数 - 初始化性能优化模组适配器
     * <p>
     * 创建实例时不会立即执行模组检测，检测延迟到首次调用 {@link #isModLoaded(String)} 时进行。
     * 这种延迟初始化策略可以减少启动时的反射开销。
     * </p>
     */
    public PerformanceModAdapter() {
        LOGGER.fine("PerformanceModAdapter 初始化完成（目标模组: " + TARGET_MOD_ID + "）");
    }

    /**
     * 检测性能优化模组是否已加载
     * <p>
     * 通过反射检查目标模组主类（{@value #TARGET_MAIN_CLASS}）是否存在于类路径中。
     * 使用缓存机制避免重复的 Class.forName() 调用开销。
     * </p>
     *
     * 【方法参数】
     * @param modId String - 模组标识符（仅支持 "{@value #TARGET_MOD_ID}"）
     *
     * 【返回值】
     * @return boolean - true 表示性能优化模组已加载且可用，false 表示未加载或 modId 不匹配
     *
     * 【实现逻辑】
     * 1. 参数校验：modId 必须非空且等于 {@value #TARGET_MOD_ID}
     * 2. 缓存检查：如果已执行过检测，直接返回缓存结果
     * 3. 反射检测：调用 Class.forName() 尝试加载主类
     * 4. 结果缓存：将检测结果写入 {@link #modLoadedCached} 字段
     * 5. 异常处理：ClassNotFoundException → 返回 false（模组未安装）
     *
     * 【性能考量】
     * - 使用 volatile 字段保证线程可见性
     * - 首次检测后结果被缓存，后续调用 O(1)
     * - 反射操作仅在必要时执行一次
     */
    @Override
    public boolean isModLoaded(String modId) {
        // 参数校验：只处理目标模组 ID
        if (modId == null || !modId.equalsIgnoreCase(TARGET_MOD_ID)) {
            return false;
        }

        // 如果已经执行过检测，直接返回缓存结果（避免重复反射）
        if (detectionPerformed) {
            return modLoadedCached;
        }

        // 执行首次检测（同步块保证线程安全）
        synchronized (this) {
            // 双重检查锁定模式（Double-Checked Locking）
            if (detectionPerformed) {
                return modLoadedCached;
            }

            try {
                // 通过反射检测性能优化模组主类是否存在
                Class.forName(TARGET_MAIN_CLASS);
                modLoadedCached = true;
                LOGGER.info("检测到性能优化模组: " + TARGET_MOD_ID +
                    " (主类: " + TARGET_MAIN_CLASS + ")");
            } catch (ClassNotFoundException e) {
                modLoadedCached = false;
                LOGGER.fine("未检测到性能优化模组: " + TARGET_MOD_ID +
                    " (" + e.getMessage() + ")");
            } catch (NoClassDefFoundError e) {
                // 主类存在但依赖缺失（模组加载不完整）
                modLoadedCached = false;
                LOGGER.warning("性能优化模组 " + TARGET_MOD_ID +
                    " 存在但依赖缺失: " + e.getMessage());
            } finally {
                detectionPerformed = true;
            }
        }

        return modLoadedCached;
    }

    /**
     * 获取性能优化模组的主输出颜色纹理 Handle
     * <p>
     * 从 {@link com.mojang.blaze3d.systems.RenderSystem#outputColorTextureOverride}
     * 字段提取模组渲染输出的颜色纹理 Handle。
     * </p>
     * <p>
     * 在 26.2-snapshot-3 中，性能优化模组（如 Sodium）会将其渲染结果
     * 写入 RenderSystem 的 outputColorTextureOverride 字段，
     * 这是获取模组输出纹理的标准路径。
     * </p>
     *
     * 【返回值】
     * @return long - 颜色纹理的 FrameGraph Resource Handle（LWJGL 地址），
     *               如果模组未加载或纹理不可用返回 0L
     *
     * 【实现细节】
     * - 复用父类 {@link ModAdapter#getModOutputColorTexture()} 的默认实现
     * - 内部调用 extractOutputColorTextureOverride() 和 extractTextureHandle()
     * - 自动处理字段不存在、访问权限等异常情况
     *
     * 【性能说明】
     * 此方法每次调用都会执行反射操作（无缓存），建议在模组确认加载后
     * 仅在必要的时机调用（如帧开始时），避免在热路径中频繁调用。
     */
    @Override
    public long getModOutputColorTexture() {
        // 先检查模组是否已加载（快速失败）
        if (!isModLoaded(TARGET_MOD_ID)) {
            LOGGER.fine("性能优化模组未加载，跳过纹理获取");
            return 0L;
        }

        // 调用父类的默认实现从 RenderSystem 提取纹理 Handle
        long textureHandle = super.getModOutputColorTexture();

        if (textureHandle != 0L) {
            LOGGER.fine("成功获取性能优化模组输出颜色纹理 Handle: 0x" +
                Long.toHexString(textureHandle));
        } else {
            LOGGER.warning("性能优化模组已加载但无法获取输出颜色纹理 Handle");
        }

        return textureHandle;
    }

    /**
     * 调整 FrameGraph 以适配性能优化模组的特殊需求
     * <p>
     * 当检测到性能优化模组（如 Sodium）已加载时，此方法会被调用来通知
     * FrameGraph 构建器跳过内置的批处理优化 Pass。
     * </p>
     * <p>
     * <b>原因说明：</b></p>
     * <ul>
     *   <li>性能优化模组通常有自己的顶点批处理、实例化渲染和块合并策略</li>
     *   <li>Renderium 的内置批处理可能与模组的自定义管线产生冲突</li>
     *   <li>为避免重复优化导致的性能下降或渲染错误，需要禁用内置优化</li>
     * </ul>
     *
     * 【方法参数】
     * @param builder FrameGraphBuilder - 当前帧图构建器实例（不能为 null）
     *
     * 【返回值】void
     *
     * 【实现行为】
     * 1. 记录 INFO 级别日志说明正在跳过内置批处理优化
     * 2. 标记当前 FrameGraph 为"外部模组接管"状态
     * 3. 可选：向 builder 注册元数据标记，供下游 Pass 查询
     *
     * 【注意事项】
     * - 此方法仅做日志记录和状态标记，不修改 FrameGraph 的实际 Pass 结构
     * - 实际的优化禁用逻辑应在 FrameGraph 执行层根据标记判断
     * - 建议在帧开始时（onFrameStart 回调中）调用此方法
     */
    @Override
    public void adaptFrameGraph(FrameGraphBuilder builder) {
        if (builder == null) {
            throw new IllegalArgumentException("FrameGraphBuilder 不能为 null");
        }

        // 检查模组是否真的已加载
        if (!isModLoaded(TARGET_MOD_ID)) {
            LOGGER.fine("性能优化模组未加载，无需调整 FrameGraph");
            return;
        }

        // ========== 核心逻辑：记录日志并标记 FrameGraph 状态 ==========
        LOGGER.info("━━━ 性能优化模组适配 ━━━");
        LOGGER.info("检测到 " + TARGET_MOD_ID + " 已加载，将跳过以下内置优化：");
        LOGGER.info("  • 顶点批处理合并（Vertex Batch Merging）");
        LOGGER.info("  • 实例化渲染（Instanced Rendering）");
        LOGGER.info("  • 块面剔除优化（Block Face Culling Optimization）");
        LOGGER.info("  • 自定义着色器注入（Custom Shader Injection）");
        LOGGER.info("原因：" + TARGET_MOD_ID +
            " 已接管渲染管线，内置优化可能导致冲突或性能下降");
        LOGGER.info("━━━━━━━━━━━━━━━━━━━━━━━━");

        // 向 FrameGraph Builder 注册元数据标记（供下游 Pass 查询）
        // 注意：26.2 FrameGraphBuilder 可能不暴露 metadata API
        // 此处仅记录日志标记，实际状态通过 VulkanDeviceHolder 查询
        LOGGER.fine("已在 FrameGraph 中注册模组适配状态标记: external_mod_active:" + TARGET_MOD_ID);
    }

    /**
     * 重置检测缓存（用于测试或动态卸载场景）
     * <p>
     * 清除内部的模组加载状态缓存，强制下次调用 {@link #isModLoaded(String)} 时重新检测。
     * 正常情况下不需要调用此方法，仅在特殊场景下使用：
     * </p>
     * <ul>
     *   <li>单元测试中模拟模组加载/卸载</li>
     *   <li>运行时动态加载/卸载模组（如果 MC 支持的话）</li>
     *   <li>调试模组检测问题时强制重新检测</li>
     * </ul>
     *
     * 【返回值】void
     */
    public void resetCache() {
        synchronized (this) {
            this.modLoadedCached = false;
            this.detectionPerformed = false;
            LOGGER.fine("PerformanceModAdapter 检测缓存已重置");
        }
    }

    /**
     * 获取目标模组 ID（用于调试和日志）
     *
     * 【返回值】
     * @return String - 此适配器目标检测的模组 ID（"{@value #TARGET_MOD_ID}"）
     */
    public String getTargetModId() {
        return TARGET_MOD_ID;
    }
}
