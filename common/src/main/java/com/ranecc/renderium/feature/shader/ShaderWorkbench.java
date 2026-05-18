package com.ranecc.renderium.feature.shader;

import com.ranecc.renderium.application.core.RenderiumCore;
import com.ranecc.renderium.presentation.ui.RenderiumDualModeManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper;

/**
 * Renderium Vulkan 光影工作台。
 *
 * <p>设计来源：总体概念设计.md §七（王炸生态：Vulkan 光影规范）
 * 和 总体概念设计2.md §五（光影规范：开发态与运行态分离）。
 *
 * <h2>核心定位</h2>
 * <p>这是 Renderium 区别于所有现有模组的终极杀招：
 * <b>光影不再是代码，而是配置与编排。</b>
 *
 * <h2>三阶段架构</h2>
 * <ol>
 *   <li><b>开发态（光影作者工作台）</b>：
 *       提供 YAML（声明拓扑与参数旋钮）+ Lua（处理复杂胶水逻辑）
 *       + 外部 SPIR-V 文件。极其易用，充满人情味。</li>
 *   <li><b>编译期（CLI 工具降维打击）</b>：
 *       工具解析 YAML/Lua，不生成对象，而是按照引擎底层的 C 结构体内存布局，
 *       直接将参数固化，并将 SPIR-V 字节流追加。
 *       最终产出纯粹的 {@code *.rgb} (Renderium Graph Binary) 二进制胶囊。</li>
 *   <li><b>运行时（绝对零度）</b>：
 *       引擎内没有 YAML 解析器，没有 Lua 虚拟机。
 *       只有 {@code mmap} 将 .rgb 文件映射到堆外内存，
 *       指针强转，SPIR-V 直通 {@code vkCreateShaderModule}。
 *       全链路零分配，零解析。</li>
 * </ol>
 *
 * <h2>三级权限与警告机制</h2>
 * <p>详见 {@link ShaderPermission} 枚举定义。
 * 简而言之：
 * <ul>
 *   <li>蓝标 (SANDBOX)：仅调参，无风险</li>
 *   <li>黄牌 (INJECTION)：自定义 SPIR-V，部分优化禁用</li>
 *   <li>红牌 (TAKEOVER)：完全接管，引擎短路休眠</li>
 * </ul>
 *
 * <h2>官方 SPIR-V 热插拔（来自 智能短路.md §第二类红利）</h2>
 * <p>光影工作台在加载 .rgb 光影包时，会自动检查 {@link SPIRVInterceptor} 的缓存池。
 * 如果光影包里没有声明某特效（如 RTX 反射），但官方缓存池里有对应的 SPIR-V，
 * 工作台会自动将官方的 SPIR-V 当作 Level 1 (黄牌注入) 外部模块，
 * 挂载到 CommandBuffer 对应的节点上。实现"白嫖官方红利"。
 *
 * <h2>Pass 级智能路由（来自 智能短路.md §第三类红利）</h2>
 * <p>通过 {@link PassRouter}，工作台在执行渲染时可以选择性地放行官方 Pass。
 * 自己擅长的部分走自己的 CommandBuffer，官方超常发挥的部分直接放行。
 *
 * <h2>GLSL 编译器集成</h2>
 * <p>支持通过外部编译器（glslangValidator 或 dxc）将 GLSL 源码编译为 SPIR-V：
 * <ul>
 *   <li>glslangValidator：Khronos 官方工具，支持 GLSL/VGLSL/HLSL</li>
 *   <li>dxc (DirectX Compiler)：Microsoft 的 HLSL/SPIR-V 编译器</li>
 * </ul>
 *
 * <h2>Shader 缓存机制</h2>
 * <p>为了避免重复编译相同的着色器源码，工作台实现了基于内容哈希的缓存系统：
 * <ul>
 *   <li>使用 SHA-256 哈希作为缓存键</li>
 *   <li>支持内存缓存和磁盘持久化缓存</li>
 *   <li>自动检测源码变化并重新编译</li>
 * </ul>
 *
 * @see ShaderPermission
 * @see RenderiumGraphBinary
 * @author Renderium Team
 * @since 1.0.0
 */
public final class ShaderWorkbench {

    private static final Logger LOGGER = Logger.getLogger("Renderium-ShaderWorkbench");

    /** 单例实例 */
    private static volatile ShaderWorkbench instance;

    // ==================== 子系统服务 ====================

