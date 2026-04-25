// Renderium - 光影系统 v2.0
// RGB Shader 模块 - 重构版，集成兼容性检测和管线节点系统

package com.renderium.module.impl.shader;

import com.renderium.interception.engine.DefaultModDetectionEngine;
import com.renderium.interception.engine.ModDetectionEngine;
import com.renderium.bridge.mc.FrameDataSnapshot;
import com.renderium.bridge.mc.MCRenderBridge;
import com.renderium.bridge.mc.MatrixContext;
import com.renderium.bridge.mc.ProjectionContext;
import com.renderium.bridge.mc.RenderiumLifecycleManager;
import com.renderium.module.ModuleCategory;
import com.renderium.module.ModuleContext;
import com.renderium.module.ModuleMetadata;
import com.renderium.module.RenderiumModule;
import com.renderium.pipeline.node.PipelineNode;
import com.renderium.pipeline.node.PipelineNodeRegistry;

import java.util.List;
import java.util.logging.Logger;

/**
 * RGB Shader 模块（重构版）
 * <p>
 * 在 Blaze3D 渲染管线**之前/之后**提供自定义着色器（光影）的加载和执行入口。
 *
 * <h2>启用条件（必须全部满足）：</h2>
 * <ol>
 *   <li>运行模式为 "aggressive" (狂暴模式)</li>
 *   <li>没有不兼容模组（Sodium/Iris/Oculus 等）</li>
 *   <li>Vulkan 设备已初始化</li>
 * </ol>
 *
 * <h2>架构定位：</h2>
 * <pre>
 * Minecraft 原生渲染调用
 *      ↓
 * ┌─────────────────────────────┐
 * │  兼容性检查层               │
 * │  (ModDetectionEngine)       │
 * └─────────────────────────────┘
 *      ↓ 通过
 * ┌─────────────────────────────┐
 * │  Mixin 拦截层               │
 * │  (VulkanBackend/FrameGraph) │
 * └─────────────────────────────┘
 *      ↓
 * ┌─────────────────────────────┐
 * │  PipelineNodeRegistry ★    │ ← 管理所有渲染节点
 * │                             │
 * │  • ShadowMapNode (阴影)     │
 * │  • SSAONode (环境光遮蔽)    │
 * │  • BloomNode (泛光)         │
 * │  • TonemapNode (色调映射)   │
 * │  • ... (更多节点)           │
 * └─────────────────────────────┘
 *      ↓
 * Blaze3D FrameGraph (已修改)
 *      ↓
 * 超分辨率 (DLSS/FSR)
 *      ↓
 * 屏幕输出
 * </pre>
 *
 * @see PipelineNodeRegistry
 * @see DefaultModDetectionEngine
 * @see com.renderium.graphics.backend.PassRouter
 * @since 2.1.0 (重构)
 */
public class RGBShaderModule implements RenderiumModule {

    private static final Logger LOGGER = Logger.getLogger(RGBShaderModule.class.getName());

    // ==================== 元数据 ====================

    private static final ModuleMetadata METADATA = new ModuleMetadata(
            "rgb-shader-loader",
            "RGB光影包加载器 v2.0",
            "2.1.0",
            ModuleCategory.SHADER,
            List.of("vulkan-backend"),              // 依赖 Vulkan 后端
            List.of("streamline-integration"),       // 可选：SL 集成增强
            "基于 PipelineNode 架构的可配置光影系统，支持 YAML/Lua/Binary 配置",
            "Renderium Team",
            false                                    // 默认关闭！需要用户手动开启
    );

    // ==================== 子组件 ====================

    /** 模组检测引擎 */
    private volatile ModDetectionEngine modDetector;

    /** 管线节点注册表 */
    private volatile PipelineNodeRegistry nodeRegistry;

    /** 光影包解析器 */
    private volatile RGBPackParser packParser;

    /** 着色器 Pass 注入器 */
    private volatile ShaderPassInjector passInjector;

    /** 效果管线管理 */
    private volatile ShaderEffectPipeline effectPipeline;

    // ==================== 状态字段 ====================

    private volatile boolean enabled = false;
    private volatile String activePackName = null;
    private volatile boolean packLoaded = false;

    /** 兼容性检查结果缓存 */
    private volatile DefaultModDetectionEngine.CompatibilityResult compatibilityResult = null;

    /** 兼容性检查是否通过 */
    private volatile boolean compatibilityPassed = false;

    // ==================== RenderiumModule 实现 ====================

    @Override
    public ModuleMetadata getMetadata() { return METADATA; }

