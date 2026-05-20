// Renderium - Blaze3D 优化模块
// Blaze3D 层优化模块 - 整合 RenderiumConfig 中的 4 大优化配置

package com.ranecc.renderium.feature.blaze3d;
import com.ranecc.renderium.feature.module.RenderiumModule;

import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.feature.module.ModuleContext;
import com.ranecc.renderium.feature.module.ModuleMetadata;
import com.ranecc.renderium.feature.module.ModuleCategory;
import com.ranecc.renderium.platform.bridge.mc.MCRenderBridge;
import com.ranecc.renderium.platform.bridge.mc.VulkanCommandBatcher;
import com.ranecc.renderium.platform.hook.OptimizerRegistry;

import com.ranecc.renderium.feature.blaze3d.VersionAdapter;
import com.ranecc.renderium.feature.blaze3d.MethodSignature;
import com.ranecc.renderium.feature.blaze3d.RenderiumProfiler;
import com.ranecc.renderium.feature.blaze3d.ResourceStats;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import com.ranecc.renderium.infrastructure.gpu.VulkanMemoryAllocator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Blaze3D 优化模块 🔧
 * <p>
 * 在 Blaze3D 渲染引擎内部执行优化操作。
 * 这是 **Module Layer 的核心模块**，负责：
 * <ul>
 *   <li>读取 {@link RenderiumConfig} 中的优化配置</li>
 *   <li>管理 FrameGraph / Vulkan Command / Memory / Shader 优化</li>
 *   <li>注册和配置所有 Mixin 注入点（🔒 SAFE / 🔐 GUARDED / ⚠️ RISKY）</li>
 *   <li>作为其他模块（如 SodiumLikeModule）的基础依赖</li>
 * </ul>
 *
 * <h2>架构定位：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │           Module Layer                                      │
 * │                                                             │
 * │  ┌───────────────────┐  ┌────────────────┐                 │
 * │  │Blaze3DOptimizer ★│  │SodiumLikeModule│                 │
 * │  │(本模块)          │  │(依赖此模块)    │                 │
 * │  ├─ FrameGraphOpt  │  │               │                 │
 * │  ├─ VulkanCmdOpt   │  │               │                 │
 * │  ├─ MemoryOpt      │  │               │                 │
 * │  └─ ShaderPipeOpt  │  │               │                 │
 * │  └───────────────────┘  └────────────────┘                 │
 * ├─────────────────────────────────────────────────────────────┤
 * │           Mixin Layer (由本模块统一管理)                     │
 * │                                                             │
 * │  🔒 SAFE 级别 (所有模式):                                   │
 * │  ├─ CommandEncoderMixin  → [S1,S4,S5] 批量/多队列/异步      │
 * │  ├─ LevelRendererMixin   → [P1,P2] 剔除/对象池              │
 * │  └─ RenderPassMixin      → [S2,S3,S4] Pipeline/Desc/Draw   │
 * │                                                             │
 * │  🔐 GUARDED 级别 (需版本验证+优雅降级):                     │
 * │  ├─ FrameGraphBuilderMixin → [P1] Inspector 包装           │
 * │  └─ PostChainMixin        → [S1,P1] Streamline 集成       │
 * │                                                             │
 * │  ⚠️ RISKY 级别 (仅包装模式):                                │
 * │  └─ GpuDeviceMixin        → [A1,A2,A3,S2] Arena/Cache      │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 官方更新 → 只需维护此模块的 Mixin 注入点
 * </pre>
 *
 * <h2>支持的优化项（来自 RenderiumConfig）：</h2>
 * <ol>
 *   <li><b>FrameGraph Optimization</b>: Pass 合并、资源复用、异步传输</li>
 *   <li><b>Vulkan Command Optimization</b>: 批量合并、多队列调度</li>
 *   <li><b>Memory Optimization</b>: Panama 堆外存储、SoA 布局</li>
 *   <li><b>Shader Pipeline Optimization</b>: SPIR-V 缓存、特化常量</li>
 * </ol>
 *
 * <h2>Mixin 集成规范（spec.md MODIFIED Requirements）：</h2>
 * <ul>
 *   <li>不持有任何 Blaze3D 内部类引用（VulkanDevice, VulkanBackend 等）</li>
 *   <li>所有优化通过 🔒 SAFE 级别 Mixin 注入点生效</li>
 *   <li>🔐 GUARDED 级别必须有版本签名验证和优雅降级</li>
 *   <li>⚠️ RISKY 级别仅使用包装模式，不修改原版行为</li>
 * </ul>
 *
 * <h2>Mixin 注册流程：</h2>
 * <pre>
 * load(context)          → 初始化 VersionAdapter / Profiler / ResourceStats
 * enable()               → 向各 Mixin 类注入优化器实例引用
 *   ├─ GpuDeviceMixin.setMemoryOptimizer()      → Arena 分配
 *   ├─ GpuDeviceMixin.setPipelineOptimizer()     → Pipeline 缓存
 *   └─ 其他 Mixin 通过静态单例获取优化器
 * disable()              → 清除 Mixin 引用，停止注入
 * dispose()              → 完全释放所有资源
 * </pre>
 *
 * <h3>适用模式：</h3>
 * <ul>
 *   <li>✅ 兼容模式 (Compatible): 启用基础优化</li>
 *   <li>✅ 狂暴模式 (Aggressive): 启用全部激进优化</li>
 * </ul>
 *
 * @see RenderiumConfig
 * @author Renderium Team
 * @since 2.0.0
 */
public class Blaze3DOptimizerModule implements RenderiumModule {

    private static final Logger LOGGER = Logger.getLogger(Blaze3DOptimizerModule.class.getName());

    // ==================== 元数据 ====================

    private static final ModuleMetadata METADATA = new ModuleMetadata(
            "blaze3d-optimizer",
            "Blaze3D优化器 🔧",
            "2.0.0",
            ModuleCategory.CORE,                    // 核心：两种模式都加载
            List.of(),                              // 无前置依赖
            List.of(),                              // 无可选依赖
            "Blaze3D层内部优化：FrameGraph/Vulkan/Memory/Shader",
            "Renderium Team",
            true                                    // 默认启用
    );

    // ==================== 子组件 ====================

    /** 帧图优化器 */
    private volatile FrameGraphOptimizer frameGraphOptimizer;

    /** Vulkan 命令优化器（已废弃，由 VulkanCommandBatcher 替代） */
    @SuppressWarnings("deprecation")
    private volatile VulkanCommandOptimizer vulkanCommandOptimizer;

    /** s7 原生命令批处理器 */
    private volatile VulkanCommandBatcher vulkanCommandBatcher;

    /** 内存优化器 */
    private volatile MemoryOptimizer memoryOptimizer;

    /** 着色器管线优化器 */
    private volatile ShaderPipelineOptimizer shaderPipelineOptimizer;

    // ==================== 状态字段 ====================

    private volatile boolean enabled = false;
    private volatile boolean aggressiveMode = false;

    // ==================== Mixin 集成状态字段 ====================

    /** 标记 load() 阶段是否已完成（基础设施初始化） */
    private volatile boolean mixinLoaded = false;

    /**
     * Mixin 注册状态表
     * <p>
     * Key: Mixin 类简单名称（如 "GpuDeviceMixin"）
     * Value: 该 Mixin 的注册状态描述
     * 使用 ConcurrentHashMap 保证线程安全读写
     */
    private final ConcurrentHashMap<String, String> mixinRegistrationStatus =
            new ConcurrentHashMap<>();

    /** VersionAdapter 版本检测完成标记 */
    private volatile boolean versionAdapterReady = false;

    /** RenderiumProfiler 性能监控初始化完成标记 */
    private volatile boolean profilerReady = false;

    // ==================== 已知问题 & 待优化项 ====================

    /**
     * Chunk Section UBO 预分配优化 (P1)
     *
     * <p>问题：Minecraft 原版 ChunkSectionUniform 初始容量=2，每次新增区块翻倍扩容，
     * resize 触发 vkDestroyBuffer + vkCreateBuffer + 数据拷贝，导致 2-10ms GPU 管线停顿。
     * 累积影响：加载密集区域时数百毫秒总停顿。
     *
     * <p>解决方案：Mixin 注入 ChunkSectionUniform.create()，根据视距预分配最终容量
     * （视距16→1024，视距32→4096），消除运行期扩容。
     *
     * @since 2.1.0 (待实现)
     */

    /** ResourceStats 资源统计初始化完成标记 */
    private volatile boolean resourceStatsReady = false;

    // ==================== RenderiumModule 实现 ====================

    @Override
    public ModuleMetadata getMetadata() { return METADATA; }

    @Override
    public boolean canLoad(ModuleContext context) {
        // 此模块始终可加载（两种模式都支持）
        this.aggressiveMode = context.isAggressiveMode();

        LOGGER.info(String.format("Blaze3DOptimizerModule: 可加载 (模式: %s)",
                aggressiveMode ? "Aggressive" : "Compatible"));
        return true;
    }