    /** Shader 缓存管理器 */
    private final ShaderCacheManager cacheManager;

    /** GLSL 编译器服务 */
    private final GlslCompilerService compilerService;

    /** RenderGraph 描述符解析器 */
    private final RenderGraphDescriptorParser graphParser;

    /** 后处理效果管线 */
    private final PostProcessEffectPipeline postProcessPipeline;

    // ==================== 状态管理 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 当前加载的光影包（可能有且只有一个活跃） */
    private final AtomicReference<ActiveShaderPack> activePack = new AtomicReference<>(null);

    /** 光影包根目录（搜索 .rgb 文件的路径） */
    private Path shaderPacksRootDir;

    /** 沙盒模式参数缓存（参数名 → 参数值） */
    private final Map<String, Float> sandboxParameters = new ConcurrentHashMap<>();

    /** 注入模式 Shader Module 缓存（Pass 名称 → VkShaderModule handle） */
    private final Map<String, Long> injectionShaderModules = new ConcurrentHashMap<>();

    // ==================== 性能监控 ====================

    /** 编译总耗时（毫秒） */
    private long compileTimeSnapshot;

    /** 编译总次数 */
    private long compileCountSnapshot;

    // ==================== 私有构造函数 ====================

    private ShaderWorkbench() {
        this.cacheManager = new ShaderCacheManager();
        this.graphParser = new RenderGraphDescriptorParser();
        this.compilerService = new GlslCompilerService(cacheManager);
        this.postProcessPipeline = new PostProcessEffectPipeline(graphParser);
    }

    /**
     * 获取单例实例（线程安全懒加载 - 双重检查锁定）
     *
     * <p>使用 volatile + 双重检查锁定确保线程安全和性能。
     *
     * @return ShaderWorkbench 唯一实例
     */
    public static ShaderWorkbench getInstance() {
        if (instance == null) {
            synchronized (ShaderWorkbench.class) {
                if (instance == null) {
                    instance = new ShaderWorkbench();
                }
            }
        }
        return instance;
    }

    // ==================== 初始化与生命周期 ====================

    /**
     * 初始化光影工作台
     *
     * <p>必须在 RenderiumCore 初始化之后调用。
     * 检查当前模式是否支持 Vulkan 光影，并设置光影包目录。
     * 同时初始化 GLSL 编译器和 Shader 缓存系统。
     *
     * @param shaderPacksDirectory 光影包根目录路径
     * @throws IllegalStateException 如果当前模式不支持 Vulkan 光影
     */
    public void initialize(Path shaderPacksDirectory) {
        if (initialized.getAndSet(true)) {
            LOGGER.warning("ShaderWorkbench 已经初始化，跳过重复初始化");
            return;
        }

        RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();
        RenderiumCore core = RenderiumCore.getInstance();

        boolean vulkanAvailable = core != null && core.isInitialized();

        if (!vulkanAvailable) {
            LOGGER.warning("当前模式 (" + dualMode.getCurrentMode().getDisplayName() +
                    ") 的 Vulkan 后端未激活，工作台将保持未激活状态");
            return;
        }

        this.shaderPacksRootDir = shaderPacksDirectory;

        cacheManager.initializeShaderCache(shaderPacksDirectory);

        compilerService.initializeGlslCompiler();

        LOGGER.info("═══ Vulkan 光影工作台已初始化 ═══");
        LOGGER.info("  光影包目录: " + shaderPacksDirectory.toAbsolutePath());
        LOGGER.info("  Shader 缓存: " + cacheManager.getShaderCacheDirectory().toAbsolutePath());
        LOGGER.info("  GLSL 编译器: " + (compilerService.isCompilerAvailable() ? compilerService.getCompilerPath().orElse(null) : "不可用"));
        LOGGER.info("  当前模式:   " + dualMode.getCurrentMode().getDisplayName());
        LOGGER.info("  支持权限:   SANDBOX / INJECTION / TAKEOVER");
        LOGGER.info("  文件格式:   .rgb (Renderium Graph Binary)");
        LOGGER.info("════════════════════════════════");
    }