    /**
     * 检查模块是否可以加载
     * <p>
     * 检查条件：
     * 1. 必须是狂暴模式
     * 2. 必须通过兼容性检查（无阻塞模组）
     *
     * 【方法参数】
     * @param context ModuleContext - 模块上下文
     *
     * 【返回值】
     * @return boolean - 是否可以加载
     */
    @Override
    public boolean canLoad(ModuleContext context) {
        // 条件1: 仅允许在狂暴模式加载
        if (!context.isAggressiveMode()) {
            LOGGER.info("RGBShaderModule: 跳过（非狂暴模式）");
            return false;
        }

        // 条件2: 执行兼容性检查
        performCompatibilityCheck();

        if (!compatibilityPassed) {
            LOGGER.warning("RGBShaderModule: 跳过（兼容性检查未通过）");
            if (compatibilityResult != null) {
                LOGGER.warning(compatibilityResult.getDiagnosticMessage());
            }
            return false;
        }

        return true;
    }

    @Override
    public boolean initialize(ModuleContext context) {
        LOGGER.info("═══ 初始化 RGBShaderModule v2.0 ═══");

        try {
            // 1. 初始化模组检测引擎
            this.modDetector = new DefaultModDetectionEngine();
            LOGGER.info("✓ 模组检测引擎已初始化");

            // 2. 初始化管线节点注册表
            this.nodeRegistry = PipelineNodeRegistry.getInstance();
            this.nodeRegistry.registerBuiltinNodes();
            LOGGER.info("✓ 管线节点注册表已初始化 (" +
                    nodeRegistry.getTotalCount() + " 个内置节点)");

            // 3. 注册到 MCRenderBridge 生命周期管理器
            registerLifecycleHooks();

            // 4. 初始化子组件
            this.packParser = new RGBPackParser(context);
            this.passInjector = new ShaderPassInjector(context);
            this.effectPipeline = new ShaderEffectPipeline(context);

            LOGGER.info("✓ RGBShaderModule v2.0 初始化完成");
            return true;

        } catch (Exception e) {
            LOGGER.severe("RGBShaderModule 初始化失败: " + e.getMessage());
            cleanup();
            return false;
        }
    }