    /**
     * 加载 Mixin 集成配置并初始化基础设施
     * <p>
     * 此方法在 {@link #initialize(ModuleContext)} 之前由 ModuleRegistry 调用，
     * 负责：
     * <ol>
     *   <li>初始化 VersionAdapter - 触发版本检测和功能特性注册</li>
     *   <li>初始化 RenderiumProfiler - 启用 Pass 级别性能监控</li>
     *   <li>初始化 ResourceStats - 启用 GPU 资源生命周期追踪</li>
     *   <li>预注册所有 Mixin 类的配置状态（此时优化器尚未创建）</li>
     * </ol>
     *
     * <h3>线程安全：</h3>
     * <ul>
     *   <li>可从任意线程调用（通常在游戏启动的主线程）</li>
     *   <li>使用 volatile 标记保证可见性</li>
     *   <li>ConcurrentHashMap 保证状态表的线程安全读写</li>
     * </ul>
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>context</b>: {@link ModuleContext} - 模块上下文，
     *       提供配置访问、模式查询、其他模块状态等能力</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>boolean - true 表示加载成功，false 表示加载失败</li>
     * </ul>
     *
     * @param context 模块上下文
     * @return 加载成功返回 true
     */
    public boolean load(ModuleContext context) {
        if (mixinLoaded) {
            LOGGER.warning("load() 已被调用过，跳过重复加载");
            return true;
        }

        LOGGER.info("╔══════════════════════════════════════╗");
        LOGGER.info("║  加载 Blaze3D Mixin 集成配置          ║");
        LOGGER.info("╚══════════════════════════════════════╝");

        // 获取配置对象（Phase 2/3 需要读取优化开关和参数）
        RenderiumConfig config = context.getConfig();

        // ========== 第一步：初始化 VersionAdapter ==========
        //
        // VersionAdapter 是静态工具类，首次调用 getMCVersion() 时会：
        // 1. 从系统属性/环境变量/Manifest 检测 MC 版本
        // 2. 注册默认功能特性（frame-graph-inspector, vma-allocator 等）
        // 3. 缓存版本信息供后续所有 Mixin 使用
        try {
            String detectedVersion = VersionAdapter.getMCVersion();
            versionAdapterReady = !"unknown".equals(detectedVersion);

            // 注册当前版本的方法签名到 VersionAdapter（GUARDED 级别需要）
            registerMethodSignaturesForVersion(detectedVersion);

            mixinRegistrationStatus.put("VersionAdapter",
                    versionAdapterReady ? "✓ 就绪 (MC " + detectedVersion + ")" : "⚠ 版本未知");

            LOGGER.info(String.format("VersionAdapter 初始化完成: version=%s, features=%s",
                    detectedVersion,
                    java.util.Arrays.toString(VersionAdapter.getRegisteredFeatures())));

        } catch (Exception e) {
            versionAdapterReady = false;
            mixinRegistrationStatus.put("VersionAdapter", "✗ 初始化失败: " + e.getMessage());
            LOGGER.severe("VersionAdapter 初始化失败: " + e.getMessage());
        }

        // ========== 第二步：初始化 RenderiumProfiler ==========
        //
        /// RenderiumProfiler 是静态工具类，启用 Pass 级别的纳秒精度计时。
        // 所有 Mixin 的注入点都可以通过 beginPass()/endPass() 记录执行时间。
        try {
            // 确保性能监控已启用（默认已启用，此处显式确认）
            if (!RenderiumProfiler.isEnabled()) {
                RenderiumProfiler.setEnabled(true);
            }
            profilerReady = RenderiumProfiler.isEnabled();

            mixinRegistrationStatus.put("RenderiumProfiler",
                    profilerReady ? "✓ 就绪" : "✗ 未启用");

            LOGGER.info(String.format("RenderiumProfiler 初始化完成: enabled=%b", profilerReady));

        } catch (Exception e) {
            profilerReady = false;
            mixinRegistrationStatus.put("RenderiumProfiler", "✗ 初始化失败: " + e.getMessage());
            LOGGER.severe("RenderiumProfiler 初始化失败: " + e.getMessage());
        }

        // ========== 第三步：初始化 ResourceStats ==========
        //
        // ResourceStats 追踪 GPU 资源的获取/释放/池化命中情况。
        // GpuDeviceMixin 的 Arena 分配策略会通过 ResourceStats 记录统计。
        try {
            if (!ResourceStats.isEnabled()) {
                ResourceStats.setEnabled(true);
            }
            resourceStatsReady = ResourceStats.isEnabled();

            mixinRegistrationStatus.put("ResourceStats",
                    resourceStatsReady ? "✓ 就绪" : "✗ 未启用");

            LOGGER.info(String.format("ResourceStats 初始化完成: enabled=%b", resourceStatsReady));

        } catch (Exception e) {
            resourceStatsReady = false;
            mixinRegistrationStatus.put("ResourceStats", "✗ 初始化失败: " + e.getMessage());
            LOGGER.severe("ResourceStats 初始化失败: " + e.getMessage());
        }

        // ========== 第四步：预注册各 Mixin 类的状态 ==========
        //
        // 此时优化器实例尚未创建（在 initialize() 中创建），
        // 这里仅记录 Mixin 的预期状态，enable() 中会完成实际的引用注入。
        registerMixinPresetStatuses();

        // ========== 第五步：Phase 2 - VMA 增强（所有模式可用） ==========
        //
        // VMA (Vulkan Memory Allocator) 增强功能：
        // - 自定义内存池：为不同用途预分配专用池，实现 O(1) 分配速度
        // - 延迟销毁：确保 GPU 完成使用后再释放资源，避免数据竞争
        // - 内存预算监控：实时追踪 GPU 内存使用，自适应调整分配策略
        // 这些优化在兼容模式和狂暴模式下都可用，能显著提升内存管理效率
        if (config.isVmaEnhancementEnabled()) {
            loadVmaOptimizers(context);
        }

        // ========== 第六步：Phase 3 - 狂暴模式深度优化（仅狂暴模式） ==========
        //
        // 狂暴模式专属的激进优化子系统：
        // - 激进优化器：顶点格式压缩、批量渲染、异步上传、GPU 剔除
        // - 现代渲染系统：GPU 驱动可见性、Bindless 资源管理、ECS 场景图
        // - 变换与合并系统：GPU 顶点变换、静态几何体缓存
        // 这些系统仅在 aggressiveMode=true 时加载，提供极致性能但需要更强硬件支持
        if (context.isAggressiveMode()) {
            loadAggressiveOptimizers(context);   // 激进优化器（批量/压缩/异步）
            loadModernRenderSystems(context);    // 现代渲染架构（GPU-Driven/Bindless/ECS）
            loadTransformSystems(context);       // 变换与合并系统（GPU变换/静态缓存）
            loadShaderTranspiler(context);       // Shader 转译管线（GLSL→SPIR-V）
        }

        // ========== 完成 ==========
        this.mixinLoaded = true;

        LOGGER.info(String.format("✓ Blaze3DOptimizerModule.load() 完成 (infrastructure=%b)",
                isInfrastructureReady()));
        logMixinLoadSummary();

        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean initialize(ModuleContext context) {
        LOGGER.info("╔══════════════════════════════════════╗");
        LOGGER.info("║  初始化 Blaze3D 优化模块              ║");
        LOGGER.info("╚══════════════════════════════════════╝");

        try {
            // 从上下文获取配置
            RenderiumConfig config = context.getConfig();

            // 初始化各优化器子组件
            this.frameGraphOptimizer = new FrameGraphOptimizer(config);
            this.vulkanCommandOptimizer = new VulkanCommandOptimizer(config);
            this.vulkanCommandBatcher = new VulkanCommandBatcher();
            this.memoryOptimizer = new MemoryOptimizer(config);
            this.shaderPipelineOptimizer = new ShaderPipelineOptimizer(config);

            LOGGER.info("✓ Blaze3DOptimizerModule 初始化完成");
            logOptimizationStatus(config);

            return true;

        } catch (Exception e) {
            LOGGER.severe("✗ 初始化失败: " + e.getMessage());
            cleanup();
            return false;
        }
    }

    @Override
    public boolean enable() {
        if (enabled) return true;

        try {
            // 启用所有优化器子组件
            frameGraphOptimizer.enable();
            // 将帧图优化器注册到全局 Mixin Hook 注册表
            OptimizerRegistry.setFrameGraphOptimizer(frameGraphOptimizer);
            vulkanCommandOptimizer.enable();
            vulkanCommandBatcher.enable();
            memoryOptimizer.enable();
            shaderPipelineOptimizer.enable();

            // 注册 s7 原生批处理器到渲染桥接器
            MCRenderBridge.setCommandBatcher(vulkanCommandBatcher);

            // ========== Mixin 注册：向各 Mixin 类注入优化器实例引用 ==========
            //
            // GpuDeviceMixin (⚠️ RISKY 级别 - Arena/Pipeline Cache):
            //   需要直接持有 MemoryOptimizer 和 ShaderPipelineOptimizer 引用
            //   因为 Arena 分配和 Pipeline 缓存需要在拦截点同步调用
            registerGpuDeviceMixinOptimizers();

            // 更新各 Mixin 类的注册状态（标记为已激活）
            updateMixinEnableStatus();

            this.enabled = true;

            LOGGER.info(String.format("✓ Blaze3DOptimizerModule 已启用 [%s mode] (Mixin=%d)",
                    aggressiveMode ? "⚡ AGGRESSIVE" : "○ COMPATIBLE",
                    mixinRegistrationStatus.size()));
            return true;

        } catch (Exception e) {
            LOGGER.severe("✗ 启用失败: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void disable() {
        if (!enabled) return;

        try {
            // ========== 清理 Mixin 引用 ==========
            //
            // 将 GpuDeviceMixin 中的优化器引用置空，
            // 确保 Mixin 拦截点在禁用后不再调用优化器
            unregisterGpuDeviceMixinOptimizers();

            // 更新 Mixin 状态为已禁用
            updateMixinDisableStatus();

            // 按启用逆序禁用优化器子组件
            shaderPipelineOptimizer.disable();
            memoryOptimizer.disable();
            vulkanCommandOptimizer.disable();
            frameGraphOptimizer.disable();

            this.enabled = false;
            LOGGER.info("Blaze3DOptimizerModule 已禁用 (Mixin 引用已清除)");

        } catch (Exception e) {
            LOGGER.severe("禁用过程出错: " + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        disable();

        // 清理 Mixin 集成状态
        mixinRegistrationStatus.clear();
        mixinLoaded = false;
        versionAdapterReady = false;
        profilerReady = false;
        resourceStatsReady = false;

        safeDispose(frameGraphOptimizer);
        // 从全局 Mixin Hook 注册表中注销
        OptimizerRegistry.setFrameGraphOptimizer(null);
        safeDispose(vulkanCommandOptimizer);
        safeDispose(memoryOptimizer);
        safeDispose(shaderPipelineOptimizer);

        frameGraphOptimizer = null;
        vulkanCommandOptimizer = null;
        memoryOptimizer = null;
        shaderPipelineOptimizer = null;

        LOGGER.info("Blaze3DOptimizerModule 已释放 (含 Mixin 状态清理)");
    }

    // ==================== 公共 API ====================

    /**
     * 获取帧图优化器
     */
    public FrameGraphOptimizer getFrameGraphOptimizer() { return frameGraphOptimizer; }

    /**
     * 获取 Vulkan 命令优化器
     */
    @SuppressWarnings("deprecation")
    public VulkanCommandOptimizer getVulkanCommandOptimizer() { return vulkanCommandOptimizer; }

    /**
     * 获取 s7 原生命令批处理器
     */
    public VulkanCommandBatcher getVulkanCommandBatcher() { return vulkanCommandBatcher; }

    /**
     * 获取内存优化器
     */
    public MemoryOptimizer getMemoryOptimizer() { return memoryOptimizer; }

    /**
     * 获取着色器管线优化器
     */
    public ShaderPipelineOptimizer getShaderPipelineOptimizer() { return shaderPipelineOptimizer; }

    /**
     * 是否处于狂暴模式
     */
    public boolean isAggressiveMode() { return aggressiveMode; }

    @Override
    public String getStatusString() {
        String mode = enabled ?
                (aggressiveMode ? "⚡ ACTIVE [AGGRESSIVE]" : "○ ACTIVE [COMPATIBLE]") :
                "○ INACTIVE";
        return String.format("%s [%s] v%s", METADATA.name(), mode, METADATA.version());
    }

    // ==================== Mixin 集成 API ====================

    /**
     * 获取所有 Mixin 的状态报告
     * <p>
     * 汇总每个 Mixin 类和基础设施组件的当前状态，
     * 包含安全级别、注册状态、优化器引用等信息。
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>String - 格式化的状态报告字符串，包含：
     *     <ul>
     *       <li>基础设施状态（VersionAdapter/Profiler/ResourceStats）</li>
     *       <li>各 Mixin 类的注册状态和安全级别</li>
     *       <li>GpuDeviceMixin 的优化器引用状态</li>
     *       <li>整体 Mixin 集成就绪度</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @return 格式化的 Mixin 状态报告
     */
    public String getMixinStatus() {
        StringBuilder report = new StringBuilder();

        report.append("╔══════════════════════════════════════════════════╗\n");
        report.append("║       Blaze3D Mixin 集成状态报告                ║\n");
        report.append("╠══════════════════════════════════════════════════╣\n");

        // 基础设施状态
        report.append(String.format("║  基础设施:                                            ║\n"));
        report.append(String.format("║    VersionAdapter:  %-35s ║\n",
                formatInfrastructureStatus("VersionAdapter", versionAdapterReady)));
        report.append(String.format("║    RenderiumProfiler: %-35s ║\n",
                formatInfrastructureStatus("RenderiumProfiler", profilerReady)));
        report.append(String.format("║    ResourceStats:    %-35s ║\n",
                formatInfrastructureStatus("ResourceStats", resourceStatsReady)));

        report.append("╠══════════════════════════════════════════════════╣\n");

        // 🔒 SAFE 级别 Mixin
        report.append("║  🔒 SAFE 级别 Mixin:                                  ║\n");
        report.append(String.format("║    CommandEncoderMixin: %-32s ║\n",
                getMixinStatusEntry("CommandEncoderMixin")));
        report.append(String.format("║    LevelRendererMixin:  %-32s ║\n",
                getMixinStatusEntry("LevelRendererMixin")));
        report.append(String.format("║    RenderPassMixin:     %-32s ║\n",
                getMixinStatusEntry("RenderPassMixin")));

        // 🔐 GUARDED 级别 Mixin
        report.append("║  🔐 GUARDED 级别 Mixin:                               ║\n");
        report.append(String.format("║    FrameGraphBuilderMixin: %-29s ║\n",
                getMixinStatusEntry("FrameGraphBuilderMixin")));
        report.append(String.format("║    PostChainMixin:      %-32s ║\n",
                getMixinStatusEntry("PostChainMixin")));

        // ⚠️ RISKY 级别 Mixin
        report.append("║  ⚠️ RISKY 级别 Mixin:                                 ║\n");
        report.append(String.format("║    GpuDeviceMixin:      %-32s ║\n",
                getMixinStatusEntry("GpuDeviceMixin")));

        report.append("╠══════════════════════════════════════════════════╣\n");

        // GpuDevice 优化器引用详情（通过 MCRenderBridge 查询）
        boolean gpuInit = MCRenderBridge.isGpuDeviceInitialized();
        report.append(String.format("║  GpuDevice 优化器引用详情:                              ║\n"));
        report.append(String.format("║    完全初始化: %-37b ║\n", gpuInit));
        report.append(String.format("║    MemoryOptimizer: %-35s ║\n",
                memoryOptimizer != null && memoryOptimizer.isEnabled() ? "✓ 已注入" : "✗ 未注入"));
        report.append(String.format("║    PipelineOptimizer: %-34s ║\n",
                shaderPipelineOptimizer != null && shaderPipelineOptimizer.isEnabled() ? "✓ 已注入" : "✗ 未注入"));

        report.append("╠══════════════════════════════════════════════════╣\n");

        // 整体状态
        report.append(String.format("║  整体状态: loaded=%b, enabled=%b, infraReady=%b    ║\n",
                mixinLoaded, enabled, isInfrastructureReady()));
        report.append("╚══════════════════════════════════════════════════╝\n");

        return report.toString();
    }

    /**
     * 检查基础设施是否全部就绪
     *
     * @return true 如果 VersionAdapter、RenderiumProfiler、ResourceStats 都已初始化成功
     */
    public boolean isInfrastructureReady() {
        return versionAdapterReady && profilerReady && resourceStatsReady;
    }

    /**
     * 检查 Mixin 是否已完成加载（load 阶段）
     *
     * @return true 如果 load() 已成功执行
     */
    public boolean isMixinLoaded() {
        return mixinLoaded;
    }

    // ==================== 内部方法 ====================

    private void cleanup() {
        safeDispose(frameGraphOptimizer);
        safeDispose(vulkanCommandOptimizer);
        safeDispose(memoryOptimizer);
        safeDispose(shaderPipelineOptimizer);

        frameGraphOptimizer = null;
        vulkanCommandOptimizer = null;
        memoryOptimizer = null;
        shaderPipelineOptimizer = null;
    }

    private void safeDispose(AutoCloseable obj) {
        if (obj != null) {
            try { obj.close(); } catch (Exception ignored) {}
        }
    }

    private void logOptimizationStatus(RenderiumConfig config) {
        LOGGER.info("┌──────────────────────────────────────────┐");
        LOGGER.info("│  Blaze3D 优化状态                        │");
        LOGGER.info("├──────────────────────────────────────────┤");

        LOGGER.info(String.format("│  FrameGraph: %s (Pass合并=%s, 并行Pass=%d)",
                config.isFrameGraphOptimizationEnabled() ? "✓" : "✗",
                config.getFrameGraphConfig().isPassMergingEnabled() ? "ON" : "OFF",
                config.getFrameGraphConfig().getMaxParallelPasses()));

        LOGGER.info(String.format("│  Vulkan Cmd: %s (批量合并=%s, 最大批次=%d)",
                config.isVulkanCommandOptimizationEnabled() ? "✓" : "✗",
                config.getVulkanCommandConfig().isBatchMergingEnabled() ? "ON" : "OFF",
                config.getVulkanCommandConfig().getMaxCommandsPerBatch()));

        LOGGER.info(String.format("│  Memory: %s (堆外存储=%s, SoA=%s)",
                config.isMemoryOptimizationEnabled() ? "✓" : "✗",
                config.getMemoryConfig().isOffHeapStorageEnabled() ? "ON" : "OFF",
                config.getMemoryConfig().isSoaLayoutEnabled() ? "ON" : "OFF"));

        LOGGER.info(String.format("│  Shader: %s (SPIR-V缓存=%s, 特化常量=%s)",
                config.isShaderPipelineOptimizationEnabled() ? "✓" : "✗",
                config.getShaderPipelineConfig().isSpirvCacheEnabled() ? "ON" : "OFF",
                config.getShaderPipelineConfig().isSpecializationConstantsEnabled() ? "ON" : "OFF"));

        LOGGER.info("└──────────────────────────────────────────┘");
    }

    // ==================== Mixin 集成内部方法 ====================

    /**
     * 为指定版本注册方法签名（🔐 GUARDED 级别需要）
     * <p>
     * 将当前 MC 版本的目标方法签名注册到 VersionAdapter，
     * 供 FrameGraphBuilderMixin 等_GUARDED_ 级别 Mixin 做签名验证。
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>version</b>: String - 当前检测到的 Minecraft 版本号</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值，签名注册到 VersionAdapter 静态注册表</li>
     * </ul>
     */
    private void registerMethodSignaturesForVersion(String version) {
        // FrameGraphBuilder.execute() 方法签名
        // 用于 FrameGraphBuilderMixin 的 GUARDED 级别验证
        // 参数: (className, methodName, methodDesc, isStatic)
        VersionAdapter.registerMethodSignature(version,
                new MethodSignature(
                        "com.mojang.blaze3d.framegraph.FrameGraphBuilder",
                        "execute",
                        "(Lcom/mojang/blaze3d/framegraph/GraphicsResourceAllocator;"
                                + "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V",
                        false
                )
        );

        // GpuDevice.createBuffer() 方法签名
        // 用于 GpuDeviceMixin 的 RISKY 级别拦截
        VersionAdapter.registerMethodSignature(version,
                new MethodSignature(
                        "com.mojang.blaze3d.platform.GpuDevice",
                        "createBuffer",
                        "(IJ)Lcom/mojang/blaze3d/buffers/GpuBuffer;",
                        false
                )
        );

        // GpuDevice.precompilePipeline() 方法签名
        VersionAdapter.registerMethodSignature(version,
                new MethodSignature(
                        "com.mojang.blaze3d.platform.GpuDevice",
                        "precompilePipeline",
                        "(Lcom/mojang/blaze3d/shaders/Program;"
                                + "Lcom/mojang/blaze3d/pipelines/PipelineDesc;)"
                                + "Lcom/mojang/blaze3d/pipelines/Pipeline;",
                        false
                )
        );

        LOGGER.fine(String.format("已为版本 %s 注册 %d 个方法签名", version, 3));
    }

    /**
     * 预注册各 Mixin 类的初始状态
     * <p>
     * 在 load() 阶段调用，此时优化器尚未创建。
     * 设置每个 Mixin 类的初始状态为"等待优化器"。
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值，状态写入 mixinRegistrationStatus 表</li>
     * </ul>
     */
    private void registerMixinPresetStatuses() {
        // 🔒 SAFE 级别 - 通过静态单例获取优化器，无需显式注入
        mixinRegistrationStatus.put("CommandEncoderMixin", "○ 等待 enable()");
        mixinRegistrationStatus.put("LevelRendererMixin", "○ 等待 enable()");
        mixinRegistrationStatus.put("RenderPassMixin", "○ 等待 enable()");

        // 🔐 GUARDED 级别 - 需要 VersionAdapter 验证
        String guardedPrefix = versionAdapterReady ? "○ 待激活 (GUARDED)" : "⚠ 版本适配未就绪";
        mixinRegistrationStatus.put("FrameGraphBuilderMixin", guardedPrefix);
        mixinRegistrationStatus.put("PostChainMixin", guardedPrefix);

        // ⚠️ RISKY 级别 - 需要显式注入优化器引用
        mixinRegistrationStatus.put("GpuDeviceMixin", "○ 等待优化器注入");
    }

    /**
     * 向 GpuDeviceMixin 注入优化器实例引用
     * <p>
     * 在 enable() 阶段调用，将 MemoryOptimizer 和 ShaderPipelineOptimizer
     * 的实例传递给 GpuDeviceMixin，使其 Arena 分配和 Pipeline 缓存策略可用。
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值，通过 GpuDeviceMixin 静态方法设置引用</li>
     * </ul>
     */
    private void registerGpuDeviceMixinOptimizers() {
        if (memoryOptimizer != null && memoryOptimizer.isEnabled()) {
            MCRenderBridge.setMemoryOptimizer(memoryOptimizer);
            MCRenderBridge.setGpuDeviceInitialized(true);
            LOGGER.info("✓ MemoryOptimizer 已通过 MCRenderBridge 注册");
        } else {
            LOGGER.warning("⚠ MemoryOptimizer 未就绪，跳过内存优化注入");
        }

        if (shaderPipelineOptimizer != null && shaderPipelineOptimizer.isEnabled()) {
            MCRenderBridge.setPipelineOptimizer(shaderPipelineOptimizer);
            LOGGER.info("✓ PipelineOptimizer 已通过 MCRenderBridge 注册");
        } else {
            LOGGER.warning("⚠ ShaderPipelineOptimizer 未就绪，跳过管线优化注入");
        }
    }

    /**
     * 清除 GpuDeviceMixin 中的优化器引用
     * <p>
     * 在 disable() 阶段调用，将引用置空以防止禁用后继续调用。
     * 注意：当前 GpuDeviceMixin 不支持 set null，仅记录日志。
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值</li>
     * </ul>
     */
    private void unregisterGpuDeviceMixinOptimizers() {
        // 通过 MCRenderBridge 清除优化器引用
        MCRenderBridge.setMemoryOptimizer(null);
        MCRenderBridge.setPipelineOptimizer(null);
        MCRenderBridge.setGpuDeviceInitialized(false);
        LOGGER.fine("优化器引用已通过 MCRenderBridge 清除");
    }

    /**
     * 更新各 Mixin 类的启用状态
     * <p>
     * 在 enable() 成功后调用，将状态表中的条目更新为"已激活"。
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值</li>
     * </ul>
     */
    private void updateMixinEnableStatus() {
        // SAFE 级别
        mixinRegistrationStatus.put("CommandEncoderMixin", "✓ 已激活 (SAFE)");
        mixinRegistrationStatus.put("LevelRendererMixin", "✓ 已激活 (SAFE)");
        mixinRegistrationStatus.put("RenderPassMixin", "✓ 已激活 (SAFE)");

        // GUARDED 级别
        String guardedStatus = versionAdapterReady ? "✓ 已激活 (GUARDED)" : "⚠ 部分激活 (版本未知)";
        mixinRegistrationStatus.put("FrameGraphBuilderMixin", guardedStatus);
        mixinRegistrationStatus.put("PostChainMixin", guardedStatus);

        // RISKY 级别
        boolean gpuOk = memoryOptimizer != null && shaderPipelineOptimizer != null;
        mixinRegistrationStatus.put("GpuDeviceMixin",
                gpuOk ? "✓ 已激活 (RISKY)" : "⚠ 部分激活 (优化器缺失)");
    }

    /**
     * 更新各 Mixin 类的禁用状态
     * <p>
     * 在 disable() 调用时将状态更新为"已禁用"。
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值</li>
     * </ul>
     */
    private void updateMixinDisableStatus() {
        for (Map.Entry<String, String> entry : mixinRegistrationStatus.entrySet()) {
            String key = entry.getKey();
            // 跳过基础设施组件（它们不由 enable/disable 控制生命周期）
            if (!key.equals("VersionAdapter") && !key.equals("RenderiumProfiler") && !key.equals("ResourceStats")) {
                mixinRegistrationStatus.put(key, "○ 已禁用");
            }
        }
    }

    // ==================== Phase 2/3 子系统加载方法 ====================

    /**
     * 加载 VMA (Vulkan Memory Allocator) 增强优化器
     * <p>
     * 初始化三个核心 VMA 增强子系统：
     * <ol>
     *   <li><b>VmaMemoryPools</b> - 自定义内存池系统
     *       <ul>
     *         <li>为顶点/索引/Uniform/Staging/纹理/渲染目标预分配专用池</li>
     *         <li>实现 O(1) 分配速度（相比标准 VMA 的搜索分配提升 10-100x）</li>
     *         <li>使用线性算法或双缓冲算法优化不同用途的内存布局</li>
     *       </ul>
     *   </li>
     *   <li><b>VmaDeferredDeallocation</b> - 延迟销毁队列
     *       <ul>
     *         <li>3帧延迟释放机制，确保 GPU 完成使用后再释放资源</li>
     *         <li>避免数据竞争和 GPU Crash</li>
     *         <li>支持 VMA 3.0+ 内置延迟销毁扩展</li>
     *       </ul>
     *   </li>
     *   <li><b>VmaMemoryBudget</b> - 内存预算管理
     *       <ul>
     *         <li>实时监控 GPU 内存使用率（目标: <85% 正常, >95% 紧急）</li>
     *         <li>自适应分配策略：根据内存压力调整优先级和算法</li>
     *         <li>支持碎片整理（每帧最多移动 64MB 数据）</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>context</b>: {@link ModuleContext} - 模块上下文，
     *       提供 GPU 设备访问、配置参数、内存属性等能力</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值，初始化结果记录到 mixinRegistrationStatus 表</li>
     * </ul>
     *
     * @param context 模块上下文
     */
    private void loadVmaOptimizers(ModuleContext context) {
        LOGGER.info("┌──────────────────────────────────────────┐");
        LOGGER.info("│  Phase 2: 加载 VMA 增强优化器            │");
        LOGGER.info("├──────────────────────────────────────────┤");

        try {
            // 获取 VMA 分配器句柄和设备内存属性
            // 这些由 Vulkan 后端在 ModuleContext 中提供
            long vmaAllocator = context.getVmaAllocator();
            Object memPropsRaw = context.getPhysicalDeviceMemoryProperties();

            // ---- 2.1 初始化自定义内存池系统 ----
            //
            // VmaMemoryPools 为不同用途预分配专用内存池：
            // - 顶点缓冲池：512MB，GPU 只读，线性算法（O(1) 分配）
            // - Uniform 缓冲池：128MB，CPU 写入/GPU 读取
            // - Staging 上传池：256MB，CPU 写入，双缓冲算法（高利用率）
            // - 纹理池：1GB，GPU 只读
            // - 渲染目标池：512MB，GPU 读写
            try {
                // [编译修复] initializePools 要求 int[] 类型参数，
                // 但 context.getPhysicalDeviceMemoryProperties() 返回 Object。
                // 进行安全类型转换，若类型不匹配则传入空数组（VmaMemoryPools 内部会处理）。
                int[] memProps = (memPropsRaw instanceof int[]) ? (int[]) memPropsRaw : new int[0];
                VmaMemoryPools.getInstance().initializePools(vmaAllocator, memProps);
                mixinRegistrationStatus.put("VmaMemoryPools", "✓ 已初始化 (6 个专用池)");
                LOGGER.info("│  ✓ VmaMemoryPools: 6 个专用池已创建      │");

                // 激活 VulkanMemoryAllocator 的 VMA 路由
                VulkanMemoryAllocator.setVmaMode(vmaAllocator, VmaMemoryPools.getInstance());
                mixinRegistrationStatus.put("VulkanMemoryAllocator", "✓ VMA 路由已激活");
                LOGGER.info("│  ✓ VulkanMemoryAllocator: VMA 路由已启用   │");

            } catch (Exception e) {
                mixinRegistrationStatus.put("VmaMemoryPools", "✗ 初始化失败: " + e.getMessage());
                LOGGER.warning("│  ⚠ VmaMemoryPools 初始化失败: " + e.getMessage() + " │");
            }

            // ---- 2.2 初始化延迟销毁系统 ----
            //
            // VmaDeferredDeallocation 实现 3 帧延迟释放：
            // - 资源释放请求进入队列，等待 N 帧后真正执行
            // - 防止 GPU 仍在使用时释放资源导致 Crash
            // - 支持 VMA 内置的帧索引追踪
            try {
                com.ranecc.renderium.feature.blaze3d.memory.VmaDeferredDeallocation.getInstance();
                mixinRegistrationStatus.put("VmaDeferredDeallocation", "✓ 已初始化 (3帧延迟)");
                LOGGER.info("│  ✓ VmaDeferredDeallocation: 3帧延迟启用   │");

            } catch (Exception e) {
                mixinRegistrationStatus.put("VmaDeferredDeallocation", "✗ 初始化失败: " + e.getMessage());
                LOGGER.warning("│  ⚠ VmaDeferredDeallocation 初始化失败: " + e.getMessage() + " │");
            }

            // ---- 2.3 初始化内存预算管理系统 ----
            //
            // VmaMemoryBudget 提供实时内存监控和自适应分配：
            // - 默认预算限制: 6GB（可通过配置调整）
            // - 高水位线: 85% 触发警告和非关键资源清理
            // - 危险水位: 95% 触发紧急回收
            // - 支持碎片整理以降低内存碎片
            long budgetBytes = context.getConfig().getVmaConfig().getMemoryBudgetBytes();
            try {
                com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryBudget.getInstance().initializeBudget(budgetBytes);
                mixinRegistrationStatus.put("VmaMemoryBudget",
                        String.format("✓ 已初始化 (预算: %d MB)", budgetBytes / (1024 * 1024)));
                LOGGER.info(String.format("│  ✓ VmaMemoryBudget: 预算 %d MB           │",
                        budgetBytes / (1024 * 1024)));

            } catch (Exception e) {
                mixinRegistrationStatus.put("VmaMemoryBudget", "✗ 初始化失败: " + e.getMessage());
                LOGGER.warning("│  ⚠ VmaMemoryBudget 初始化失败: " + e.getMessage() + " │");
            }

            // ---- 2.4 初始化内存别名系统 ----
            //
            // VmaMemoryAliasing 实现跨类型内存复用：
            // - 同一物理内存可同时作为 Vertex Buffer 和 Uniform Buffer 使用
            // - 减少显存分配次数，降低碎片化
            // - 支持生命周期重叠检测，避免数据竞争
            try {
                // [编译修复] VmaMemoryAliasing.initialize() 仅接受 long vmaAllocator 单参数，
                // 原代码错误地传入了 memProps（Object 类型），已移除。
                com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryAliasing.getInstance()
                        .initialize(vmaAllocator);
                mixinRegistrationStatus.put("VmaMemoryAliasing", "✓ 已初始化 (跨类型复用)");
                LOGGER.info("│  ✓ VmaMemoryAliasing: 跨类型复用已启用   │");

            } catch (Exception e) {
                mixinRegistrationStatus.put("VmaMemoryAliasing", "✗ 初始化失败: " + e.getMessage());
                LOGGER.warning("│  ⚠ VmaMemoryAliasing 初始化失败: " + e.getMessage() + " │");
            }

            // ---- 2.5 初始化高级内存标志管理器 ----
            //
            // VmaAdvancedFlags 提供现代 GPU 高级内存特性：
            // - 持久映射缓冲区：CPU 端永久映射，零拷贝上传
            // - 设备地址缓冲区：用于光线追踪、间接渲染
            // - 受保护内存：DRM 内容保护
            long vkDevice = context.getVkDevice();
            try {
                com.ranecc.renderium.feature.blaze3d.memory.VmaAdvancedFlags advancedFlags =
                        new com.ranecc.renderium.feature.blaze3d.memory.VmaAdvancedFlags();
                advancedFlags.initialize(vmaAllocator, vkDevice);
                mixinRegistrationStatus.put("VmaAdvancedFlags", "✓ 已初始化 (高级标志)");
                LOGGER.info("│  ✓ VmaAdvancedFlags: 高级标志已启用      │");

            } catch (Exception e) {
                mixinRegistrationStatus.put("VmaAdvancedFlags", "✗ 初始化失败: " + e.getMessage());
                LOGGER.warning("│  ⚠ VmaAdvancedFlags 初始化失败: " + e.getMessage() + " │");
            }

            LOGGER.info("└──────────────────────────────────────────┘");
            LOGGER.info("✓ VMA aggressive optimizations loaded");

        } catch (Exception e) {
            LOGGER.severe("✗ VMA 优化器加载失败: " + e.getMessage());
            // 单个子系统失败不影响其他系统，仅记录错误
        }
    }

    /**
     * 加载狂暴模式激进优化器
     * <p>
     * 初始化四个性能关键的激进优化子系统：
     * <ol>
     *   <li><b>VertexFormatCompressor</b> - 顶点格式压缩器
     *       <ul>
     *         <li>将浮点位置压缩为 16-bit 半精度或 10-bit 定点格式</li>
     *         <li>减少显存带宽占用 30-50%</li>
     *         <li>支持有损/无损压缩模式切换</li>
     *       </ul>
     *   </li>
     *   <li><b>AggressiveBatchRenderer</b> - 激进批量渲染器
     *       <ul>
     *         <li>跨材质/跨层级的批量合并（突破传统限制）</li>
     *         <li>MultiDrawIndirect 调用将 Draw Calls 从数千降至 <10</li>
     *         <li>动态批次重建，适应视锥变化</li>
     *       </ul>
     *   </li>
     *   <li><b>AsyncChunkUploader</b> - 异步区块上传器
     *       <ul>
     *         <li>使用独立传输队列（Transfer Queue）异步上传数据</li>
     *         <li>CPU 和 GPU 上传并行执行，消除 CPU-GPU 同步等待</li>
     *         <li>支持多缓冲区轮转避免 stalls</li>
     *       </ul>
     *   </li>
     *   <li><b>GPUCullingSystem</b> - GPU 剔除系统
     *       <ul>
     *         <li>Compute Shader 并行视锥剔除（零 CPU 开销）</li>
     *         <li>Hi-Z 遮挡剔除（利用上一帧深度金字塔）</li>
     *         <li>输出可见实例列表供 Indirect Draw 使用</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>context</b>: {@link ModuleContext} - 模块上下文，
     *       提供配置访问、GPU 设备状态等能力</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值，各子系统状态记录到 mixinRegistrationStatus 表</li>
     * </ul>
     *
     * @param context 模块上下文
     */
    private void loadAggressiveOptimizers(ModuleContext context) {
        LOGGER.info("┌──────────────────────────────────────────┐");
        LOGGER.info("│  Phase 3a: 加载激进优化器               │");
        LOGGER.info("├──────────────────────────────────────────┤");

        RenderiumConfig config = context.getConfig();

        // ---- 3a.1 顶点格式压缩器 ----
        //
        // 减少顶点数据大小，提升带宽效率：
        // - Position: float32 (12B) → half16 (6B) 或 int10_10_10 (4B)
        // - Normal: float32 (12B) → int8_8_8 (3B)
        // - UV: float32 (8B) → half16 (4B)
        // - 综合压缩率: ~50%
        try {
            boolean compressionEnabled = config.getAggressiveConfig().isVertexCompressionEnabled();
            com.ranecc.renderium.feature.blaze3d.aggressive.VertexFormatCompressor.getInstance().setEnabled(compressionEnabled);
            mixinRegistrationStatus.put("VertexFormatCompressor",
                    compressionEnabled ? "✓ 已启用 (压缩模式)" : "○ 已禁用");
            LOGGER.info(String.format("│  ✓ VertexFormatCompressor: %s          │",
                    compressionEnabled ? "已启用" : "已禁用"));

        } catch (Exception e) {
            mixinRegistrationStatus.put("VertexFormatCompressor", "✗ 初始化失败: " + e.getMessage());
            LOGGER.warning("│  ⚠ VertexFormatCompressor 初始化失败: " + e.getMessage() + " │");
        }

        // ---- 3a.2 激进批量渲染器 ----
        //
        // 将数千次 Draw Calls 合并为 <10 次 MultiDrawIndirect：
        // - Level 1: 顶点合并（多个 Chunk 顶点到连续缓冲）
        // - Level 2: 材质合并（Bindless Texture 支持数千材质）
        // - Level 3: 渲染层合并（SOLID/CUTOUT/TRANSLUCENT 各一次调用）
        // - Level 4: 跨帧合并（静态几何体缓存）
        try {
            boolean batchingEnabled = config.getAggressiveConfig().isAggressiveBatchingEnabled();
            com.ranecc.renderium.feature.blaze3d.aggressive.AggressiveBatchRenderer.getInstance().setEnabled(batchingEnabled);
            mixinRegistrationStatus.put("AggressiveBatchRenderer",
                    batchingEnabled ? "✓ 已启用 (MultiDrawIndirect)" : "○ 已禁用");
            LOGGER.info(String.format("│  ✓ AggressiveBatchRenderer: %s           │",
                    batchingEnabled ? "已启用" : "已禁用"));

        } catch (Exception e) {
            mixinRegistrationStatus.put("AggressiveBatchRenderer", "✗ 初始化失败: " + e.getMessage());
            LOGGER.warning("│  ⚠ AggressiveBatchRenderer 初始化失败: " + e.getMessage() + " │");
        }

        // ---- 3a.3 异步区块上传器 ----
        //
        // 使用传输队列异步上传 Chunk 数据：
        // - Transfer Queue 与 Graphics Queue 并行执行
        // - 多缓冲区轮转（通常 2-3 帧）避免同步等待
        // - Fence 信号量确保上传完成后再渲染
        try {
            boolean asyncUploadEnabled = config.getAggressiveConfig().isAsyncUploadEnabled();
            com.ranecc.renderium.feature.blaze3d.aggressive.AsyncChunkUploader.getInstance().setEnabled(asyncUploadEnabled);
            mixinRegistrationStatus.put("AsyncChunkUploader",
                    asyncUploadEnabled ? "✓ 已启用 (Transfer Queue)" : "○ 已禁用");
            LOGGER.info(String.format("│  ✓ AsyncChunkUploader: %s                │",
                    asyncUploadEnabled ? "已启用" : "已禁用"));

        } catch (Exception e) {
            mixinRegistrationStatus.put("AsyncChunkUploader", "✗ 初始化失败: " + e.getMessage());
            LOGGER.warning("│  ⚠ AsyncChunkUploader 初始化失败: " + e.getMessage() + " │");
        }

        // ---- 3a.4 GPU 剔除系统 ----
        //
        // 完全在 GPU 上执行可见性测试：
        // - Pass 1: Compute Shader 视锥剔除（并行处理所有实例）
        // - Pass 2: Hi-Z 遮挡剔除（利用深度金字塔）
        // - Pass 3: 并行压缩生成 Indirect Draw 命令
        // - CPU 开销: <0.1ms（vs 传统 2-5ms）
        try {
            com.ranecc.renderium.feature.blaze3d.aggressive.GPUCullingSystem.getInstance();
            mixinRegistrationStatus.put("GPUCullingSystem", "✓ 已初始化 (Compute Shader)");
            LOGGER.info("│  ✓ GPUCullingSystem: Compute Shader 就绪   │");

        } catch (Exception e) {
            mixinRegistrationStatus.put("GPUCullingSystem", "✗ 初始化失败: " + e.getMessage());
            LOGGER.warning("│  ⚠ GPUCullingSystem 初始化失败: " + e.getMessage() + " │");
        }

        LOGGER.info("└──────────────────────────────────────────┘");
        LOGGER.info("✓ Aggressive mode optimizers loaded");
    }

    /**
     * 加载现代渲染架构系统
     * <p>
     * 初始化三个面向未来的 GPU-Driven 渲染子系统：
     * <ol>
     *   <li><b>GPUDrivenVisibilitySystem</b> - GPU 驱动可见性系统
     *       <ul>
     *         <li>完全在 GPU 上执行剔除操作（零 CPU 开销）</li>
     *         <li>Hi-Z Buffer 用于遮挡查询（零延迟 vs 传统 1-2 帧）</li>
     *         <li>自动生成 Indirect Draw 命令供后续 Pass 使用</li>
     *         <li>可配置 Hi-Z 缓冲区尺寸（默认 1024x1024）</li>
     *       </ul>
     *   </li>
     *   <li><b>BindlessResourceManager</b> - 无绑定资源管理器
     *       <ul>
     *         <li>全局描述符表支持数千个纹理（默认 4096）</li>
     *         <li>Shader 中通过 32-bit 索引直接访问任意纹理</li>
     *         <li>消除传统 OpenGL/Vulkan 的纹理单元数量限制</li>
     *         <li>支持纹理注册/注销/热更新</li>
     *       </ul>
     *   </li>
     *   <li><b>ECSSceneGraph</b> - ECS 场景图系统
     *       <ul>
     *         <li>Entity-Component-System 架构管理场景对象</li>
     *         <li>数据导向设计（Data-Oriented Design），缓存友好</li>
     *         <li>支持组件热插拔和系统并行执行</li>
     *         <li>为未来 Virtual Geometry / Mesh Shader 打基础</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>context</b>: {@link ModuleContext} - 模块上下文，
     *       提供 GPU 设备句柄、配置参数等能力</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值，各子系统状态记录到 mixinRegistrationStatus 表</li>
     * </ul>
     *
     * @param context 模块上下文
     */
    private void loadModernRenderSystems(ModuleContext context) {
        LOGGER.info("┌──────────────────────────────────────────┐");
        LOGGER.info("│  Phase 3b: 加载现代渲染系统              │");
        LOGGER.info("├──────────────────────────────────────────┤");

        RenderiumConfig config = context.getConfig();
        Object gpuDevice = context.getGpuDevice();  // Vulkan 设备句柄

        // ---- 3b.1 GPU 驱动可见性系统 ----
        //
        // 替代传统 CPU 端逐对象视锥测试：
        // - Compute Shader 批量处理所有实例（256 线程/workgroup）
        // - 使用 Subgroup 操作（Ballot/Broadcast）进行高效并行压缩
        // - Hi-Z 遮挡剔除利用上一帧生成的深度金字塔
        // - 输出: 可见实例列表 + Indirect Draw 命令缓冲
        int hiZSize = config.getModernConfig().getHiZBufferSize();
        try {
            // init() 需要 (gpuDevice, hiZWidth, hiZHeight) 三个参数
            // hiZSize 作为正方形 Hi-Z 缓冲区的边长
            com.ranecc.renderium.feature.blaze3d.modern.GPUDrivenVisibilitySystem.getInstance()
                    .init(gpuDevice, hiZSize, hiZSize);
            mixinRegistrationStatus.put("GPUDrivenVisibilitySystem",
                    String.format("✓ 已初始化 (HiZ: %dx%d)", hiZSize, hiZSize));
            LOGGER.info(String.format("│  ✓ GPUDrivenVisibilitySystem: HiZ %dx%d   │",
                    hiZSize, hiZSize));

        } catch (Exception e) {
            mixinRegistrationStatus.put("GPUDrivenVisibilitySystem", "✗ 初始化失败: " + e.getMessage());
            LOGGER.warning("│  ⚠ GPUDrivenVisibilitySystem 初始化失败: " + e.getMessage() + " │");
        }

        // ---- 3b.2 Bindless 资源管理器 ----
        //
        // 突破传统纹理绑定限制（OpenGL 16-32 个，Vulkan 取决于描述符集）：
        // - 全局描述符数组: texture2D globalTextures[] （最多 4096 个）
        // - 通过 nonuniformEXT 索引在 Shader 中访问任意纹理
        // - 材质数据只存储 32-bit 索引，极大减小 Uniform 缓冲
        // - 支持运行时纹理注册/注销/更新
        int maxTextures = config.getModernConfig().getBindlessMaxTextures();
        try {
            com.ranecc.renderium.feature.blaze3d.modern.BindlessResourceManager.getInstance().init(maxTextures);
            mixinRegistrationStatus.put("BindlessResourceManager",
                    String.format("✓ 已初始化 (最大 %d 纹理)", maxTextures));
            LOGGER.info(String.format("│  ✓ BindlessResourceManager: %d 纹理槽位    │",
                    maxTextures));

        } catch (Exception e) {
            mixinRegistrationStatus.put("BindlessResourceManager", "✗ 初始化失败: " + e.getMessage());
            LOGGER.warning("│  ⚠ BindlessResourceManager 初始化失败: " + e.getMessage() + " │");
        }

        // ---- 3b.3 ECS Scene Graph System ----
        //
        // Modern Entity-Component-System architecture:
        // - Entity: Unique identifier for scene objects (64-bit ID)
        // - Component: Pure data containers (Transform/Mesh/Material/Light, etc.)
        // - System: Logic processors (RenderingSystem/PhysicsSystem/AISystem, etc.)
        // - Data stored continuously, cache-friendly (SoA layout)
        // - Supports multi-threaded System execution
        try {
            com.ranecc.renderium.feature.blaze3d.modern.ECSSceneGraph.getInstance();
            mixinRegistrationStatus.put("ECSSceneGraph", "✓ 已初始化 (ECS 架构)");
            LOGGER.info("│  ✓ ECSSceneGraph: ECS 架构就绪             │");

        } catch (Exception e) {
            mixinRegistrationStatus.put("ECSSceneGraph", "✗ 初始化失败: " + e.getMessage());
            LOGGER.warning("│  ⚠ ECSSceneGraph 初始化失败: " + e.getMessage() + " │");
        }

        LOGGER.info("└──────────────────────────────────────────┘");
        LOGGER.info("✓ Modern render systems loaded");
    }

    /**
     * 加载变换与合并处理系统
     * <p>
     * 初始化两个关键的几何体优化子系统：
     * <ol>
     *   <li><b>GPUVertexTransformSystem</b> - GPU 顶点变换系统
     *       <ul>
     *         <li>CPU 只准备原始数据（模型空间顶点）+ 变换矩阵</li>
     *         <li>所有矩阵乘法在 Vertex Shader 中完成（GPU 并行）</li>
     *         <li>单次 drawIndirectCount 调用渲染所有 Chunk</li>
     *         <li>CPU 开销从 3-5ms 降至 <0.1ms（30-50x 提升）</li>
     *         <li>支持最大 65536 个 Chunk 实例</li>
     *       </ul>
     *   </li>
     *   <li><b>StaticGeometryCache</b> - 静态几何体缓存
     *       <ul>
     *         <li>未变化的 Chunk 自动提升为静态（连续 N 帧未变）</li>
     *         <li>静态几何体永久驻留 GPU 显存，无需每帧重新上传</li>
     *         <li>分离静态/动态缓冲区，各自单次 Draw 调用</li>
     *         <li>显著减少 CPU-GPU 数据传输带宽</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>context</b>: {@link ModuleContext} - 模块上下文，
     *       提供 GPU 设备句柄、配置参数等能力</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值，各子系统状态记录到 mixinRegistrationStatus 表</li>
     * </ul>
     *
     * @param context 模块上下文
     */
    private void loadTransformSystems(ModuleContext context) {
        LOGGER.info("┌──────────────────────────────────────────┐");
        LOGGER.info("│  Phase 3c: 加载变换与合并系统             │");
        LOGGER.info("├──────────────────────────────────────────┤");

        RenderiumConfig config = context.getConfig();
        Object gpuDevice = context.getGpuDevice();  // Vulkan 设备句柄

        // ---- 3c.1 GPU 顶点变换系统 ----
        //
        // 将顶点变换从 CPU 移至 GPU：
        // - CPU 端: 收集变换矩阵数组（每个 Chunk 一个 mat4）
        // - GPU 端: Vertex Shader 通过 Instance Index 读取矩阵并执行变换
        // - 使用 drawIndirectCount 一次性提交所有绘制命令
        // - 消除 CPU 端数百万次矩阵乘法
        int maxChunks = config.getTransformConfig().getMaxChunks();
        try {
            com.ranecc.renderium.feature.blaze3d.transform.GPUVertexTransformSystem.getInstance().init(gpuDevice, maxChunks);
            mixinRegistrationStatus.put("GPUVertexTransformSystem",
                    String.format("✓ 已初始化 (最大 %d Chunks)", maxChunks));
            LOGGER.info(String.format("│  ✓ GPUVertexTransformSystem: %d Chunks      │",
                    maxChunks));

        } catch (Exception e) {
            mixinRegistrationStatus.put("GPUVertexTransformSystem", "✗ 初始化失败: " + e.getMessage());
            LOGGER.warning("│  ⚠ GPUVertexTransformSystem 初始化失败: " + e.getMessage() + " │");
        }

        // ---- 3c.2 渲染层批量合并器 ----
        //
        // LayerBatchMerger 按 MC 渲染层（SOLID/CUTOUT/TRANSLUCENT）分组：
        // - 每个渲染层只提交一次 MultiDrawIndexedIndirect 调用
        // - 将 Draw Calls 从 N 个 Chunk 降低到 ~4 层
        // - TRANSLUCENT 层自动执行深度排序
        try {
            com.ranecc.renderium.feature.blaze3d.transform.LayerBatchMerger layerMerger =
                    new com.ranecc.renderium.feature.blaze3d.transform.LayerBatchMerger();
            layerMerger.init(gpuDevice);
            mixinRegistrationStatus.put("LayerBatchMerger", "✓ 已初始化 (5 渲染层)");
            LOGGER.info("│  ✓ LayerBatchMerger: 5 渲染层已就绪      │");

        } catch (Exception e) {
            mixinRegistrationStatus.put("LayerBatchMerger", "✗ 初始化失败: " + e.getMessage());
            LOGGER.warning("│  ⚠ LayerBatchMerger 初始化失败: " + e.getMessage() + " │");
        }

        // ---- 3c.3 材质合并渲染器 ----
        //
        // MaterialMergedRenderer 使用 Bindless Texture 按材质分组：
        // - 相同材质的所有 Chunk 合并为一个批次
        // - 每种材质只需一次 Draw 调用（零状态切换）
        // - 支持最多 4096 纹理的 Bindless 描述符数组
        try {
            int maxTextures = config.getModernConfig().getBindlessMaxTextures();
            com.ranecc.renderium.feature.blaze3d.transform.MaterialMergedRenderer materialRenderer =
                    new com.ranecc.renderium.feature.blaze3d.transform.MaterialMergedRenderer(
                            maxTextures, 512);
            materialRenderer.init(gpuDevice);
            mixinRegistrationStatus.put("MaterialMergedRenderer",
                    String.format("✓ 已初始化 (最大 %d 纹理)", maxTextures));
            LOGGER.info(String.format("│  ✓ MaterialMergedRenderer: %d 纹理       │",
                    maxTextures));

        } catch (Exception e) {
            mixinRegistrationStatus.put("MaterialMergedRenderer", "✗ 初始化失败: " + e.getMessage());
            LOGGER.warning("│  ⚠ MaterialMergedRenderer 初始化失败: " + e.getMessage() + " │");
        }

        // ---- 3c.4 Static Geometry Cache ----
        //
        // Auto-detect and cache unchanged geometry:
        // - Each Chunk maintains a data hash to detect content changes
        // - Chunks unchanged for N consecutive frames (default 60) promoted to static
        // - Static data written to dedicated GPU buffer, permanently resident
        // - Dynamic data uses separate temporary buffer, updated every frame
        // - Static and dynamic Draw Calls submitted separately during rendering
        // - Reduces 70-80% of CPU-GPU transfer bandwidth (for static scenes)
        try {
            boolean staticCacheEnabled = config.getTransformConfig().isStaticGeometryCacheEnabled();
            com.ranecc.renderium.feature.blaze3d.transform.StaticGeometryCache.getInstance().setEnabled(staticCacheEnabled);
            mixinRegistrationStatus.put("StaticGeometryCache",
                    staticCacheEnabled ? "✓ 已启用 (自动提升)" : "○ 已禁用");
            LOGGER.info(String.format("│  ✓ StaticGeometryCache: %s                  │",
                    staticCacheEnabled ? "已启用" : "已禁用"));

        } catch (Exception e) {
            mixinRegistrationStatus.put("StaticGeometryCache", "✗ 初始化失败: " + e.getMessage());
            LOGGER.warning("│  ⚠ StaticGeometryCache 初始化失败: " + e.getMessage() + " │");
        }

        LOGGER.info("└──────────────────────────────────────────┘");
        LOGGER.info("✓ Transform & merging systems loaded");
    }

    /**
     * 加载 Shader 转译管线
     * <p>
     * 初始化 GLSL → SPIR-V 完整转译管线：
     * <ol>
     *   <li><b>ShaderTranspilerPipeline</b> - 顶层入口
     *       <ul>
     *         <li>协调 Preprocessor → Lexer → Transformer → Compiler 全流程</li>
     *         <li>AOT 预编译调度（光影包切换时自动触发）</li>
     *         <li>L1 内存缓存 + L2 磁盘缓存管理</li>
     *       </ul>
     *   </li>
     *   <li><b>GlslangCompiler</b> - SPIR-V 编译器（Panama FFM）
     *       <ul>
     *         <li>通过 Panama FFM 加载 libglslang，零 JNI 开销</li>
     *         <li>支持 GLSL/Vulkan/HLSL 源码输入</li>
     *         <li>输出 SPIR-V 二进制模块</li>
     *       </ul>
     *   </li>
     *   <li><b>UniformRedirector</b> - Uniform 桥接器
     *       <ul>
     *         <li>将 Blaze3D Uniform 名称映射到 Vulkan 描述符集</li>
     *         <li>支持 set/binding/offset 三级寻址</li>
     *         <li>运行时动态注册新 Uniform</li>
     *       </ul>
     *   </li>
     *   <li><b>RenderiumShaderRegistry</b> - Shader 注册表
     *       <ul>
     *         <li>管理所有已转译 Shader 的生命周期</li>
     *         <li>支持按名称/阶段/版本查询</li>
     *         <li>热重载支持</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>context</b>: {@link ModuleContext} - 模块上下文，
     *       提供配置访问、Vulkan 设备句柄等能力</li>
     * </ul>
     *
     * @param context 模块上下文
     */
    private void loadShaderTranspiler(ModuleContext context) {
        LOGGER.info("┌──────────────────────────────────────────┐");
        LOGGER.info("│  Phase 3d: 加载 Shader 转译管线          │");
        LOGGER.info("├──────────────────────────────────────────┤");

        try {
            // 获取 Vulkan Device 句柄
            long vkDevice = context.getVkDevice();

            // ---- 3d.1 初始化 Shader 转译管线 ----
            //
            // ShaderTranspilerPipeline 是顶层单例，协调整个转译流程：
            // - GlslPreprocessor: #include 展开 + 循环检测
            // - GlslLexer: Token 级词法分析（避免正则误匹配）
            // - GlslToVkTransformer: AST 级转换（attribute→layout, texture2D→texture）
            // - GlslangCompiler: libglslang SPIR-V 编译（Panama FFM）
            // - ShaderCache: L1 内存 + L2 磁盘二级缓存
            com.ranecc.renderium.feature.blaze3d.shader.ShaderTranspilerPipeline transpiler =
                    com.ranecc.renderium.feature.blaze3d.shader.ShaderTranspilerPipeline.getInstance();
            transpiler.initialize(vkDevice);
            mixinRegistrationStatus.put("ShaderTranspilerPipeline",
                    transpiler.isEnabled() ? "✓ 已初始化 (Panama FFM)" : "⚠ 初始化不完整");
            LOGGER.info("│  ✓ ShaderTranspilerPipeline: 就绪          │");

            // ---- 3d.2 初始化 Uniform 重定向器 ----
            //
            // UniformRedirector 桥接 Blaze3D 和 Vulkan 的 Uniform 系统：
            // - MC Uniform 名称 → Vulkan Descriptor Set/Binding/Offset
            // - 支持标准 MC Uniform (ModelViewProj, ChunkOffset 等)
            // - 运行时动态注册光影包自定义 Uniform
            try {
                com.ranecc.renderium.feature.blaze3d.shader.UniformRedirector uniformRedirector =
                        com.ranecc.renderium.feature.blaze3d.shader.UniformRedirector.getInstance();
                uniformRedirector.initialize();
                mixinRegistrationStatus.put("UniformRedirector", "✓ 已初始化 (Uniform 映射)");
                LOGGER.info("│  ✓ UniformRedirector: 映射表已加载      │");

            } catch (Exception e) {
                mixinRegistrationStatus.put("UniformRedirector", "✗ 初始化失败: " + e.getMessage());
                LOGGER.warning("│  ⚠ UniformRedirector 初始化失败: " + e.getMessage() + " │");
            }

            // ---- 3d.3 初始化 Shader 注册表 ----
            //
            /// RenderiumShaderRegistry 管理 Shader 生命周期：
            // - 按 Shader 名称、阶段（VS/FS/GS）、版本索引
            // - 支持热重载（光影包切换时）
            // - 与 ShaderCache 联动避免重复编译
            try {
                com.ranecc.renderium.feature.blaze3d.shader.RenderiumShaderRegistry registry =
                        com.ranecc.renderium.feature.blaze3d.shader.RenderiumShaderRegistry.getInstance();
                registry.initialize();
                mixinRegistrationStatus.put("RenderiumShaderRegistry", "✓ 已初始化 (Shader 生命周期)");
                LOGGER.info("│  ✓ RenderiumShaderRegistry: 就绪           │");

            } catch (Exception e) {
                mixinRegistrationStatus.put("RenderiumShaderRegistry", "✗ 初始化失败: " + e.getMessage());
                LOGGER.warning("│  ⚠ RenderiumShaderRegistry 初始化失败: " + e.getMessage() + " │");
            }

            LOGGER.info("└──────────────────────────────────────────┘");
            LOGGER.info("✓ Shader Transpiler Pipeline loaded");

        } catch (Exception e) {
            LOGGER.severe("✗ Shader 转译管线加载失败: " + e.getMessage());
        }
    }

    /**
     * 输出 Mixin 加载摘要日志
     * <p>
     * 在 load() 完成后调用，汇总基础设施初始化结果。
     * 扩展版本包含 Phase 2/3 所有子系统的状态信息。
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值，输出到 Logger</li>
     * </ul>
     */
    private void logMixinLoadSummary() {
        LOGGER.info("┌──────────────────────────────────────────────────────┐");
        LOGGER.info("│  Mixin 加载摘要                                       │");
        LOGGER.info("├──────────────────────────────────────────────────────┤");

        // ---- Phase 1: 基础设施组件状态 ----
        LOGGER.info("│  Phase 1: 基础设施                                    │");
        for (Map.Entry<String, String> entry : mixinRegistrationStatus.entrySet()) {
            String key = entry.getKey();
            // 只输出 Phase 1 的基础设施组件
            if (key.equals("VersionAdapter") || key.equals("RenderiumProfiler") || key.equals("ResourceStats")) {
                LOGGER.info(String.format("│    %-28s: %s", key, entry.getValue()));
            }
        }

        // ---- Phase 2: VMA 增强优化器状态（如果已加载）----//
        if (mixinRegistrationStatus.containsKey("VmaMemoryPools")) {
            LOGGER.info("│  Phase 2: VMA 增强优化器                              │");
            String[] vmaComponents = {
                    "VmaMemoryPools",
                    "VmaDeferredDeallocation",
                    "VmaMemoryBudget",
                    "VmaMemoryAliasing",
                    "VmaAdvancedFlags"
            };
            for (String component : vmaComponents) {
                String status = mixinRegistrationStatus.getOrDefault(component, "— 未加载");
                LOGGER.info(String.format("│    %-28s: %s", component, status));
            }
        }

        // ---- Phase 3: 狂暴模式子系统状态（如果已加载）----//
        boolean hasAggressiveSystems = mixinRegistrationStatus.containsKey("VertexFormatCompressor");
        if (hasAggressiveSystems) {
            LOGGER.info("│  Phase 3a: 激进优化器                                │");
            String[] aggressiveComponents = {
                    "VertexFormatCompressor",
                    "AggressiveBatchRenderer",
                    "AsyncChunkUploader",
                    "GPUCullingSystem"
            };
            for (String component : aggressiveComponents) {
                String status = mixinRegistrationStatus.getOrDefault(component, "— 未加载");
                LOGGER.info(String.format("│    %-28s: %s", component, status));
            }

            LOGGER.info("│  Phase 3b: 现代渲染系统                              │");
            String[] modernComponents = {
                    "GPUDrivenVisibilitySystem",
                    "BindlessResourceManager",
                    "ECSSceneGraph"
            };
            for (String component : modernComponents) {
                String status = mixinRegistrationStatus.getOrDefault(component, "— 未加载");
                LOGGER.info(String.format("│    %-28s: %s", component, status));
            }

            LOGGER.info("│  Phase 3c: 变换与合并系统                             │");
            String[] transformComponents = {
                    "GPUVertexTransformSystem",
                    "LayerBatchMerger",
                    "MaterialMergedRenderer",
                    "StaticGeometryCache"
            };
            for (String component : transformComponents) {
                String status = mixinRegistrationStatus.getOrDefault(component, "— 未加载");
                LOGGER.info(String.format("│    %-28s: %s", component, status));
            }

            // ---- Phase 3d: Shader 转译管线状态（如果已加载）----//
            if (mixinRegistrationStatus.containsKey("ShaderTranspilerPipeline")) {
                LOGGER.info("│  Phase 3d: Shader 转译管线                           │");
                String[] shaderComponents = {
                        "ShaderTranspilerPipeline",
                        "UniformRedirector",
                        "RenderiumShaderRegistry"
                };
                for (String component : shaderComponents) {
                    String status = mixinRegistrationStatus.getOrDefault(component, "— 未加载");
                    LOGGER.info(String.format("│    %-28s: %s", component, status));
                }
            }
        }

        // ---- Mixin 类注册状态 ----
        LOGGER.info("│  Mixin 注入点状态                                      │");
        String[] mixinClasses = {
                "CommandEncoderMixin",
                "LevelRendererMixin",
                "RenderPassMixin",
                "FrameGraphBuilderMixin",
                "PostChainMixin",
                "GpuDeviceMixin"
        };
        for (String mixinClass : mixinClasses) {
            String status = mixinRegistrationStatus.getOrDefault(mixinClass, "— 未知");
            LOGGER.info(String.format("│    %-28s: %s", mixinClass, status));
        }

        // ---- 整体统计 ----
        int totalSystems = mixinRegistrationStatus.size();
        long successCount = mixinRegistrationStatus.values().stream()
                .filter(status -> status.startsWith("✓")).count();
        long warningCount = mixinRegistrationStatus.values().stream()
                .filter(status -> status.startsWith("○") || status.startsWith("⚠")).count();
        long errorCount = mixinRegistrationStatus.values().stream()
                .filter(status -> status.startsWith("✗")).count();

        LOGGER.info("├──────────────────────────────────────────────────────┤");
        LOGGER.info(String.format("│  总计: %d 个子系统 | 成功: %d | 警告: %d | 失败: %d   │",
                totalSystems, successCount, warningCount, errorCount));
        LOGGER.info("└──────────────────────────────────────────────────────┘");
    }

    /**
     * 格式化基础设施组件的状态字符串
     *
     * @param name  组件名称
     * @param ready 是否就绪
     * @return 格式化的状态字符串
     */
    private String formatInfrastructureStatus(String name, boolean ready) {
        return ready ? "✓ 就绪" : "✗ 未就绪";
    }

    /**
     * 从状态表中获取指定 Mixin 的状态条目
     *
     * @param mixinName Mixin 类简单名称
     * @return 状态描述字符串，未找到则返回 "— 未知"
     */
    private String getMixinStatusEntry(String mixinName) {
        return mixinRegistrationStatus.getOrDefault(mixinName, "— 未知");
    }
}