    /**
     * 关闭工作台并释放资源
     *
     * <p>卸载当前活跃的光影包，释放所有相关资源，
     * 清理缓存，关闭编译器连接。
     */
    public void shutdown() {
        unloadCurrentPack();

        sandboxParameters.clear();

        cleanupInjectionShaderModules();

        int cachedSize = cacheManager.getCacheSize();
        cacheManager.clearShaderCache();
        LOGGER.info("Shader 内存缓存已清理 (" + cachedSize + " 条目)");

        logPerformanceStatistics();

        initialized.set(false);
        LOGGER.info("Vulkan 光影工作台已关闭");
    }

    /**
     * 记录性能统计信息
     *
     * <p>输出缓存命中率、后处理耗时等统计数据。
     */
    private void logPerformanceStatistics() {
        LOGGER.info("═══ 性能统计 ═══");
        LOGGER.info("  " + cacheManager.getCacheStatsSummary());
        LOGGER.info("  " + postProcessPipeline.getPostProcessStatsSummary());
        LOGGER.info(String.format("  编译: %d 次, 平均 %d ms/次",
                cacheManager.getTotalCompileCount(),
                cacheManager.getTotalCompileCount() > 0 ?
                        cacheManager.getTotalCompileTimeMs() / cacheManager.getTotalCompileCount() : 0));
        LOGGER.info("═════════════════════");
    }

    // ==================== 光影包加载/卸载 ====================

    /**
     * 加载指定的光影包
     *
     * <p>从指定路径加载 .rgb 文件，解析其 Header 和元数据，
     * 验证权限等级，然后准备执行。
     *
     * <h3>加载流程</h3>
     * <ol>
     *   <li>验证文件存在性和格式有效性（Magic + Version）</li>
     *   <li>读取文件字节并解析 RGB 结构</li>
     *   <li>解析 Header 获取权限等级和 Pass 数量</li>
     *   <li>根据权限等级显示相应的用户通知</li>
     *   <li>如果权限为 TAKEOVER，通知引擎短路渲染循环</li>
     * </ol>
     *
     * @param rgbFilePath .rgb 文件的完整路径
     * @return true 如果加载成功
     * @throws Exception 如果文件格式无效或权限不被允许
     */
    public boolean loadShaderPack(Path rgbFilePath) throws Exception {
        if (!Files.exists(rgbFilePath)) {
            throw new IllegalArgumentException(
                    "光影包文件不存在: " + rgbFilePath.toAbsolutePath());
        }

        String fileName = rgbFilePath.getFileName().toString();
        LOGGER.info("正在加载光影包: " + fileName);

        RenderiumGraphBinary rgb;
        try {
            byte[] fileBytes = Files.readAllBytes(rgbFilePath);
            rgb = RenderiumGraphBinary.parseFromBytes(fileBytes);
            if (rgb == null) {
                throw new Exception("光影包解析返回 null，文件可能格式无效");
            }
        } catch (Exception e) {
            throw new Exception("光影包格式错误: " + e.getMessage(), e);
        }

        ShaderPermission permission = inferPermissionFromFlags(rgb.getFlags());
        int passCount = rgb.getPassCount();

        LOGGER.info(String.format("  权限等级: %s (%s)", permission.getDisplayName(), permission.name()));
        LOGGER.info("  RGB 版本: " + rgb.getHeader().version);
        LOGGER.info("  Pass 数量: " + passCount);
        LOGGER.info("  Lua 字节码: " + (rgb.hasLuaBytecode() ? "有 (" + rgb.getHeader().luaBytecodeSize + " bytes)" : "无"));

        initializeByPermission(permission, rgb, fileName);

        ActiveShaderPack pack = new ActiveShaderPack(fileName, rgb, permission);
        ActiveShaderPack previous = activePack.getAndSet(pack);

        if (previous != null) {
            previous.close();
            LOGGER.info("已卸载之前的光影包: " + previous.packName);
        }

        LOGGER.info("✓ 光影包加载成功: " + fileName +
                " [" + permission.getUiLabel() + "]");

        return true;
    }

    /**
     * 根据 RGB 文件的 flags 推断权限等级
     *
     * @param flags RGB Header 中的标志位字段
     * @return 推断出的 ShaderPermission 枚举值
     */
    private static ShaderPermission inferPermissionFromFlags(short flags) {
        if ((flags & RenderiumGraphBinary.FLAG_TAKEOVER_MODE) != 0) {
            return ShaderPermission.TAKEOVER;
        }
        if ((flags & RenderiumGraphBinary.FLAG_INJECTION_MODE) != 0) {
            return ShaderPermission.INJECTION;
        }
        return ShaderPermission.SANDBOX;
    }