    @Override
    public boolean enable() {
        if (enabled) return true;

        try {
            // 启用 Pass 注入器
            passInjector.enable();

            // 初始化所有启用的渲染节点
            initializeEnabledNodes();

            this.enabled = true;

            LOGGER.info("✓ RGBShaderModule ENABLED - 光影注入就绪");
            logSystemStatus();
            return true;

        } catch (Exception e) {
            LOGGER.severe("RGBShaderModule 启用失败: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void disable() {
        if (!enabled) return;

        try {
            passInjector.disable();
            effectPipeline.disable();

            this.enabled = false;
            this.packLoaded = false;

            LOGGER.info("RGBShaderModule DISABLED");

        } catch (Exception e) {
            LOGGER.severe("禁用 RGBShaderModule 时出错: " + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        disable();

        // 释放所有节点资源
        if (nodeRegistry != null) {
            nodeRegistry.disposeAll();
        }

        // 使用 safeDispose 统一释放资源
        safeDispose(packParser);   packParser = null;
        safeDispose(passInjector); passInjector = null;
        safeDispose(effectPipeline); effectPipeline = null;

        modDetector = null;
        nodeRegistry = null;

        LOGGER.info("RGBShaderModule disposed");
    }

    // ==================== 公共 API ====================

    /**
     * 加载指定的 RGB 光影包
     *
     * 【方法参数】
     * @param packPath String - 光影包路径（目录或 .rgb 文件）
     *
     * 【返回值】
     * @return boolean - 是否成功加载
     */
    public boolean loadPack(String packPath) {
        if (!enabled || packParser == null) {
            LOGGER.warning("无法加载光影包: 模块未启用");
            return false;
        }

        try {
            LOGGER.info("加载 RGB 光影包: " + packPath);

            // 1. 解析包清单
            RGBPackParser.ParsedPackResult parseResult = packParser.parse(packPath);
            if (parseResult == null) {
                LOGGER.severe("光影包清单解析失败");
                return false;
            }

            // 提取 manifest（record 自动生成 accessor）
            RGBPackManifest manifest = parseResult.manifest();
            if (manifest == null) {
                LOGGER.severe("光影包 manifest 为空");
                return false;
            }

            // 2. 应用节点配置（从 nodes.yaml）
            applyNodeConfiguration(manifest);

            // 3. 编译/加载着色器（从解析结果中获取）
            // 注意：需要将 RGBPackParser.CompiledShader 转换为外部 CompiledShader 类型
            List<RGBPackParser.CompiledShader> rawShaders = parseResult.compiledShaders();
            List<CompiledShader> shaders = convertShaders(rawShaders);

            // 4. 创建效果链
            effectPipeline.buildEffectChain(manifest, shaders);

            // 5. 注册到 Pass 注入器
            passInjector.registerEffects(effectPipeline.getEffects());

            this.activePackName = manifest.getName();
            this.packLoaded = true;

            LOGGER.info("✓ 光影包加载成功: " + manifest.getName());
            logPackInfo(manifest);

            return true;

        } catch (Exception e) {
            LOGGER.severe("光影包加载错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 卸载当前光影包并重置节点状态
     */
    public void unloadPack() {
        if (!packLoaded) return;

        effectPipeline.clear();
        passInjector.clearEffects();

        // 重置所有节点到默认状态
        if (nodeRegistry != null) {
            nodeRegistry.resetToDefaults();
        }

        this.activePackName = null;
        this.packLoaded = false;

        LOGGER.info("光影包已卸载，节点状态已重置");
    }

    // ==================== 查询 API ====================

    /** 是否有活跃的光影包 */
    public boolean isPackLoaded() { return packLoaded; }

    /** 当前光影包名称 */
    public String getActivePackName() { return activePackName; }

    /** 获取 Pass 注入器（供 Mixin 使用） */
    public ShaderPassInjector getPassInjector() { return passInjector; }

    /** 获取效果管线 */
    public ShaderEffectPipeline getEffectPipeline() { return effectPipeline; }

    /** 获取管线节点注册表 */
    public PipelineNodeRegistry getNodeRegistry() { return nodeRegistry; }

    /** 获取兼容性检查结果 */
    public DefaultModDetectionEngine.CompatibilityResult getCompatibilityResult() {
        return compatibilityResult;
    }

    /** 兼容性是否通过 */
    public boolean isCompatibilityPassed() { return compatibilityPassed; }

    // ==================== 内部方法 ====================

    /**
     * 执行兼容性检查
     */
    private void performCompatibilityCheck() {
        if (modDetector instanceof DefaultModDetectionEngine engine) {
            compatibilityResult = engine.checkShaderSystemCompatibility();
            compatibilityPassed = compatibilityResult.isShaderSystemAllowed();
        } else {
            // 回退：使用简单检查
            compatibilityPassed = true;
            LOGGER.warning("使用非标准 ModDetectionEngine，跳过详细兼容性检查");
        }
    }

    /**
     * 初始化所有启用的渲染节点
     * <p>
     * 通过 MCRenderBridge 获取帧数据（零 Mixin 依赖），
     * 如果桥接器数据尚未就绪，使用安全默认值。
     */
    private void initializeEnabledNodes() {
        if (nodeRegistry == null) return;

        // 通过 MCRenderBridge 获取帧数据（而非直接访问 Mixin 注入对象）
        FrameDataSnapshot frameData = MCRenderBridge.getCurrentFrameData();

        int successCount = 0;
        int failCount = 0;

        for (String nodeId : nodeRegistry.getExecutionOrder()) {
            PipelineNode node = nodeRegistry.getNode(nodeId);
            if (node != null && !node.isInitialized()) {
                // TODO: 从 FrameDataSnapshot 构建 RenderContext 并初始化节点
                // RenderContext ctx = new RenderContext.Builder()
                //     .matrixDataFrom(frameData)
                //     .build();
                // if (node.initialize(ctx)) { ... }
            }
        }

        if (failCount > 0) {
            LOGGER.warning(String.format("节点初始化: %d 成功, %d 失败", successCount, failCount));
        }
    }

    /**
     * 应用光影包的节点配置
     */
    private void applyNodeConfiguration(RGBPackManifest manifest) {
        // TODO: 从 manifest 中读取 nodes.yaml 配置
        // 并调用 nodeRegistry.batchSetStates() 应用
        LOGGER.fine("应用光影包节点配置: " + manifest.getName());
    }

    /**
     * 将 RGBPackParser 内部 CompiledShader 转换为外部 CompiledShader 类型
     *
     * @param rawShaders RGBPackParser.CompiledShader 列表
     * @return 外部 CompiledShader 列表
     */
    private List<CompiledShader> convertShaders(List<RGBPackParser.CompiledShader> rawShaders) {
        if (rawShaders == null || rawShaders.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        List<CompiledShader> result = new java.util.ArrayList<>(rawShaders.size());
        for (RGBPackParser.CompiledShader raw : rawShaders) {
            try {
                // 将 RGBPackParser.ShaderStage 转换为 CompiledShader.ShaderType
                String stageName = raw.stage().name();  // VERTEX/FRAGMENT/COMPUTE
                CompiledShader.ShaderType shaderType = mapShaderStage(stageName);

                CompiledShader shader = new CompiledShader(
                    raw.name(),
                    shaderType,
                    raw.spirvData(),  // spirvData 而不是 bytecode
                    0L               // programHandle（稍后由 GPU 创建）
                );
                result.add(shader);
            } catch (Exception e) {
                LOGGER.warning("转换着色器失败: " + raw.name() + " - " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * 将 RGBPackParser 着色器阶段映射为 CompiledShader.ShaderType
     */
    private static CompiledShader.ShaderType mapShaderStage(String stageName) {
        if (stageName == null) return CompiledShader.ShaderType.FRAGMENT;
        return switch (stageName.toUpperCase()) {
            case "VERTEX" -> CompiledShader.ShaderType.VERTEX;
            case "FRAGMENT" -> CompiledShader.ShaderType.FRAGMENT;
            case "GEOMETRY" -> CompiledShader.ShaderType.GEOMETRY;
            case "COMPUTE" -> CompiledShader.ShaderType.COMPUTE;
            default -> CompiledShader.ShaderType.FRAGMENT;
        };
    }

    private void cleanup() {
        safeDispose(modDetector);      modDetector = null;
        safeDispose(packParser);       packParser = null;
        safeDispose(passInjector);     passInjector = null;
        safeDispose(effectPipeline);   effectPipeline = null;
    }

    private void safeDispose(Object obj) {
        if (obj == null) return;
        try {
            if (obj instanceof AutoCloseable) {
                ((AutoCloseable) obj).close();
            } else if (obj instanceof PipelineNode) {
                ((PipelineNode) obj).dispose();
            }
        } catch (Exception ignored) {}
    }

    /**
     * 注册到 MCRenderBridge 生命周期管理器
     * <p>
     * 监听投影矩阵和视图矩阵更新事件，
     * 确保光影系统始终使用最新的渲染状态。
     * 通过 MCRenderBridge 获取数据，零 Mixin 依赖。
     */
    private void registerLifecycleHooks() {
        RenderiumLifecycleManager lifecycleManager = MCRenderBridge.getLifecycleManager();
        if (lifecycleManager == null) {
            lifecycleManager = RenderiumLifecycleManager.getInstance();
            MCRenderBridge.setLifecycleManager(lifecycleManager);
        }

        // 注册 GameRenderer 钩子：监听投影矩阵更新
        lifecycleManager.addGameRendererListener(new RenderiumLifecycleManager.GameRendererHooks() {
            @Override
            public void onAfterSetProjection(ProjectionContext ctx) {
                // 投影矩阵已更新到 FrameDataSnapshot
                // 光影节点通过 MCRenderBridge.getCurrentFrameData() 自动获取
                LOGGER.fine("RGBShaderModule: 投影矩阵已同步");
            }
        });

        // 注册 LevelRenderer 钩子：监听视图矩阵更新
        lifecycleManager.addLevelRendererListener(new RenderiumLifecycleManager.LevelRendererHooks() {
            @Override
            public void onAfterModelViewSet(MatrixContext ctx) {
                // 视图矩阵已更新到 FrameDataSnapshot
                LOGGER.fine("RGBShaderModule: 视图矩阵已同步");
            }
        });

        LOGGER.info("✓ 生命周期钩子已注册 (通过 MCRenderBridge)");
    }

    private void logSystemStatus() {
        LOGGER.info("╔══════════════════════════════════════╗");
        LOGGER.info("║   RGB Shader System Status          ║");
        LOGGER.info("╠══════════════════════════════════════╣");
        LOGGER.info(String.format("║ Version: %-29s ║", METADATA.version()));
        LOGGER.info(String.format("║ Compatibility: %-23s ║",
                compatibilityPassed ? "✓ PASSED" : "✗ FAILED"));
        
        if (nodeRegistry != null) {
            LOGGER.info(String.format("║ Total Nodes: %-26d ║", nodeRegistry.getTotalCount()));
            LOGGER.info(String.format("║ Enabled Nodes: %-25d ║", nodeRegistry.getEnabledCount()));
        }
        
        if (packLoaded) {
            LOGGER.info(String.format("║ Active Pack: %-27s ║", activePackName));
        }
        LOGGER.info("╚══════════════════════════════════════╝");
    }

    private void logPackInfo(RGBPackManifest manifest) {
        LOGGER.info("╔══════════════════════════════════════╗");
        LOGGER.info("║   RGB Shader Pack Loaded             ║");
        LOGGER.info("╠══════════════════════════════════════╣");
        LOGGER.info(String.format("║ Name: %-33s ║", manifest.getName()));
        LOGGER.info(String.format("║ Author: %-31s ║", manifest.getAuthor()));
        LOGGER.info(String.format("║ Version: %-30s ║", manifest.getVersion()));
        LOGGER.info(String.format("║ Effects: %-29d ║", manifest.getEffectCount()));
        LOGGER.info("╚══════════════════════════════════════╝");
    }

    @Override
    public String getStatusString() {
        String state = enabled ? "● ACTIVE" : "○ INACTIVE";
        String compat = compatibilityPassed ? "✓" : "✗";
        String pack = packLoaded ? " [" + activePackName + "]" : "";
        return String.format("%s [%s]%s compat=%s v%s",
                METADATA.name(), state, pack, compat, METADATA.version());
    }
}