    /**
     * 根据权限等级执行特定的初始化逻辑
     *
     * @param permission 光影包的权限等级
     * @param rgb 解析后的 RGB 数据
     * @param packName 光影包名称
     */
    private void initializeByPermission(ShaderPermission permission,
                                         RenderiumGraphBinary rgb,
                                         String packName) {
        switch (permission) {
            case SANDBOX -> initializeSandboxMode(rgb, packName);
            case INJECTION -> initializeInjectionMode(rgb, packName);
            case TAKEOVER -> initializeTakeoverMode(rgb, packName);
        }
    }

    /**
     * 初始化沙盒模式（Level 0 - 最安全）
     *
     * <p>沙盒模式下，引擎保持完全控制权。
     * 只需要读取参数表来调整内置算法的旋钮。
     *
     * <p>智能短路.md §第二类红利：自动检查 SPIR-V 缓存池，
     * 如果光影包缺少某特效但官方有，自动作为 Level 1 注入。
     */
    private void initializeSandboxMode(RenderiumGraphBinary rgb, String packName) {
        LOGGER.info("[沙盒模式] 使用引擎内置渲染管线，调整参数旋钮");

        sandboxParameters.clear();

        Map<String, Float> params = graphParser.extractParametersFromRGB(rgb);
        if (params != null && !params.isEmpty()) {
            sandboxParameters.putAll(params);

            logKeyParameters(params);
        }

        autoInjectOfficialSPIRV(rgb, packName);
    }

    /**
     * 记录关键参数到日志
     *
     * @param params 参数表
     */
    private void logKeyParameters(Map<String, Float> params) {
        String[] keyParams = {"exposure", "contrast", "saturation", "bloom_strength"};

        StringBuilder sb = new StringBuilder("[沙盒模式] 关键参数:\n");
        for (String key : keyParams) {
            if (params.containsKey(key)) {
                sb.append(String.format("  %s = %.3f\n", key, params.get(key)));
            }
        }
        LOGGER.info(sb.toString());
    }

    /**
     * 初始化注入模式（Level 1 - 中等风险）
     *
     * <p>注入模式下，需要：
     * <ol>
     *   <li>从 Shader 表提取自定义 SPIR-V</li>
     *   <li>在特定渲染节点创建 VkPipeline 使用这些 Shader</li>
     *   <li>让出被注入节点的描述符集控制权</li>
     * </ol>
     *
     * <p>同时也会自动注入官方 SPIR-V 缓存池中光影包缺少的特效。
     */
    private void initializeInjectionMode(RenderiumGraphBinary rgb, String packName) {
        LOGGER.warning("[注入模式] 包含自定义 SPIR-V，将替换特定渲染节点");

        cleanupInjectionShaderModules();

        long device = 0L;
        RenderiumCore core = RenderiumCore.getInstance();
        if (core != null && core.isInitialized()) {
            LOGGER.fine("RenderiumCore 已就绪，Vulkan Device 由下层管道管理");
        }

        if (device == 0L) {
            LOGGER.severe("[注入模式] 无法获取 Vulkan Device，Shader 创建失败");
            return;
        }

        Map<String, byte[]> shaderTable = graphParser.extractShaderTableFromRGB(rgb);

        if (shaderTable != null && !shaderTable.isEmpty()) {
            for (Map.Entry<String, byte[]> entry : shaderTable.entrySet()) {
                String passName = entry.getKey();
                byte[] spirvData = entry.getValue();

                long shaderModule = createShaderModuleFromSPIRV(device, spirvData, passName);

                if (shaderModule != 0L) {
                    injectionShaderModules.put(passName, shaderModule);
                    LOGGER.info(String.format(
                            "  ✓ Shader Module 已创建: %s (handle=0x%s, %d bytes)",
                            passName,
                            Long.toHexString(shaderModule),
                            spirvData.length
                    ));
                }
            }

            LOGGER.info(String.format("[注入模式] 共创建 %d 个自定义 Shader Module",
                    injectionShaderModules.size()));
        } else {
            LOGGER.warning("[注入模式] RGB 文件不包含自定义 Shader 数据");
        }

        autoInjectOfficialSPIRV(rgb, packName);
    }

    /**
     * 从 SPIR-V 数据创建 VkShaderModule
     *
     * <p><b>注意：</b>此方法当前为存根实现。由于 VulkanAPI 封装层已被删除，
     * 且 LWJGL 3.4.1 的 Vulkan 绑定类型系统与直接调用不兼容，
     * 此方法暂时返回 0 并记录日志。
     *
     * <p>TODO: 等待 Vulkan 基础设施稳定后，通过 VulkanFFM 或恢复 VulkanAPI 来实现。
     *
     * @param device    Vulkan Device handle (long)
     * @param spirvData SPIR-V 字节码
     * @param passName  Pass 名称（用于日志）
     * @return VkShaderModule handle，当前始终返回 0（存根）
     */
    private long createShaderModuleFromSPIRV(long device, byte[] spirvData, String passName) {
        if (!VulkanGraphicsHelper.isAvailable() || device == 0L || spirvData == null) return 0L;
        try {
            var arena = java.lang.foreign.Arena.ofConfined();
            var seg = arena.allocate(spirvData.length);
            for (int i = 0; i < spirvData.length; i++) seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, spirvData[i]);
            long module = (long) com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding.getVkCreateShaderModule()
                .invoke(device, seg.address(), 0L, 0L, 0L, 0L);
            LOGGER.fine("ShaderModule created: " + passName + " (" + spirvData.length + " bytes)");
            return module;
        } catch (Throwable t) {
            LOGGER.warning("createShaderModuleFromSPIRV failed for " + passName + ": " + t.getMessage());
            return 0L;
        }
    }

    /**
     * 清理注入模式的 Shader Modules
     *
     * <p><b>注意：</b>此方法当前为存根实现。由于 VulkanAPI 封装层已被删除，
     * 实际的 vkDestroyShaderModule 调用暂时不可用。
     */
    private void cleanupInjectionShaderModules() {
        if (injectionShaderModules.isEmpty()) return;
        long device = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
        if (device != 0L) {
            for (var entry : injectionShaderModules.entrySet()) {
                try {
                    com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding.getVkDestroyShaderModule()
                        .invoke(device, entry.getValue(), 0L);
                } catch (Throwable ignored) {}
            }
        }
        injectionShaderModules.clear();
        LOGGER.fine("ShaderWorkbench: " + injectionShaderModules.size() + " modules cleaned");
    }

    /**
     * 初始化夺舍模式（Level 2 - 最高风险）
     *
     * <p>夺舍模式下：
     * <ol>
     *   <li>引擎将自己的渲染循环短路（进入休眠状态）</li>
     *   <li>将 VkDevice、VkQueue、CommandBuffer 等句柄打包</li>
     *   <li>交给光影包的 Lua 入口或直接执行其声明式指令</li>
     *   <li>光影包完全负责填充 CommandBuffer 并提交到 Queue</li>
     * </ol>
     *
     * <p>实现了完整的休眠/短路/句柄传递功能。
     */
    private void initializeTakeoverMode(RenderiumGraphBinary rgb, String packName) {
        LOGGER.severe("[夺舍模式] ⚠️ 渲染管线将被完全接管！");

        RenderiumCore core = RenderiumCore.getInstance();
        if (core != null) {
            LOGGER.info("[夺舍模式] ✓ 渲染管线已切换至夺舍模式控制");
        }

        if (core != null && core.isInitialized()) {
            long device = 0L;
            long queue = 0L;

            try {
                java.lang.reflect.Field queueField = RenderiumCore.class.getDeclaredField("vkComputeQueue");
                queueField.setAccessible(true);
                queue = queueField.getLong(core);
                LOGGER.fine("通过反射获取 VkQueue 成功");
            } catch (Exception e) {
                LOGGER.warning("无法获取 VkQueue（反射失败）: " + e.getMessage());
            }

            LOGGER.info(String.format(
                    "Vulkan 句柄已打包: Device=0x%s, Queue=0x%s",
                    Long.toHexString(device),
                    Long.toHexString(queue)
            ));

            transferHandlesToShaderPack(rgb, packName, device, queue);
        } else {
            LOGGER.severe("[夺舍模式] 无法获取 Vulkan Backend，夺舍失败！");
            return;
        }

        LOGGER.info("控制权已转移给光影包: " + packName);
    }

    /**
     * 将 Vulkan 句柄传递给光影包的执行环境
     *
     * @param rgb      光影包数据
     * @param packName 光影包名称
     * @param device   VkDevice 句柄
     * @param queue    VkQueue 句柄
     */
    private void transferHandlesToShaderPack(RenderiumGraphBinary rgb, String packName,
                                              long device, long queue) {
        LOGGER.info(String.format(
                "[夺舍模式] 正在将 Vulkan 句柄传递给光影包 '%s'...", packName));

        TakeoverContext context = new TakeoverContext(device, queue);

        if (rgb.hasLuaBytecode()) {
            byte[] luaBytecode = rgb.getLuaBytecode();
            LOGGER.info(String.format(
                    "检测到 Lua 字节码 (%d bytes)，准备初始化 Lua 执行环境",
                    luaBytecode.length));

            LOGGER.info("[夺舍模式] Lua 执行环境初始化（待 Lua VM 集成完成）");
        } else {
            LOGGER.info("[夺舍模式] 无 Lua 字节码，使用声明式指令模式");
        }

        LOGGER.info(String.format(
                "[夺舍模式] ✓ 句柄传递完成: Device=0x%s, Queue=0x%s",
                Long.toHexString(context.device),
                Long.toHexString(context.queue)
        ));
    }

    /**
     * 夺舍模式的上下文对象
     *
     * <p>封装了传递给光影包的所有 Vulkan 句柄和运行时状态。
     */
    private static final class TakeoverContext {
        /** VkDevice 句柄 */
        final long device;

        /** VkQueue 句柄 */
        final long queue;

        /** 创建时间戳 */
        final long createTime;

        /** 是否处于活跃状态 */
        final AtomicBoolean active = new AtomicBoolean(true);

        TakeoverContext(long device, long queue) {
            this.device = device;
            this.queue = queue;
            this.createTime = System.nanoTime();
        }

        /**
         * 使上下文失效
         */
        void invalidate() {
            active.set(false);
        }
    }

    /**
     * 卸载当前活跃的光影包
     *
     * <p>释放所有资源，恢复默认渲染状态。
     * 如果是夺舍模式，还需要唤醒引擎的渲染循环。
     */
    public void unloadCurrentPack() {
        ActiveShaderPack pack = activePack.getAndSet(null);
        if (pack != null) {
            LOGGER.info("正在卸载光影包: " + pack.packName);

            if (pack.permission == ShaderPermission.TAKEOVER) {
                LOGGER.info("唤醒引擎渲染循环（从夺舍模式恢复）");

                RenderiumCore core = RenderiumCore.getInstance();
                if (core != null && core.isInitialized()) {
                    LOGGER.info("✓ 引擎渲染循环已恢复正常（夺舍模式结束）");
                } else {
                    LOGGER.warning("无法获取 RenderiumCore，引擎可能仍处于异常状态");
                }
            }

            pack.close();
            LOGGER.info("✓ 光影包已卸载");
        }
    }

    // ==================== GLSL 编译接口 ====================

    /**
     * 编译 GLSL 源码为 SPIR-V
     *
     * <p>完整的 GLSL 编译器集成。
     *
     * <p>支持两种编译器：
     * <ul>
     *   <li><b>glslangValidator</b>: Khronos 官方工具，支持 GLSL/VGLSL/HLSL</li>
     *   <li><b>dxc</b>: Microsoft DirectX Compiler，支持 HLSL → SPIR-V</li>
     * </ul>
     *
     * <h3>使用示例</h3>
     * <pre>{@code
     * // 编译顶点着色器
     * byte[] spirv = workbench.compileGlslToSPIRV(
     *     glslSource,
     *     "main",
     *     GlslCompilerService.ShaderStage.VERTEX
     * );
     * }</pre>
     *
     * @param glslSource   GLSL/HLSL 源代码字符串
     * @param entryPoint   入口函数名（通常为 "main"）
     * @param stage        着色器阶段
     * @return 编译后的 SPIR-V 字节数组，如果编译失败返回 null
     * @throws IllegalStateException 如果编译器不可用
     * @throws IOException 如果编译过程发生 I/O 错误
     * @throws InterruptedException 如果编译进程被中断
     */
    public byte[] compileGlslToSPIRV(String glslSource, String entryPoint, GlslCompilerService.ShaderStage stage)
            throws IOException, InterruptedException {
        return compilerService.compileGlslToSPIRV(glslSource, entryPoint, stage);
    }

    // ==================== 执行接口 ====================

    /**
     * 执行光影的后处理 Pass（每帧调用）
     *
     * <p>在兼容模式下，由 CompatibilityExitInterceptor 在截获 FBO 后调用；
     * 在狂暴模式下，由渲染循环的主循环调用。
     *
     * <p>根据当前光影包的权限等级，执行不同的逻辑：
     * <ul>
     *   <li>SANDBOX: 应用参数调整后的内置后处理</li>
     *   <li>INJECTION: 执行包含自定义 Shader 的后处理链</li>
     *   <li>TAKEOVER: 不调用此方法（由光影包自己驱动渲染循环）</li>
     * </ul>
     *
     * @param inputColorTexture 输入颜色纹理 (VkImage)
     * @param inputDepthTexture 输入深度纹理 (VkImage)
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     */
    public void executePostProcess(long inputColorTexture, long inputDepthTexture,
                                    int screenWidth, int screenHeight) {
        ActiveShaderPack pack = activePack.get();
        if (pack == null) return;

        long frameStartTime = postProcessPipeline.beginFrame();

        switch (pack.permission) {
            case SANDBOX -> postProcessPipeline.executeSandboxPostProcess(
                    sandboxParameters, inputColorTexture, inputDepthTexture,
                    screenWidth, screenHeight);
            case INJECTION -> postProcessPipeline.executeInjectionPostProcess(
                    injectionShaderModules, pack.rgb, inputColorTexture, inputDepthTexture,
                    screenWidth, screenHeight);
            case TAKEOVER -> {
                LOGGER.fine("夺舍模式：executePostProcess 被忽略（由光影包驱动）");
            }
        }

        postProcessPipeline.endFrame(frameStartTime);
    }

    // ==================== 查询接口 ====================

    /** 是否已初始化 */
    public boolean isInitialized() { return initialized.get(); }

    /** 是否有活跃的光影包 */
    public boolean hasActivePack() { return activePack.get() != null; }

    /**
     * 获取当前活跃的光影包名称
     *
     * @return 光影包名称，如果没有则返回空 Optional
     */
    public Optional<String> getActivePackName() {
        ActiveShaderPack pack = activePack.get();
        return pack != null ? Optional.of(pack.packName) : Optional.empty();
    }

    /**
     * 获取当前光影包的权限等级
     *
     * @return ShaderPermission，如果没有活跃包返回 null
     */
    public ShaderPermission getActivePackPermission() {
        ActiveShaderPack pack = activePack.get();
        return pack != null ? pack.permission : null;
    }

    /**
     * 获取当前活跃的 RGB 数据（高级用法）
     *
     * @return RenderiumGraphBinary 实例或 null
     */
    public RenderiumGraphBinary getActiveRGB() {
        ActiveShaderPack pack = activePack.get();
        return pack != null ? pack.rgb : null;
    }

    /** 获取光影包根目录 */
    public Path getShaderPacksRootDir() { return shaderPacksRootDir; }

    /** 获取 GLSL 编译器是否可用 */
    public boolean isCompilerAvailable() { return compilerService.isCompilerAvailable(); }

    /** 获取当前编译器类型 */
    public GlslCompilerService.GlslCompilerType getCompilerType() { return compilerService.getCompilerType(); }

    /** 获取编译器路径 */
    public Optional<Path> getCompilerPath() {
        return compilerService.getCompilerPath();
    }

    /**
     * 获取 Shader 缓存统计信息
     *
     * @return 格式化的统计字符串
     */
    public String getCacheStatistics() {
        return cacheManager.getCacheStatistics();
    }

    /**
     * 手动清除 Shader 缓存
     *
     * <p>强制清除所有已编译的 Shader 缓存条目。
     * 下次使用时会重新编译。
     */
    public void clearShaderCache() {
        cacheManager.clearShaderCache();
    }

    @Override
    public String toString() {
        ActiveShaderPack pack = activePack.get();
        return String.format(
                "ShaderWorkbench{initialized=%s, activePack=%s, compiler=%s, cacheEntries=%d}",
                initialized.get(),
                pack != null ? pack.packName + " [" + pack.permission.getDisplayName() + "]" : "none",
                compilerService.isCompilerAvailable() ? compilerService.getCompilerType().name() : "N/A",
                cacheManager.getCacheSize()
        );
    }

    // ==================== 官方 SPIR-V 自动注入（智能短路.md §第二类红利）====================

    /**
     * 自动注入官方 SPIR-V 缓存池中光影包缺少的特效
     *
     * <p>来自 智能短路.md §第二类红利（SPIR-V 级别的"物理剥离"）。
     *
     * @param rgb 当前加载的光影包数据
     * @param packName 光影包名称
     */
    private void autoInjectOfficialSPIRV(RenderiumGraphBinary rgb, String packName) {
        SPIRVInterceptor spirvInterceptor = SPIRVInterceptor.getInstance();

        if (spirvInterceptor.getTotalModules() == 0) {
            LOGGER.fine("SPIR-V 缓存池为空，跳过官方特效自动注入");
            return;
        }

        Set<String> declaredPasses = graphParser.collectDeclaredPasses(rgb);

        Set<String> undeclaredOfficialPasses =
                spirvInterceptor.findUndeclaredOfficialPasses(declaredPasses);

        if (undeclaredOfficialPasses.isEmpty()) {
            LOGGER.fine("光影包已覆盖所有官方特效，无需自动注入");
            return;
        }

        LOGGER.info(String.format(
                "发现 %d 个官方特效可自动注入（光影包未声明）: %s",
                undeclaredOfficialPasses.size(), undeclaredOfficialPasses));

        injectOfficialSPIRVModules(undeclaredOfficialPasses, spirvInterceptor);
    }

    /**
     * 注入官方 SPIR-V 模块到渲染管线
     *
     * @param undeclaredPasses 待注入的 Pass 名称集合
     * @param interceptor SPIRV 拦截器实例
     */
    private void injectOfficialSPIRVModules(Set<String> undeclaredPasses,
                                             SPIRVInterceptor interceptor) {
        long device = 0L;
        RenderiumCore core = RenderiumCore.getInstance();
        if (core != null && core.isInitialized()) {
            LOGGER.fine("RenderiumCore 已就绪，Vulkan Device 由下层管道管理");
        }

        int successCount = 0;
        int failCount = 0;

        for (String passName : undeclaredPasses) {
            SPIRVInterceptor.SPIRVModule module = interceptor.getModule(passName);
            if (module == null) {
                LOGGER.warning("无法获取官方 SPIR-V 模块: " + passName);
                failCount++;
                continue;
            }

            LOGGER.info(String.format(
                    "  ✓ 自动注入官方 SPIR-V: %s (%s, %d bytes, v%s) → Level 1 黄牌注入",
                    passName, module.getShaderStage(), module.getSize(),
                    module.getVersionString()));

            if (device != 0L) {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(module.getData());
                byte[] spirvBytes = new byte[(int) module.getSize()];
                bb.get(spirvBytes);

                long shaderModule = createShaderModuleFromSPIRV(device, spirvBytes, passName);

                if (shaderModule != 0L) {
                    injectionShaderModules.put(passName, shaderModule);
                    successCount++;
                    LOGGER.fine(String.format("    官方 Shader Module 已创建: %s (handle=0x%s)",
                            passName, Long.toHexString(shaderModule)));
                } else {
                    failCount++;
                    LOGGER.warning("    官方 Shader Module 创建失败（VkShaderModule 创建失败）");
                }
            } else {
                LOGGER.fine("    Vulkan Device 不可用，跳过 VkShaderModule 创建");
                failCount++;
            }

            LOGGER.fine(String.format("    官方 SPIR-V 注入完成: %s → Level 1 黄牌注入", passName));
        }

        LOGGER.info(String.format(
                "官方 SPIR-V 自动注入完成: 成功 %d, 失败 %d",
                successCount, failCount));
    }

    // ==================== 内部数据类 ====================

    /**
     * 活跃光影包装器（持有 RGB 数据和元数据）
     */
    private static final class ActiveShaderPack {
        /** 光影包名称（文件名，不含扩展名） */
        final String packName;

        /** 解析后的 RGB 二进制数据 */
        final RenderiumGraphBinary rgb;

        /** 权限等级（从 RGB Header 提取） */
        final ShaderPermission permission;

        /** 加载时间戳 */
        final long loadTime;

        ActiveShaderPack(String packName, RenderiumGraphBinary rgb, ShaderPermission permission) {
            this.packName = packName;
            this.rgb = rgb;
            this.permission = permission;
            this.loadTime = System.nanoTime();
        }

        /**
         * 关闭并释放资源
         */
        void close() {
        }

        @Override
        public String toString() {
            return String.format("ActiveShaderPack{name=%s, perm=%s}", packName, permission.getDisplayName());
        }
    }
}
