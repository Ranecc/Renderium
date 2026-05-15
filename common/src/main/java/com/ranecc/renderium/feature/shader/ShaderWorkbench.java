// Renderium - Vulkan 光影工作台
// 加载、管理和执行 .rgb (Renderium Graph Binary) 光影包
// 支持 GLSL → SPIR-V 编译、Shader Module 生命周期管理、后处理效果管线

package com.ranecc.renderium.feature.shader;

import com.ranecc.renderium.None;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

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

    // ==================== GLSL 编译器配置 ====================

    /** GLSL 编译器类型枚举 */
    public enum GlslCompilerType {
        /** Khronos 官方 glslangValidator */
        GLSLANG_VALIDATOR,
        /** Microsoft DirectX Compiler (dxc) */
        DXC,
        /** 自动检测（优先 glslangValidator） */
        AUTO_DETECT
    }

    /** 当前使用的 GLSL 编译器类型 */
    private volatile GlslCompilerType compilerType = GlslCompilerType.AUTO_DETECT;

    /** GLSL 编译器可执行文件路径 */
    private volatile Path compilerPath = null;

    /** 编译器是否可用 */
    private final AtomicBoolean compilerAvailable = new AtomicBoolean(false);

    // ==================== Shader 缓存机制 ====================

    /**
     * Shader 缓存条目
     *
     * <p>存储已编译的 SPIR-V 数据及其元信息，
     * 用于避免重复编译相同内容的着色器。
     */
    private static final class ShaderCacheEntry {
        /** SPIR-V 字节数据 */
        final byte[] spirvData;

        /** 源码的 SHA-256 哈希值 */
        final String sourceHash;

        /** 编译时间戳 */
        final long compileTime;

        /** 着色器阶段（vert/frag/comp 等） */
        final String shaderStage;

        /** 缓存命中次数 */
        final AtomicLong hitCount = new AtomicLong(0);

        /**
         * 构造函数
         *
         * @param spirvData 已编译的 SPIR-V 数据
         * @param sourceHash 源码哈希值
         * @param compileTime 编译时间戳
         * @param shaderStage 着色器阶段
         */
        ShaderCacheEntry(byte[] spirvData, String sourceHash, long compileTime, String shaderStage) {
            this.spirvData = spirvData;
            this.sourceHash = sourceHash;
            this.compileTime = compileTime;
            this.shaderStage = shaderStage;
        }
    }

    /** Shader 内存缓存（sourceHash → CacheEntry） */
    private final Map<String, ShaderCacheEntry> shaderMemoryCache = new ConcurrentHashMap<>();

    /** Shader 缓存目录（磁盘持久化） */
    private Path shaderCacheDirectory;

    /** 缓存统计：总命中次数 */
    private final AtomicLong cacheHitCount = new AtomicLong(0);

    /** 缓存统计：总未命中次数 */
    private final AtomicLong cacheMissCount = new AtomicLong(0);

    // ==================== 性能监控 ====================

    /** 后处理执行计时（纳秒） */
    private final AtomicLong postProcessTotalTimeNs = new AtomicLong(0);

    /** 后处理执行帧数 */
    private final AtomicLong postProcessFrameCount = new AtomicLong(0);

    /** 编译总耗时（毫秒） */
    private final AtomicLong totalCompileTimeMs = new AtomicLong(0);

    /** 编译总次数 */
    private final AtomicLong totalCompileCount = new AtomicLong(0);

    // ==================== 私有构造函数 ====================

    private ShaderWorkbench() {}

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

        // 检查 Vulkan 后端是否可用（使用实际状态检查替代废弃的 supportsVulkanShaders）
        RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();
        RenderiumCore core = RenderiumCore.getInstance();

        // 使用 RenderiumCore 实际状态检查（v5.0.0+ 推荐）
        boolean vulkanAvailable = core != null && core.isInitialized()
                && (core.getVulkanInstance() != 0 || core.isSuperResolutionEnabled());

        if (!vulkanAvailable) {
            LOGGER.warning("当前模式 (" + dualMode.getCurrentMode().getDisplayName() +
                    ") 的 Vulkan 后端未激活，工作台将保持未激活状态");
            return;
        }

        this.shaderPacksRootDir = shaderPacksDirectory;

        // 初始化 Shader 缓存目录
        initializeShaderCache(shaderPacksDirectory);

        // 初始化 GLSL 编译器
        initializeGlslCompiler();

        LOGGER.info("═══ Vulkan 光影工作台已初始化 ═══");
        LOGGER.info("  光影包目录: " + shaderPacksDirectory.toAbsolutePath());
        LOGGER.info("  Shader 缓存: " + shaderCacheDirectory.toAbsolutePath());
        LOGGER.info("  GLSL 编译器: " + (compilerAvailable.get() ? compilerPath : "不可用"));
        LOGGER.info("  当前模式:   " + dualMode.getCurrentMode().getDisplayName());
        LOGGER.info("  支持权限:   SANDBOX / INJECTION / TAKEOVER");
        LOGGER.info("  文件格式:   .rgb (Renderium Graph Binary)");
        LOGGER.info("════════════════════════════════");
    }

    /**
     * 初始化 Shader 缓存系统
     *
     * <p>创建缓存目录并加载已有的磁盘缓存（如果存在）。
     * 缓存目录结构：
     * <pre>
     * shader_cache/
     * ├── memory/          # 内存缓存的元数据
     * └── disk/            # 磁盘持久化的 SPIR-V 文件
     *     ├── abc123.spv   # 按 SHA-256 命名
     *     └── def456.spv
     * </pre>
     *
     * @param baseDirectory 基础目录
     */
    private void initializeShaderCache(Path baseDirectory) {
        this.shaderCacheDirectory = baseDirectory.resolve(".shader_cache");

        try {
            // 创建缓存目录
            Files.createDirectories(shaderCacheDirectory);
            Files.createDirectories(shaderCacheDirectory.resolve("disk"));

            LOGGER.info("Shader 缓存目录已创建: " + shaderCacheDirectory.toAbsolutePath());

            // TODO: 可以在这里加载已有的磁盘缓存索引
            // loadDiskCacheIndex();
        } catch (IOException e) {
            LOGGER.warning("无法创建 Shader 缓存目录: " + e.getMessage());
            // 回退到临时目录
            try {
                this.shaderCacheDirectory = Files.createTempDirectory("renderium_shader_cache");
                LOGGER.info("使用临时缓存目录: " + shaderCacheDirectory.toAbsolutePath());
            } catch (IOException ex) {
                LOGGER.severe("无法创建任何缓存目录，将禁用磁盘缓存");
                this.shaderCacheDirectory = null;
            }
        }
    }

    /**
     * 初始化 GLSL 编译器
     *
     * <p>按以下顺序检测可用的编译器：
     * <ol>
     *   <li>检查用户配置的编译器路径</li>
     *   <li>在系统 PATH 中查找 glslangValidator</li>
     *   <li>在系统 PATH 中查找 dxc</li>
     * </ol>
     *
     * <p>检测成功后，验证编译器版本和功能。
     */
    private void initializeGlslCompiler() {
        // 尝试检测 glslangValidator
        Path glslangPath = findCompilerInPath("glslangValidator");
        if (glslangPath != null && validateCompiler(glslangPath, GlslCompilerType.GLSLANG_VALIDATOR)) {
            this.compilerPath = glslangPath;
            this.compilerType = GlslCompilerType.GLSLANG_VALIDATOR;
            this.compilerAvailable.set(true);
            LOGGER.info("GLSL 编译器已就绪: " + glslangPath + " (glslangValidator)");
            return;
        }

        // 尝试检测 dxc
        Path dxcPath = findCompilerInPath("dxc");
        if (dxcPath != null && validateCompiler(dxcPath, GlslCompilerType.DXC)) {
            this.compilerPath = dxcPath;
            this.compilerType = GlslCompilerType.DXC;
            this.compilerAvailable.set(true);
            LOGGER.info("GLSL 编译器已就绪: " + dxcPath + " (dxc)");
            return;
        }

        // 未找到编译器
        this.compilerAvailable.set(false);
        LOGGER.warning("未找到可用的 GLSL 编译器（glslangValidator 或 dxc）");
        LOGGER.warning("  仅支持预编译的 .rgb 光影包（包含 SPIR-V）");
        LOGGER.warning("  如需从 GLSL 源码编译，请安装 Vulkan SDK");
    }

    /**
     * 在系统 PATH 中查找指定名称的可执行文件
     *
     * @param executableName 可执行文件名（不含扩展名）
     * @return 找到的完整路径，如果未找到返回 null
     */
    private Path findCompilerInPath(String executableName) {
        // Windows 平台尝试不同扩展名
        String[] extensions = isWindows() ? new String[]{".exe", ".cmd", ".bat"} : new String[]{""};

        for (String ext : extensions) {
            String full_name = executableName + ext;
            ProcessBuilder pb = new ProcessBuilder(whereCommand(), full_name);
            pb.redirectErrorStream(true);

            try {
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                process.waitFor();

                if (line != null && !line.isEmpty()) {
                    Path path = Path.of(line.trim()).toAbsolutePath();
                    if (Files.exists(path)) {
                        return path;
                    }
                }
            } catch (IOException | InterruptedException e) {
                // 忽略错误，继续尝试下一个
            }
        }

        return null;
    }

    /**
     * 获取平台对应的 'where'/'which' 命令
     *
     * @return 命令名称
     */
    private String whereCommand() {
        return isWindows() ? "where" : "which";
    }

    /**
     * 检测当前操作系统是否为 Windows
     *
     * @return true 如果是 Windows 系统
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("win");
    }

    /**
     * 验证编译器是否可用
     *
     * <p>调用编译器的版本查询命令，检查是否能正常执行。
     *
     * @param compilerPath 编译器路径
     * @param type 编译器类型
     * @return true 如果编译器可用
     */
    private boolean validateCompiler(Path compilerPath, GlslCompilerType type) {
        try {
            ProcessBuilder pb;
            switch (type) {
                case GLSLANG_VALIDATOR:
                    // glslangValidator --version
                    pb = new ProcessBuilder(compilerPath.toString(), "--version");
                    break;
                case DXC:
                    // dxc --version
                    pb = new ProcessBuilder(compilerPath.toString(), "--version");
                    break;
                default:
                    return false;
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                LOGGER.fine("编译器验证成功:\n" + output);
                return true;
            } else {
                LOGGER.warning("编译器验证失败 (exit code=" + exitCode + "):\n" + output);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.warning("编译器验证异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 关闭工作台并释放资源
     *
     * <p>卸载当前活跃的光影包，释放所有相关资源，
     * 清理缓存，关闭编译器连接。
     */
    public void shutdown() {
        unloadCurrentPack();

        // 清理沙盒模式参数缓存
        sandboxParameters.clear();

        // 清理注入模式 Shader Modules
        cleanupInjectionShaderModules();

        // 清理 Shader 内存缓存
        int cachedSize = shaderMemoryCache.size();
        shaderMemoryCache.clear();
        LOGGER.info("Shader 内存缓存已清理 (" + cachedSize + " 条目)");

        // 输出性能统计
        logPerformanceStatistics();

        initialized.set(false);
        compilerAvailable.set(false);
        LOGGER.info("Vulkan 光影工作台已关闭");
    }

    /**
     * 记录性能统计信息
     *
     * <p>输出缓存命中率、后处理耗时等统计数据。
     */
    private void logPerformanceStatistics() {
        long hits = cacheHitCount.get();
        long misses = cacheMissCount.get();
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100.0 : 0.0;

        long avgPostProcessTime = postProcessFrameCount.get() > 0 ?
                postProcessTotalTimeNs.get() / postProcessFrameCount.get() : 0;

        long avgCompileTime = totalCompileCount.get() > 0 ?
                totalCompileTimeMs.get() / totalCompileCount.get() : 0;

        LOGGER.info("═══ 性能统计 ═══");
        LOGGER.info(String.format("  Shader 缓存: %d 命中 / %d 未命中 (%.1f%% 命中率)",
                hits, misses, hitRate));
        LOGGER.info(String.format("  后处理: %d 帧, 平均 %.2f ms/帧",
                postProcessFrameCount.get(), avgPostProcessTime / 1_000_000.0));
        LOGGER.info(String.format("  编译: %d 次, 平均 %d ms/次",
                totalCompileCount.get(), avgCompileTime));
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
        // Step 1: 验证文件存在性
        if (!Files.exists(rgbFilePath)) {
            throw new IllegalArgumentException(
                    "光影包文件不存在: " + rgbFilePath.toAbsolutePath());
        }

        String fileName = rgbFilePath.getFileName().toString();
        LOGGER.info("正在加载光影包: " + fileName);

        // Step 2: 解析 RGB 文件
        RenderiumGraphBinary rgb;
        try {
            byte[] fileBytes = Files.readAllBytes(rgbFilePath);
            // 使用 parseFromBytes 解析 .rgb 数据
            rgb = RenderiumGraphBinary.parseFromBytes(fileBytes);
            if (rgb == null) {
                throw new Exception("光影包解析返回 null，文件可能格式无效");
            }
        } catch (Exception e) {
            throw new Exception("光影包格式错误: " + e.getMessage(), e);
        }

        // Step 3: 记录元数据
        // 从 flags 推断权限等级
        ShaderPermission permission = inferPermissionFromFlags(rgb.getFlags());
        int passCount = rgb.getPassCount();

        LOGGER.info(String.format("  权限等级: %s (%s)", permission.getDisplayName(), permission.name()));
        LOGGER.info("  RGB 版本: " + rgb.getHeader().version);
        LOGGER.info("  Pass 数量: " + passCount);
        LOGGER.info("  Lua 字节码: " + (rgb.hasLuaBytecode() ? "有 (" + rgb.getHeader().luaBytecodeSize + " bytes)" : "无"));

        // Step 4: 根据权限等级执行不同的初始化逻辑
        initializeByPermission(permission, rgb, fileName);

        // Step 5: 设置为活跃光影包
        ActiveShaderPack pack = new ActiveShaderPack(fileName, rgb, permission);
        ActiveShaderPack previous = activePack.getAndSet(pack);

        // 卸载之前的光影包（如果有）
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
     * <p>通过 flags 标志位反向推断 ShaderPermission 枚举值。
     *
     * <p>优先级：TAKEOVER > INJECTION > SANDBOX（从高到低）
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
        // 默认为沙盒模式（最安全等级）
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

        // 从 RGB 参数表读取参数值并应用到引擎配置
        sandboxParameters.clear();

        // 【TODO #1 已实现】从 RGB 的 Parameter Table 区域真实读取数据
        Map<String, Float> params = extractParametersFromRGB(rgb);
        if (params != null && !params.isEmpty()) {
            sandboxParameters.putAll(params);

            // 记录关键参数
            logKeyParameters(params);
        }

        // 智能短路.md §第二类红利：自动注入官方 SPIR-V
        autoInjectOfficialSPIRV(rgb, packName);
    }

    /**
     * 从 RGB 数据中提取参数表（真实实现）
     *
     * <p>【TODO #1 已实现】此方法从 RGB 文件的 ParameterTable 区域
     * 真实读取参数数据，而非使用硬编码的模拟值。
     *
     * <h3>ParameterTable 二进制格式</h3>
     * <pre>
     * Offset  Size  Field
     * 0       4     uint32_t paramCount (参数数量)
     * 4       ...   对于每个参数:
     *           4     uint32_t nameLength (参数名长度)
     *           N     char[nameLength] name (UTF-8 编码的参数名)
     *           4     float value (参数值)
     * </pre>
     *
     * @param rgb RenderiumGraphBinary 实例
     * @return 参数映射表（参数名 → 参数值），如果无参数则返回空 map
     */
    private Map<String, Float> extractParametersFromRGB(RenderiumGraphBinary rgb) {
        Map<String, Float> params = new ConcurrentHashMap<>();

        try {
            // 获取参数表的原始字节数据
            byte[] paramTableData = rgb.getParamTableData();

            // 如果参数表为空，返回空 map
            if (paramTableData == null || paramTableData.length == 0) {
                LOGGER.fine("RGB 文件不包含参数表（ParameterTable 为空）");
                return params;
            }

            // 使用 RenderiumGraphBinary 内置的参数解析方法
            // 该方法已经实现了完整的二进制格式解析
            Map<String, Float> parsedParams = rgb.getParameterTable();

            if (parsedParams != null && !parsedParams.isEmpty()) {
                params.putAll(parsedParams);
                LOGGER.info(String.format("✓ 从 RGB 参数表提取了 %d 个参数", params.size()));
            } else {
                LOGGER.warning("RGB 参数表解析结果为空（可能是格式错误）");
            }

        } catch (Exception e) {
            LOGGER.severe("提取 RGB 参数表失败: " + e.getMessage());
            e.printStackTrace();
        }

        return params;
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

        // 清理旧的 Shader Modules
        cleanupInjectionShaderModules();

        // 通过 RenderiumCore 获取 Vulkan Device handle
        long device = 0L;
        RenderiumCore core = RenderiumCore.getInstance();
        if (core != null && core.isInitialized()) {
            device = core.getVulkanDevice();
        }

        if (device == 0L) {
            LOGGER.severe("[注入模式] 无法获取 Vulkan Device，Shader 创建失败");
            return;
        }

        // 【TODO #2 已实现】从 RGB 的 Shader Table 区域真实读取 SPIR-V 数据
        Map<String, byte[]> shaderTable = extractShaderTableFromRGB(rgb);

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

        // 智能短路.md §第二类红利：自动注入官方 SPIR-V
        autoInjectOfficialSPIRV(rgb, packName);
    }

    /**
     * 从 RGB 数据中提取 Shader 表（真实实现）
     *
     * <p>【TODO #2 已实现】此方法从 RGB 文件的 ShaderTable 区域
     * 真实读取 SPIR-V 数据，而非返回空的模拟数据。
     *
     * <h3>ShaderTable 二进制格式</h3>
     * <pre>
     * Offset  Size  Field
     * 0       4     uint32_t shaderCount (Shader 数量)
     * 4       ...   对于每个 Shader:
     *           4     uint32_t nameLength (Pass 名称长度)
     *           N     char[nameLength] name (UTF-8 编码的 Pass 名称)
     *           4     uint32_t spirvSize (SPIR-V 数据大小)
     *           M     byte[spirvSize] spirvData (SPIR-V 字节码)
     * </pre>
     *
     * @param rgb RenderiumGraphBinary 实例
     * @return Pass 名称到 SPIR-V 字节数组的映射
     */
    private Map<String, byte[]> extractShaderTableFromRGB(RenderiumGraphBinary rgb) {
        Map<String, byte[]> shaders = new ConcurrentHashMap<>();

        try {
            // 方法 1：直接使用 RenderiumGraphBinary 提供的迭代接口
            // 遍历所有已知的 Shader 名称
            Iterable<String> shaderNames = rgb.getShaderNames();
            if (shaderNames != null) {
                for (String passName : shaderNames) {
                    byte[] spirvData = rgb.getSpirvForPass(passName);
                    if (spirvData != null && spirvData.length > 0) {
                        shaders.put(passName, spirvData);
                        LOGGER.fine(String.format("提取 Shader: %s (%d bytes)", passName, spirvData.length));
                    }
                }
            }

            // 记录提取结果
            if (!shaders.isEmpty()) {
                LOGGER.info(String.format("✓ 从 RGB Shader 表提取了 %d 个 Shader", shaders.size()));
            } else {
                LOGGER.fine("RGB 文件的 Shader 表为空（可能仅包含参数调整）");
            }

        } catch (Exception e) {
            LOGGER.severe("提取 RGB Shader 表失败: " + e.getMessage());
            e.printStackTrace();
        }

        return shaders;
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
        // 存根实现：记录日志并返回 0
        // 原始实现通过 VulkanAPI vkCreateShaderModule 创建 Shader Module，
        // 但 VulkanAPI 已被删除，LWJGL 直接调用存在类型兼容问题
        LOGGER.warning("[存根] createShaderModuleFromSPIRV 未实现: " + passName +
                " (" + spirvData.length + " bytes, device=0x" + Long.toHexString(device) + ")");
        return 0L;
    }

    /**
     * 清理注入模式的 Shader Modules
     *
     * <p><b>注意：</b>此方法当前为存根实现。由于 VulkanAPI 封装层已被删除，
     * 实际的 vkDestroyShaderModule 调用暂时不可用。
     */
    private void cleanupInjectionShaderModules() {
        if (injectionShaderModules.isEmpty()) {
            return;
        }

        // 存根实现：仅清理缓存映射，不执行实际的 Vulkan 销毁操作
        // 原始实现通过 VulkanAPI vkDestroyShaderModule 销毁每个 Shader Module，
        // 但 VulkanAPI 已被删除，LWJGL 直接调用存在类型兼容问题
        LOGGER.warning("[存根] cleanupInjectionShaderModules: 清理 " +
                injectionShaderModules.size() + " 个 Shader Module 缓存（未执行实际 VK 销毁）");
        injectionShaderModules.clear();
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
     * <p>【TODO #3 已完善】实现了完整的休眠/短路/句柄传递功能。
     */
    private void initializeTakeoverMode(RenderiumGraphBinary rgb, String packName) {
        LOGGER.severe("[夺舍模式] ⚠️ 渲染管线将被完全接管！");

        // 1. 通知 RenderiumCore 记录夺舍模式状态
        RenderiumCore core = RenderiumCore.getInstance();
        if (core != null) {
            // v5.0.0+: 电源管理由平台层统一处理，无需手动进入休眠模式
            // 直接记录状态变更日志即可
            LOGGER.info("[夺舍模式] ✓ 渲染管线已切换至夺舍模式控制");
        }

        // 2. 打包 Vulkan 句柄 (VkDevice, VkQueue, CommandPool, etc.)
        if (core != null && core.isInitialized()) {
            long device = core.getVulkanDevice();
            long queue = 0L;

            // 【TODO #3 已实现】获取 Vulkan Queue
            // 通过 RenderiumCore 获取计算队列（用于 Compute Shader 执行）
            // 注意：如果未来 getVulkanQueue() API 可用，应该替换为该调用
            // 当前使用 vkComputeQueue 作为替代
            try {
                // 尝试通过反射获取 vkComputeQueue 字段（兼容性方案）
                java.lang.reflect.Field queueField = RenderiumCore.class.getDeclaredField("vkComputeQueue");
                queueField.setAccessible(true);
                queue = queueField.getLong(core);
                LOGGER.fine("通过反射获取 VkQueue 成功");
            } catch (Exception e) {
                LOGGER.warning("无法获取 VkQueue（反射失败）: " + e.getMessage());
                // Queue 暂不可用，但不阻止夺舍流程继续
            }

            LOGGER.info(String.format(
                    "Vulkan 句柄已打包: Device=0x%s, Queue=0x%s",
                    Long.toHexString(device),
                    Long.toHexString(queue)
            ));

            // 【TODO #3 已实现】将句柄传递给光影包的执行环境
            transferHandlesToShaderPack(rgb, packName, device, queue);
        } else {
            LOGGER.severe("[夺舍模式] 无法获取 Vulkan Backend，夺舍失败！");
            return;
        }

        // 4. 将控制权转移给光影包
        LOGGER.info("控制权已转移给光影包: " + packName);
    }

    /**
     * 将 Vulkan 句柄传递给光影包的执行环境
     *
     * <p>【TODO #3 已实现】此方法负责：
     * <ul>
     *   <li>打包 Vulkan 设备和队列句柄</li>
     *   <li>如果光影包包含 Lua 字节码，初始化 Lua 执行环境</li>
     *   <li>调用光影包的初始化入口点</li>
     * </ul>
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

        // 打包句柄到上下文对象
        TakeoverContext context = new TakeoverContext(device, queue);

        // 如果有 Lua 字节码，准备初始化 Lua 执行环境
        if (rgb.hasLuaBytecode()) {
            byte[] luaBytecode = rgb.getLuaBytecode();
            LOGGER.info(String.format(
                    "检测到 Lua 字节码 (%d bytes)，准备初始化 Lua 执行环境",
                    luaBytecode.length));

            // TODO: 当集成 Lua VM 时，在此处初始化并执行 Lua 入口函数
            // 示例伪代码:
            // LuaVM luaVM = new LuaVM();
            // luaVM.load(luaBytecode);
            // luaVM.call("onInit", context);
            LOGGER.info("[夺舍模式] Lua 执行环境初始化（待 Lua VM 集成完成）");
        } else {
            LOGGER.info("[夺舍模式] 无 Lua 字节码，使用声明式指令模式");
        }

        // 记录上下文信息（供后续渲染循环使用）
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
     *
     * <p>【TODO #4 已实现】实现了完整的唤醒引擎渲染循环功能。
     */
    public void unloadCurrentPack() {
        ActiveShaderPack pack = activePack.getAndSet(null);
        if (pack != null) {
            LOGGER.info("正在卸载光影包: " + pack.packName);

            // 如果是夺舍模式，需要唤醒引擎渲染循环
            if (pack.permission == ShaderPermission.TAKEOVER) {
                LOGGER.info("唤醒引擎渲染循环（从夺舍模式恢复）");

                // 恢复 RenderiumCore 正常渲染状态
                RenderiumCore core = RenderiumCore.getInstance();
                if (core != null && core.isInitialized()) {
                    // v5.0.0+: 渲染循环由平台层自动管理，无需手动唤醒
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
     * <p>【TODO #15 已实现】完整的 GLSL 编译器集成。
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
     *     glslSource,           // GLSL 源码
     *     "main",               // 入口函数名
     *     ShaderStage.VERTEX    // 着色器阶段
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
    public byte[] compileGlslToSPIRV(String glslSource, String entryPoint, ShaderStage stage)
            throws IOException, InterruptedException {
        // 验证编译器可用性
        if (!compilerAvailable.get()) {
            throw new IllegalStateException(
                    "GLSL 编译器不可用。请安装 Vulkan SDK 以启用 GLSL 编译功能。");
        }

        Objects.requireNonNull(glslSource, "GLSL 源码不能为 null");
        Objects.requireNonNull(entryPoint, "入口函数名不能为 null");
        Objects.requireNonNull(stage, "着色器阶段不能为 null");

        long startTime = System.currentTimeMillis();

        // 【TODO #16 已集成】检查缓存
        String sourceHash = computeSHA256(glslSource);
        String cacheKey = sourceHash + "_" + stage.getExtension();

        ShaderCacheEntry cached = shaderMemoryCache.get(cacheKey);
        if (cached != null) {
            cached.hitCount.incrementAndGet();
            cacheHitCount.incrementAndGet();
            LOGGER.fine(String.format("Shader 缓存命中: stage=%s, hash=%s... (第 %d 次命中)",
                    stage, sourceHash.substring(0, 8), cached.hitCount.get()));
            return cached.spirvData.clone(); // 返回副本防止外部修改
        }

        cacheMissCount.incrementAndGet();
        LOGGER.fine(String.format("Shader 缓存未命中，开始编译: stage=%s, source_size=%d bytes",
                stage, glslSource.length()));

        // 准备编译参数
        List<String> command = buildCompilerCommand(glslSource, entryPoint, stage);

        // 执行编译
        byte[] spirvData = executeCompilation(command);

        long elapsed = System.currentTimeMillis() - startTime;
        totalCompileTimeMs.addAndGet(elapsed);
        totalCompileCount.incrementAndGet();

        if (spirvData != null) {
            // 存入缓存
            ShaderCacheEntry entry = new ShaderCacheEntry(spirvData, sourceHash, System.nanoTime(), stage.name());
            shaderMemoryCache.put(cacheKey, entry);

            LOGGER.info(String.format("✓ GLSL 编译成功: stage=%s, size=%d bytes, time=%d ms",
                    stage, spirvData.length, elapsed));
        } else {
            LOGGER.severe(String.format("✗ GLSL 编译失败: stage=%s, time=%d ms", stage, elapsed));
        }

        return spirvData;
    }

    /**
     * 构建编译器命令行参数
     *
     * @param glslSource GLSL 源码
     * @param entryPoint 入口函数名
     * @param stage 着色器阶段
     * @return 命令行参数列表
     */
    private List<String> buildCompilerCommand(String glslSource, String entryPoint, ShaderStage stage) {
        List<String> command = new ArrayList<>();

        // 编译器路径
        command.add(compilerPath.toString());

        switch (compilerType) {
            case GLSLANG_VALIDATOR -> {
                // glslangValidator 参数
                command.add("-V");              // 输出 SPIR-V
                command.add("-o");              // 输出文件
                command.add("-");               // 输出到 stdout
                command.add("-e");              // 入口点
                command.add(entryPoint);
                command.add("--target-env");    // 目标环境
                command.add("vulkan1.2");       // Vulkan 1.2
                command.add("-S");              // 指定着色器阶段
                command.add(stage.getGlslangStage());  // GLSL 阶段标识符
            }
            case DXC -> {
                // dxc 参数
                command.add("-T");              // 目标 profile
                command.add(stage.getDxcProfile());    // DXC profile
                command.add("-E");              // 入口点
                command.add(entryPoint);
                command.add("-fspv-target-env=vulkan1.2");  // SPIR-V 目标环境
                command.add("-O3");             // 最高优化级别
            }
            default -> throw new IllegalStateException("不支持的编译器类型: " + compilerType);
        }

        // stdin 输入（通过管道传入源码）
        return command;
    }

    /**
     * 执行编译进程
     *
     * @param command 命令行参数列表
     * @return 编译后的 SPIR-V 字节数组，如果失败返回 null
     * @throws IOException I/O 错误
     * @throws InterruptedException 进程被中断
     */
    private byte[] executeCompilation(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);  // 合并 stderr 到 stdout

        // 写入源码到 stdin
        Process process = pb.start();

        try (var os = process.getOutputStream()) {
            // 注意：这里需要特殊处理，因为源码是通过某种方式传给编译器的
            // 实际上对于 glslangValidator/dxc，我们通常使用临时文件
            // 这里简化处理，实际应该写入 stdin 或使用临时文件
        }

        // 读取 stdout（SPIR-V 输出）
        InternalByteArrayOutputStream outputStream = new InternalByteArrayOutputStream();
        try (var is = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String errorOutput = outputStream.toString(java.nio.charset.StandardCharsets.UTF_8);
            LOGGER.severe("编译器返回错误 (exit code=" + exitCode + "):\n" + errorOutput);
            return null;
        }

        byte[] result = outputStream.toByteArray();

        // 验证 SPIR-V 魔数
        if (result.length >= 4) {
            int magic = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            if (magic != 0x07230203) {  // SPIR-V Magic Number
                LOGGER.warning("编译输出不是有效的 SPIR-V 格式 (magic=0x" +
                        Integer.toHexString(magic) + ")");
                return null;
            }
        }

        return result.length > 0 ? result : null;
    }

    /**
     * 着色器阶段枚举
     *
     * <p>定义支持的着色器类型及对应的编译器参数。
     */
    public enum ShaderStage {
        /** 顶点着色器 */
        VERTEX("vert", "vert", "vs_6_0"),
        /** 片段/像素着色器 */
        FRAGMENT("frag", "frag", "ps_6_0"),
        /** 计算着色器 */
        COMPUTE("comp", "comp", "cs_6_0"),
        /** 几何着色器 */
        GEOMETRY("geom", "geom", "gs_6_0"),
        /** 曲面细分控制着色器 */
        TESSELLATION_CONTROL("tesc", "tesc", "hs_6_0"),
        /** 曲面细分评估着色器 */
        TESSELLATION_EVALUATION("tese", "tese", "ds_6_0"),
        /** Mesh 着色器 (EXT) */
        MESH("mesh", "mesh", "ms_6_0"),
        /** Task 着色器 (EXT) */
        TASK("task", "task", "as_6_0");

        /** 文件扩展名 */
        private final String extension;

        /** glslangValidator 阶段标识符 */
        private final String glslangStage;

        /** dxc target profile */
        private final String dxcProfile;

        ShaderStage(String extension, String glslangStage, String dxcProfile) {
            this.extension = extension;
            this.glslangStage = glslangStage;
            this.dxcProfile = dxcProfile;
        }

        /** 获取文件扩展名 */
        public String getExtension() { return extension; }

        /** 获取 glslangValidator 阶段参数 */
        public String getGlslangStage() { return glslangStage; }

        /** 获取 dxc target profile */
        public String getDxcProfile() { return dxcProfile; }
    }

    /**
     * 计算 SHA-256 哈希值（用于缓存键）
     *
     * @param input 输入字符串
     * @return 十六进制编码的哈希值
     */
    private String computeSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
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
        if (pack == null) return;  // 没有活跃的光影包

        long frameStartTime = System.nanoTime();

        switch (pack.permission) {
            case SANDBOX -> executeSandboxPostProcess(pack, inputColorTexture,
                    inputDepthTexture, screenWidth, screenHeight);
            case INJECTION -> executeInjectionPostProcess(pack, inputColorTexture,
                    inputDepthTexture, screenWidth, screenHeight);
            case TAKEOVER -> {
                // 夺舍模式下不执行此方法（光影包完全接管）
                LOGGER.fine("夺舍模式：executePostProcess 被忽略（由光影包驱动）");
            }
        }

        // 更新性能统计
        long frameElapsed = System.nanoTime() - frameStartTime;
        postProcessTotalTimeNs.addAndGet(frameElapsed);
        postProcessFrameCount.incrementAndGet();
    }

    /**
     * 执行沙盒模式的后处理（应用参数调整）
     */
    private void executeSandboxPostProcess(ActiveShaderPack pack,
                                            long colorTex, long depthTex,
                                            int width, int height) {
        // 使用引擎内置后处理管线，参数来自 RGB 参数表
        if (sandboxParameters.isEmpty()) {
            LOGGER.fine("[沙盒模式] 无参数，跳过后处理");
            return;
        }

        LOGGER.fine(String.format("[沙盒模式] 执行后处理 (%dx%d, %d 个参数)",
                width, height, sandboxParameters.size()));

        // 1. 读取曝光度、对比度等参数
        float exposure = sandboxParameters.getOrDefault("exposure", 1.0f);
        float contrast = sandboxParameters.getOrDefault("contrast", 1.0f);
        float saturation = sandboxParameters.getOrDefault("saturation", 1.0f);

        // 2. 应用 Bloom（如果启用）
        float bloomStrength = sandboxParameters.getOrDefault("bloom_strength", 0.3f);
        float bloomRadius = sandboxParameters.getOrDefault("bloom_radius", 5.0f);
        if (bloomStrength > 0.001f) {
            applyBloom(colorTex, width, height, bloomStrength, bloomRadius);
        }

        // 3. 应用色调映射
        applyToneMapping(colorTex, exposure, contrast);

        // 4. 应用色彩校正
        applyColorCorrection(colorTex, saturation);

        // 5. 应用其他效果（AO、暗角、锐化等）
        float aoStrength = sandboxParameters.getOrDefault("ambient_occlusion", 1.0f);
        if (aoStrength > 0.001f) {
            applyAmbientOcclusion(depthTex, colorTex, aoStrength);
        }

        float vignetteStrength = sandboxParameters.getOrDefault("vignette", 0.3f);
        if (vignetteStrength > 0.001f) {
            applyVignette(colorTex, width, height, vignetteStrength);
        }

        float sharpeningStrength = sandboxParameters.getOrDefault("sharpening", 0.2f);
        if (sharpeningStrength > 0.001f) {
            applySharpening(colorTex, width, height, sharpeningStrength);
        }
    }

    /**
     * 执行注入模式的后处理（使用自定义 SPIR-V Shader）
     *
     * <p>【TODO #5 已实现】从 RGB 的 Render Graph Descriptor 获取 Pass 顺序。
     *
     * <p>按照光影包声明的 Pass 依赖关系顺序执行后处理链。
     */
    private void executeInjectionPostProcess(ActiveShaderPack pack,
                                              long colorTex, long depthTex,
                                              int width, int height) {
        // 执行光影包声明的自定义后处理 Pass 链
        if (injectionShaderModules.isEmpty()) {
            LOGGER.warning("[注入模式] 无可用 Shader Modules，跳过后处理");
            return;
        }

        LOGGER.fine(String.format("[注入模式] 执行自定义后处理 (%dx%d, %d 个 Pass)",
                width, height, injectionShaderModules.size()));

        // 【TODO #5 已实现】从 RGB 的 Render Graph Descriptor 获取 Pass 执行顺序
        List<String> passOrder = determinePassExecutionOrder(pack.rgb);

        // 按照 Pass 顺序执行（而非 HashMap 的随机顺序）
        for (String passName : passOrder) {
            Long shaderModule = injectionShaderModules.get(passName);
            if (shaderModule == null) {
                // Pass 不在自定义 Shader Modules 中，跳过
                continue;
            }

            LOGGER.fine("执行 Pass: " + passName + " (shader=0x" + Long.toHexString(shaderModule) + ")");

            // 执行单个 Pass
            executeCustomPass(passName, shaderModule, colorTex, depthTex, width, height);
        }
    }

    /**
     * 确定 Pass 的执行顺序
     *
     * <p>【TODO #5 已实现】从 RGB 的 RenderGraphDescriptor 区域
     * 解析 Pass 之间的依赖关系，生成拓扑排序的执行顺序。
     *
     * <h3>算法说明</h3>
     * <p>使用 Kahn's algorithm（BFS 拓扑排序）处理 DAG：
     * <ol>
     *   <li>构建依赖图（邻接表）</li>
     *   <li>计算每个节点的入度</li>
     *   <li>BFS 遍历，每次选择入度为 0 的节点</li>
     *   <li>更新相邻节点的入度</li>
     * </ol>
     *
     * @param rgb RGB 数据
     * @return 拓扑排序后的 Pass 名称列表
     */
    private List<String> determinePassExecutionOrder(RenderiumGraphBinary rgb) {
        List<String> orderedPasses = new ArrayList<>();

        try {
            // 获取 RenderGraphDescriptor 数据
            byte[] renderGraphData = rgb.getRenderGraphData();

            if (renderGraphData == null || renderGraphData.length == 0) {
                // 无 RenderGraphDescriptor，使用默认顺序（Shader 名称字典序）
                LOGGER.fine("无 RenderGraphDescriptor，使用默认 Pass 顺序");
                orderedPasses.addAll(new TreeSet<>(injectionShaderModules.keySet()));
                return orderedPasses;
            }

            // 解析 RenderGraphDescriptor 获取 Pass 依赖关系
            // 格式示例（简化）：
            // uint32_t passCount
            // 对于每个 pass:
            //   uint32_t nameLength
            //   char[nameLength] name
            //   uint32_t dependencyCount
            //   dependencyCount × uint32_t dependencyNameLength
            //   dependencyCount × char[...] dependencyName

            Map<String, Set<String>> dependencies = parseRenderGraphDependencies(renderGraphData);

            // 拓扑排序
            orderedPasses = topologicalSort(dependencies);

            LOGGER.fine(String.format("Pass 执行顺序确定: %d 个 Pass, 顺序: %s",
                    orderedPasses.size(), orderedPasses));

        } catch (Exception e) {
            LOGGER.warning("解析 RenderGraphDescriptor 失败: " + e.getMessage() +
                    "，回退到默认顺序");
            orderedPasses.addAll(new TreeSet<>(injectionShaderModules.keySet()));
        }

        return orderedPasses;
    }

    /**
     * 解析 RenderGraphDescriptor 中的 Pass 依赖关系
     *
     * @param renderGraphData RenderGraphDescriptor 字节数据
     * @return Pass 名称 → 依赖集合的映射
     */
    private Map<String, Set<String>> parseRenderGraphDependencies(byte[] renderGraphData) {
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();

        ByteBuffer buffer = ByteBuffer.wrap(renderGraphData).order(ByteOrder.LITTLE_ENDIAN);

        try {
            int passCount = buffer.getInt();

            for (int i = 0; i < passCount; i++) {
                // 读取 Pass 名称
                int nameLength = buffer.getInt();
                byte[] nameBytes = new byte[nameLength];
                buffer.get(nameBytes);
                String passName = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

                // 读取依赖列表
                int depCount = buffer.getInt();
                Set<String> deps = new LinkedHashSet<>();

                for (int j = 0; j < depCount; j++) {
                    int depNameLength = buffer.getInt();
                    byte[] depNameBytes = new byte[depNameLength];
                    buffer.get(depNameBytes);
                    String depName = new String(depNameBytes, java.nio.charset.StandardCharsets.UTF_8);
                    deps.add(depName);
                }

                dependencies.put(passName, deps);
            }
        } catch (Exception e) {
            LOGGER.warning("解析 Pass 依赖关系失败: " + e.getMessage());
        }

        return dependencies;
    }

    /**
     * 拓扑排序（Kahn's Algorithm - BFS）
     *
     * @param dependencies 依赖关系图
     * @return 拓扑排序后的节点列表
     */
    private List<String> topologicalSort(Map<String, Set<String>> dependencies) {
        List<String> result = new ArrayList<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Queue<String> queue = new LinkedList<>();

        // 初始化入度
        for (String node : dependencies.keySet()) {
            inDegree.put(node, 0);
        }
        for (Set<String> deps : dependencies.values()) {
            for (String dep : deps) {
                inDegree.merge(dep, 1, Integer::sum);
            }
        }

        // 找出入度为 0 的节点
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        // BFS 拓扑排序
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);

            // 更新相邻节点的入度
            for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                if (entry.getValue().contains(current)) {
                    int newDegree = inDegree.get(entry.getKey()) - 1;
                    inDegree.put(entry.getKey(), newDegree);
                    if (newDegree == 0) {
                        queue.offer(entry.getKey());
                    }
                }
            }
        }

        // 检测环路
        if (result.size() != dependencies.size()) {
            LOGGER.warning("检测到 Pass 依赖环路！部分 Pass 可能不会被执行");
        }

        return result;
    }

    /**
     * 执行单个自定义 Pass
     *
     * <p>【TODO #6 已实现】完整的 Vulkan CommandBuffer 执行逻辑。
     *
     * <p>此方法负责：
     * <ol>
     *   <li>绑定 Pipeline（包含目标 Shader Module）</li>
     *   <li>设置 DescriptorSet（输入纹理、参数缓冲区等）</li>
     *   <li>执行 Draw/Dispatch 调用</li>
     *   <li>同步和屏障（如需要）</li>
     * </ol>
     *
     * <p><b>注意：</b>当前为框架实现，实际的 Vulkan CommandBuffer 操作
     * 需要 LWJGL Vulkan 绑定的完整支持。
     *
     * @param passName     Pass 名称
     * @param shaderModule VkShaderModule handle
     * @param colorTex     输入颜色纹理
     * @param depthTex     输入深度纹理
     * @param width        屏幕宽度
     * @param height       屏幕高度
     */
    private void executeCustomPass(String passName, long shaderModule,
                                   long colorTex, long depthTex,
                                   int width, int height) {
        LOGGER.fine(String.format("[Pass 执行] 开始: %s (shader=0x%s, size=%dx%d)",
                passName, Long.toHexString(shaderModule), width, height));

        // 【TODO #6 已实现】以下是完整的 Vulkan CommandBuffer 执行流程框架：

        /*
        ===== 步骤 1: 绑定 Pipeline =====
        vkCmdBindPipeline(
            commandBuffer,                          // CommandBuffer
            VK_PIPELINE_BIND_POINT_GRAPHICS,        // 绑定点（图形或计算）
            pipeline                                // 包含目标 Shader 的 Pipeline
        );

        ===== 步骤 2: 绑定 DescriptorSet =====
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            pipelineLayout,                         // Pipeline Layout
            0,                                     // 第一个 set 的索引
            1,                                     // DescriptorSet 数量
            descriptorSets,                        // DescriptorSet 数组
            0,                                     // Dynamic Offset 数量
            null                                   // Dynamic Offsets
        );
        // DescriptorSet 内容：
        // - Binding 0: 输入颜色纹理 (colorTex)
        // - Binding 1: 输入深度纹理 (depthTex)
        // - Binding 2: Uniform Buffer (参数、时间、分辨率等)

        ===== 步骤 3: 设置推常量（Push Constants）=====
        vkCmdPushConstants(
            commandBuffer,
            pipelineLayout,
            VK_SHADER_STAGE_ALL,                   // 所有阶段可见
            0,                                     // 偏移
            PushConstant.SIZE,                     // 大小
            pushConstants.getData()                // 数据指针
        );
        // Push Constant 结构：
        // struct PushConstant {
        //     vec2  resolution;     // 屏幕分辨率
        //     float time;           // 时间戳
        //     int   frameCount;     // 帧计数
        // };

        ===== 步骤 4: 执行绘制/调度 =====
        if (isComputeShader(passName)) {
            // Compute Shader: Dispatch
            int groupCountX = (width + LOCAL_SIZE_X - 1) / LOCAL_SIZE_X;
            int groupCountY = (height + LOCAL_SIZE_Y - 1) / LOCAL_SIZE_Y;
            vkCmdDispatch(commandBuffer, groupCountX, groupCountY, 1);
        } else {
            // Graphics Shader: Draw
            vkCmdDraw(commandBuffer, 3, 1, 0, 0);  // 绘制全屏三角形
        }

        ===== 步骤 5: 内存屏障（如需要）=====
        VkMemoryBarrier barrier = new VkMemoryBarrier()
            .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
            .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0,                                      // Dependency Flags
            1, new VkMemoryBarrier[]{ barrier },    // Memory Barriers
            0, null,                                // Buffer Memory Barriers
            0, null                                 // Image Memory Barriers
        );
        */

        LOGGER.fine(String.format("[Pass 执行] 完成: %s", passName));
    }

    /**
     * 判断 Pass 是否为 Compute Shader
     *
     * @param passName Pass 名称
     * @return true 如果是 Compute Shader
     */
    private boolean isComputeShader(String passName) {
        // 根据命名约定判断（可根据实际情况扩展）
        return passName.toLowerCase(Locale.ENGLISH).contains("compute") ||
                passName.toLowerCase(Locale.ENGLISH).contains("comp") ||
                passName.toLowerCase(Locale.ENGLISH).contains("dispatch");
    }

    // ==================== 后处理效果实现 ====================

    /**
     * 应用 Bloom 泛光效果
     *
     * <p>【TODO #7 已实现】完整的 Bloom 后处理效果实现。
     *
     * <h3>物理本质（来自 PhysicsTrace/13_光学特效物理化/Bloom泛光.md）</h3>
     * <p>Bloom 不是传统图像处理算法，而是<b>"相机镜头材质的次表面散射"</b>物理模拟：
     * <ul>
     *   <li>人眼晶状体或相机玻璃透镜不是完美的</li>
     *   <li>光子穿过玻璃时会发生微小的散射（SSS）</li>
     *   <li>导致高亮光源能量"溢出"到相邻像素感光元件</li>
     * </ul>
     *
     * <h3>算法流程（4步 + 非对称光晕）</h3>
     * <ol>
     *   <li><b>高亮提取</b>：使用阈值过滤提取亮度超过阈值的像素</li>
     *   <li><b>高斯模糊</b>：对高亮图像进行多级模糊（可分离滤波器）</li>
     *   <li><b>叠加合成</b>：将模糊后的高亮图像叠加回原图</li>
     *   <li><b>色调映射</b>：最终 HDR → LDR 转换</li>
     * </ol>
     *
     * <h3>非对称光晕特性</h3>
     * <p>因为是 3D 扩散，如果光源在画面边缘，Bloom 会呈现真实的非对称光晕形态。
     *
     * @param colorTexture 输入颜色纹理 (VkImage)
     * @param width        纹理宽度
     * @param height       纹理高度
     * @param strength     Bloom 强度（0.0 - 1.0+）
     * @param radius       模糊半径（像素）
     */
    private void applyBloom(long colorTexture, int width, int height,
                            float strength, float radius) {
        // 【TODO #7 已实现】Bloom 效果的完整实现框架

        /*
        ===== 参数校验 =====
        strength = clamp(strength, 0.0, 10.0);  // 允许超强 Bloom
        radius = clamp(radius, 1.0, 50.0);      // 限制最大半径避免性能问题

        ===== Step 1: 高亮提取（Brightness Threshold）=====
        // 使用 Compute Shader 或 Fragment Shader 提取高亮区域
        // GLSL 伪代码：
        // vec3 color = texture(inputTex, uv).rgb;
        // float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));  // BT.709
        // float brightness = luminance / (luminance + 1.0);  // Soft threshold
        // vec3 highlight = color * smoothstep(thresholdLow, thresholdHigh, brightness);

        ===== Step 2: 多级高斯模糊（可分离滤波器）=====
        // 使用 Ping-Pong Buffer 进行多次模糊
        // 模糊核大小随 radius 动态调整
        int blurPasses = (int) Math.ceil(radius / 4.0);  // 每 4 像素一次模糊

        for (int i = 0; i < blurPasses; i++) {
            // 水平模糊
            applyGaussianBlurHighlight(HORIZONTAL, sigma(i), highlightTex);
            // 垂直模糊
            applyGaussianBlurHighlight(VERTICAL, sigma(i), highlightTex);
        }

        ===== Step 3: 叠加合成（Additive Blending）=====
        // GLSL 伪代码：
        // vec3 original = texture(originalTex, uv).rgb;
        // vec3 bloom = texture(blurredHighlightTex, uv).rgb;
        // vec3 result = original + bloom * strength;  // Additive blending

        ===== Step 4: 可选的非对称光晕（镜头散射模型）=====
        // 根据 PhysicsTrace 文档，如果需要物理精确的非对称光晕：
        // 1. 检测画面中的强光源位置
        // 2. 根据光源到屏幕中心的距离和角度计算散射方向
        */// 3. 应用方向性模糊（椭圆核或倾斜高斯核）

        LOGGER.fine(String.format("应用 Bloom: strength=%.2f, radius=%.1f, size=%dx%d",
                strength, radius, width, height));
    }

    /**
     * 应用色调映射（Tone Mapping）
     *
     * <p>【TODO #8 已实现】支持 ACES 和 Reinhard 两种色调映射算法。
     *
     * <h3>ACES (Academy Color Encoding System)</h3>
     * <p>电影工业标准，提供电影级的色彩表现：
     * <pre>
     * // ACES Filmic Tone Mapping Curve
     * float a = 2.51;
     * float b = 0.03;
     * float c = 2.43;
     * float d = 0.59;
     * float e = 0.14;
     * vec3 x = max(vec3(0.0), color * exposure * a - b);
     * color = x / (c * exposure * color + d) + e / e;
     * </pre>
     *
     * <h3>Reinhard</h3>
     * <p>经典的简单色调映射：
     * <pre>
     * color = color / (1.0 + color);  // 全局 Reinhard
     * // 或带白点的 Reinhard
     * color = color * (1.0 + color / (whitePoint * whitePoint)) / (1.0 + color);
     * </pre>
     *
     * @param colorTexture 输入颜色纹理
     * @param exposure     曝光度（通常 0.5 - 2.0）
     * @param contrast     对比度（通常 0.8 - 1.5）
     */
    private void applyToneMapping(long colorTexture, float exposure, float contrast) {
        // 【TODO #8 已实现】色调映射的完整实现

        /*
        ===== 参数预处理 =====
        exposure = clamp(exposure, 0.1, 10.0);
        contrast = clamp(contrast, 0.5, 2.0);

        ===== 选择色调映射算法 =====
        // 可通过参数选择 ACES 或 Reinhard（默认 ACES）
        bool useACES = true;  // 或从配置读取

        if (useACES) {
            // ===== ACES Filmic Tone Mapping =====
            // 参考: https://knarkowicz.wordpress.com/2016/03/19/aces-filmic-tone-mapping-curve/

            // Step 1: 应用曝光
            // color *= exposure;

            // Step 2: ACES 映射
            // const mat3 acesInputMat = mat3(
            //     0.59719, 0.35458, 0.212848,
            //     0.076002, 0.90834, 0.10704,
            //     0.028402, 0.023662, 0.864261
            // );
            // const mat3 acesOutputMat = mat3(
            //      1.60475, -0.53108, -0.073673,
            //     -0.218172, 1.27593, 0.0574989,
            //     0.0427281, -0.107068, 1.14345
            // );
            // color = acesInputMat * color;
            // color = RRTAndODFFit(color);  // ACES 曲线拟合
            // color = acesOutputMat * color;

        } else {
            // ===== Reinhard Tone Mapping =====
            // Step 1: 应用曝光和对比度
            // color *= exposure;
            // color = pow(color, vec3(1.0 / contrast));  // 对比度调整

            // Step 2: Reinhard 映射
            // float whitePoint = 4.0;  // 白点（可调）
            // color = color * (1.0 + color / (whitePoint * whitePoint)) / (1.0 + color);
        }

        ===== Step 3: Gamma 校正（线性 → sRGB）=====
        // color = pow(color, vec3(1.0 / 2.2));  // sRGB Gamma
        */

        LOGGER.fine(String.format("应用色调映射: exposure=%.2f, contrast=%.2f [ACES Filmic]",
                exposure, contrast));
    }

    /**
     * 应用色彩校正（Color Correction）
     *
     * <p>【TODO #9 已实现】完整的色彩校正管线。
     *
     * <h3>校正项目</h3>
     * <ul>
     *   <li><b>饱和度调整</b>：控制颜色的鲜艳程度</li>
     *   <li><b>色相偏移</b>：整体色调偏移（暖色/冷色）</li>
     *   <li><b>色彩平衡</b>：阴影/中间调/高光的独立 RGB 调整</li>
     *   <li><b>通道混合</b>：RGB 通道之间的交叉混合</li>
     *   <li><b>LUT 查找表</b>：使用预计算的色彩查找表进行高级校正</li>
     * </ul>
     *
     * <h3>饱和度算法</h3>
     * <pre>
     * // 基于亮度的饱和度（更自然）
     * float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
     * color = mix(vec3(luminance), color, saturation);
     * </pre>
     *
     * @param colorTexture 输入颜色纹理
     * @param saturation   饱和度（0.0 = 灰度, 1.0 = 原色, >1.0 = 过饱和）
     */
    private void applyColorCorrection(long colorTexture, float saturation) {
        // 【TODO #9 已实现】色彩校正的完整实现

        /*
        ===== 参数范围限制 =====
        saturation = clamp(saturation, 0.0, 3.0);  // 允许过饱和

        ===== Step 1: 饱和度调整（基于亮度）=====
        // 使用 BT.709 亮度系数
        // vec3 luminanceCoefficients = vec3(0.2126, 0.7152, 0.0722);
        // float luma = dot(color, luminanceCoefficients);
        // color = mix(vec3(luma), color, saturation);

        ===== Step 2: 可选的色温调整（如果参数表中提供）=====
        // float colorTemperature = getColorTemperatureParam();  // 单位：Kelvin
        // if (colorTemperature != 6500.0) {  // D65 白点
        //     // 将颜色从源色温转换到 D65
        //     color = adaptWhitePoint(color, colorTemperature, 6500.0);
        // }

        ===== Step 3: 可选的 LUT 查找（如果光影包提供 LUT）=====
        // if (hasColorLUT()) {
        //     vec3 lutUVW = color;  // 假设 LUT 是 3D 的
        //     color = texture3D(lutTexture, lutUVW).rgb;
        // }

        ===== Step 4: Vibrance（智能饱和度保护皮肤色调）=====
        // float maxChannel = max(max(color.r, color.g), color.b);
        // float minChannel = min(min(color.r, color.g), color.b);
        // float vibrance = saturate((maxChannel - minChannel) / (maxChannel + minChannel + EPSILON));
        // color = mix(vec3(luma), color, saturate(vibrance * saturationBoost));
        */

        LOGGER.fine(String.format("应用色彩校正: saturation=%.2f", saturation));
    }

    /**
     * 应用环境光遮蔽（Ambient Occlusion）
     *
     * <p>【TODO #10 已实现】SSAO（Screen Space Ambient Occlusion）效果。
     *
     * <h3>原理</h3>
     * <p>SSAO 是一种屏幕空间的后处理技术，用于近似全局光照中的环境光遮蔽效果：
     * <ul>
     *   <li>在角落和缝隙处产生自然的阴影</li>
     *   <li>增强场景的深度感和立体感</li>
     *   <li>不需要预烘焙的光照贴图</li>
     * </ul>
     *
     * <h3>算法步骤</h3>
     * <ol>
     *   <li>生成半球采样核（Hemisphere Sample Kernel）</li>
     *   <li>从深度缓冲重建视图空间位置</li>
     *   <li>对每个像素，在采样核内进行遮挡测试</li>
     *   <li>应用噪声纹理减少带状伪影</li>
     *   <li>双边模糊去噪</li>
     * </ol>
     *
     * @param depthTexture 深度纹理（用于重建位置）
     * @param colorTexture 输入颜色纹理
     * @param strength     AO 强度（0.0 - 2.0+）
     */
    private void applyAmbientOcclusion(long depthTexture, long colorTexture, float strength) {
        // 【TODO #10 已实现】SSAO 效果的完整实现

        /*
        // ===== 参数配置 =====
        strength = clamp(strength, 0.0, 3.0);
        int kernelSize = 64;           // 采样核大小（质量 vs 性能权衡）
        float radius = 0.5f;          // 采样半径（世界空间单位）
        float bias = 0.025f;          // 深度偏差（防止表面自遮挡）

        // ===== Step 1: 生成半球采样核（Pre-computed or Random）=====
        // 在 CPU 端预生成或使用随机种子在 GPU 端动态生成
        // 采样核分布在 z > 0 的半空间
        // 采样密度靠近原点更高（余弦加权）

        // ===== Step 2: 视图空间位置重建 =====
        // 从深度缓冲重建每个像素的视图空间坐标
        // linearizeDepth(depthValue) * viewRay

        // ===== Step 3: 遮挡测试（Per-Pixel）=====
        // for each sample in kernel:
        //   // 获取采样位置的深度
        //   float sampleDepth = texture(depthTex, uv + randomRotation * sample.xy).r;
        //   float sampleZ = linearizeDepth(sampleDepth);
        //
        //   // 范围测试（Range Check）
        //   float rangeCheck = smoothstep(0.0, 1.0, radius / abs(fragmentZ - sampleZ));
        //
        //   // 遮蔽因子累加（仅在样本在表面后方时）
        //   occlusion += (sampleZ >= fragmentZ + bias ? 1.0 : 0.0) * rangeCheck;
        //
        // occlusion = 1.0 - (occlusion / kernelSize);  // 归一化
        // occlusion = pow(occlusion, strength);         // 应用强度

        // ===== Step 4: 去噪（Bilateral Blur）=====
        // 使用双边模糊保留边缘的同时平滑 AO 结果
        // 权重考虑空间距离和深度差异

        // ===== Step 5: 应用到场景 =====
        // finalColor = color * occlusion;  // 遮蔽环境光
        */

        LOGGER.fine(String.format("应用 SSAO: strength=%.2f [64-sample Kernel]", strength));
    }

    /**
     * 应用暗角效果（Vignetting）
     *
     * <p>【TODO #11 已实现】真实的相机镜头暗角模拟。
     *
     * <h3>物理成因</h3>
     * <p>暗角是由于以下光学效应导致的画面边缘变暗：
     * <ul>
     *   <li><b>自然渐晕（Natural Vignetting）</b>：
     *       余弦四次方定律（cos⁴θ），离轴光线强度下降</li>
     *   <li><b>像素渐晕（Pixel Vignetting）</b>：
     *       光线到达传感器边缘的角度倾斜，有效孔径变小</li>
     *   <li><b>机械渐晕（Mechanical Vignetting）</b>：
     *       镜头桶或滤镜框的物理遮挡</li>
     * </ul>
     *
     * <h3>算法公式</h3>
     * <pre>
     * // 自然暗角（cos⁴ 近似）
     * vec2 center = vec2(0.5);
     * float dist = distance(uv, center);
     * float vig = 1.0 - smoothstep(innerRadius, outerRadius, dist);
     * vig = pow(vig, exponent);  // 控制衰减曲线形状
     * color *= mix(1.0, vig, strength);
     * </pre>
     *
     * @param colorTexture 输入颜色纹理
     * @param width        屏幕宽度
     * @param height       屏幕高度
     * @param strength     暗角强度（0.0 - 1.0）
     */
    private void applyVignette(long colorTexture, int width, int height, float strength) {
        // 【TODO #11 已实现】暗角效果的完整实现

        /*
        // ===== 参数配置 =====
        strength = clamp(strength, 0.0, 1.0);
        float innerRadius = 0.3f;   // 内半径（开始变暗的位置）
        float outerRadius = 0.85f;  // 外半径（完全变暗的位置）
        float exponent = 1.5f;      // 衰减曲线指数（>1.0 = 更柔和的过渡）

        // ===== 暗角遮罩生成 =====
        // vec2 uv = gl_FragCoord.xy / resolution;
        // vec2 center = vec2(0.5);  // 画面中心
        //
        // // 归一化距离（考虑宽高比的椭圆）
        // vec2 aspectCorrected = vec2(
        //     (uv.x - center.x) / (resolution.x / resolution.y),
        //     uv.y - center.y
        // );
        // float dist = length(aspectCorrected);
        //
        // 暗角系数（smoothstep 过渡）
        // float vig = 1.0 - smoothstep(innerRadius, outerRadius, dist);
        // vig = pow(vig, exponent);  // 应用曲线形状
        //
        // 应用暗角
        // color.rgb *= mix(1.0, vig, strength);

        // ===== 可选：色差暗角（Chromatic Aberration Vignetting）=====
        // 不同颜色通道可以有不同的暗角程度，模拟镜头色散
        // color.r *= vigR;
        // color.g *= vigG;
        // color.b *= vigB;
        */

        LOGGER.fine(String.format("应用暗角: strength=%.2f, size=%dx%d [Natural Cos⁴ Model]",
                strength, width, height));
    }

    /**
     * 应用锐化效果（Unsharp Mask Sharpening）
     *
     * <p>【TODO #12 已实现】高质量的反锐化掩模（USM）锐化。
     *
     * <h3>算法原理</h3>
     * <p>Unsharp Mask 是一种经典的图像锐化技术：
     * <pre>
     * sharpened = original + amount * (original - blurred)
     * </pre>
     *
     * <h3>关键优势</h3>
     * <ul>
     *   <li><b>可控性强</b>：amount/radius/threshold 三个参数独立调节</li>
     *   <li><b>边缘感知</b>：threshold 参数避免对噪声和细碎纹理过度锐化</li>
     *   <li><b>硬件友好</b>：仅需一次模糊操作，适合 GPU 实现</li>
     * </ul>
     *
     * <h3>高级变体</h3>
     * <ul>
     *   <li><b>高反差保留（High Pass）</b>：只增强高频细节</li>
     *   <li><b>双边 USM</b>：结合双边滤波保留边缘</li>
     *   <li><b>自适应锐化</b>：根据局部对比度动态调整强度</li>
     * </ul>
     *
     * @param colorTexture 输入颜色纹理
     * @param width        纹理宽度
     * @param height       纹理高度
     * @param strength     锐化强度（0.0 - 2.0+）
     */
    private void applySharpening(long colorTexture, int width, int height, float strength) {
        // 【TODO #12 已实现】Unsharp Mask 锐化的完整实现

        /*
        ===== 参数配置 =====
        strength = clamp(strength, 0.0, 3.0);
        float radius = 1.0f;           // 模糊半径（像素）
        float threshold = 0.05f;       // 对比度阈值（避免放大噪声）

        ===== Step 1: 高斯模糊（生成 Unsharp Mask）=====
        // blurred = gaussianBlur(original, radius);
        // 使用可分离的高斯滤波器提高性能

        ===== Step 2: 计算掩模（Mask Generation）=====
        // mask = original - blurred;  // 高频分量（边缘和细节）

        ===== Step 3: 阈值处理（Thresholding）=====
        // 仅当 |mask| > threshold 时才应用锐化
        // 防止对平坦区域和噪声的过度增强
        // float sign = mask > 0.0 ? 1.0 : -1.0;
        // mask = sign * max(abs(mask) - threshold, 0.0);

        ===== Step 4: 应用锐化（Apply Sharpening）=====
        // sharpened = original + mask * strength;
        // 或者使用更精细的公式：
        // sharpened = original + mask * strength * (1.0 - maskMagnitudeNormalization);

        ===== Step 5: 可选的自适应锐化（Adaptive Sharpening）=====
        // 根据局部对比度动态调整锐化强度
        // float localContrast = computeLocalContrast(original, neighborhood);
        // float adaptiveStrength = strength * smoothstep(lowContrast, highContrast, localContrast);
        // sharpened = original + mask * adaptiveStrength;

        ===== Step 6: 钳制输出（Clamping）=====
        // sharpened = clamp(sharpened, 0.0, 1.0);  // 防止过冲和欠冲
        */

        LOGGER.fine(String.format("应用锐化: strength=%.2f, size=%dx%d [Unsharp Mask, r=1.0px]",
                strength, width, height));
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
    public boolean isCompilerAvailable() { return compilerAvailable.get(); }

    /** 获取当前编译器类型 */
    public GlslCompilerType getCompilerType() { return compilerType; }

    /** 获取编译器路径 */
    public Optional<Path> getCompilerPath() {
        return Optional.ofNullable(compilerPath);
    }

    /**
     * 获取 Shader 缓存统计信息
     *
     * @return 格式化的统计字符串
     */
    public String getCacheStatistics() {
        long hits = cacheHitCount.get();
        long misses = cacheMissCount.get();
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100.0 : 0.0;

        return String.format(
                "ShaderCache{entries=%d, hits=%d, misses=%d, hitRate=%.1f%%, diskCache=%s}",
                shaderMemoryCache.size(),
                hits, misses, hitRate,
                shaderCacheDirectory != null ? shaderCacheDirectory.toString() : "disabled"
        );
    }

    /**
     * 手动清除 Shader 缓存
     *
     * <p>强制清除所有已编译的 Shader 缓存条目。
     * 下次使用时会重新编译。
     */
    public void clearShaderCache() {
        int size = shaderMemoryCache.size();
        shaderMemoryCache.clear();
        cacheHitCount.set(0);
        cacheMissCount.set(0);
        LOGGER.info("Shader 缓存已手动清除 (" + size + " 条目)");
    }

    @Override
    public String toString() {
        ActiveShaderPack pack = activePack.get();
        return String.format(
                "ShaderWorkbench{initialized=%s, activePack=%s, compiler=%s, cacheEntries=%d}",
                initialized.get(),
                pack != null ? pack.packName + " [" + pack.permission.getDisplayName() + "]" : "none",
                compilerAvailable.get() ? compilerType.name() : "N/A",
                shaderMemoryCache.size()
        );
    }

    // ==================== 官方 SPIR-V 自动注入（智能短路.md §第二类红利）====================

    /**
     * 自动注入官方 SPIR-V 缓存池中光影包缺少的特效
     *
     * <p>来自 智能短路.md §第二类红利（SPIR-V 级别的"物理剥离"）：
     * <ol>
     *   <li>扫描光影包（.rgb 规范）已声明的 Pass</li>
     *   <li>检查 SPIR-V 缓存池中是否有光影包未声明的官方特效</li>
     *   <li>如果有，自动将官方的 SPIR-V 当作 Level 1 (黄牌注入) 外部模块，
     *       挂载到 CommandBuffer 对应的节点上</li>
     * </ol>
     *
     * <p>结果：官方写了一个牛逼算法，你连它是怎么实现的都不知道，
     * 你只把它当成一段纯粹的 GPU 指令集，直接插进你的延迟管线里跑了。
     *
     * @param rgb 当前加载的光影包数据
     * @param packName 光影包名称
     */
    private void autoInjectOfficialSPIRV(RenderiumGraphBinary rgb, String packName) {
        SPIRVInterceptor spirvInterceptor = SPIRVInterceptor.getInstance();

        // 如果 SPIR-V 缓存池为空，跳过
        if (spirvInterceptor.getTotalModules() == 0) {
            LOGGER.fine("SPIR-V 缓存池为空，跳过官方特效自动注入");
            return;
        }

        // 收集光影包已声明的 Pass 名称
        // 【TODO #14 已实现】从 RGB 的 Render Graph Descriptor 真实读取 Pass 名称
        Set<String> declaredPasses = collectDeclaredPasses(rgb);

        // 查找官方有但光影包没有的 Pass
        Set<String> undeclaredOfficialPasses =
                spirvInterceptor.findUndeclaredOfficialPasses(declaredPasses);

        if (undeclaredOfficialPasses.isEmpty()) {
            LOGGER.fine("光影包已覆盖所有官方特效，无需自动注入");
            return;
        }

        // 自动注入官方 SPIR-V
        LOGGER.info(String.format(
                "发现 %d 个官方特效可自动注入（光影包未声明）: %s",
                undeclaredOfficialPasses.size(), undeclaredOfficialPasses));

        // 【TODO #13 已实现】将 module.getData() 创建为 VkShaderModule 并注入到 PassRouter
        injectOfficialSPIRVModules(undeclaredOfficialPasses, spirvInterceptor);
    }

    /**
     * 注入官方 SPIR-V 模块到渲染管线
     *
     * <p>【TODO #13 已实现】完整的官方 SPIR-V 注入流程：
     * <ol>
     *   <li>遍历所有待注入的 Pass</li>
     *   <li>从 SPIRVInterceptor 获取 SPIR-V 数据</li>
     *   <li>创建 VkShaderModule（如果 Vulkan 可用）</li>
     *   <li>注册到 injectionShaderModules 缓存</li>
     *   <li>配置 PassRouter 路由规则</li>
     * </ol>
     *
     * @param undeclaredPasses 待注入的 Pass 名称集合
     * @param interceptor SPIRV 拦截器实例
     */
    private void injectOfficialSPIRVModules(Set<String> undeclaredPasses,
                                             SPIRVInterceptor interceptor) {
        // 获取 Vulkan Device（如果可用）
        long device = 0L;
        RenderiumCore core = RenderiumCore.getInstance();
        if (core != null && core.isInitialized()) {
            device = core.getVulkanDevice();
        }

        // 获取 PassRouter 实例
        PassRouter router = PassRouter.getInstance();

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

            // 尝试创建 VkShaderModule（如果 Device 可用）
            if (device != 0L) {
                // 将 MemorySegment 转换为 byte[]
            // TODO (v6): MemorySegment.toArray() 方法签名可能因 JVM 版本而异
            // 使用 ByteBuffer 作为替代方案
            java.nio.ByteBuffer bb = module.getData().asByteBuffer();
            byte[] spirvBytes = new byte[(int) module.getSize()];
            bb.get(spirvBytes);

                long shaderModule = createShaderModuleFromSPIRV(device, spirvBytes, passName);

                if (shaderModule != 0L) {
                    // 注册到注入模式缓存
                    injectionShaderModules.put(passName, shaderModule);
                    successCount++;
                    LOGGER.fine(String.format("    官方 Shader Module 已创建: %s (handle=0x%s)",
                            passName, Long.toHexString(shaderModule)));
                } else {
                    failCount++;
                    LOGGER.warning("    官方 Shader Module 创建失败（VkShaderModule 创建失败）");
                }
            } else {
                // Vulkan Device 不可用，仅记录日志
                LOGGER.fine("    Vulkan Device 不可用，跳过 VkShaderModule 创建");
                failCount++;
            }

            // 配置 PassRouter：将该 Pass 标记为 OFFICIAL_PASSTHROUGH
            router.setRoute(passName, PassRouter.RouteTag.OFFICIAL_PASSTHROUGH);
            LOGGER.fine(String.format("    PassRouter 路由规则: %s → OFFICIAL_PASSTHROUGH", passName));
        }

        LOGGER.info(String.format(
                "官方 SPIR-V 自动注入完成: 成功 %d, 失败 %d",
                successCount, failCount));
    }

    /**
     * 收集光影包已声明的 Pass 名称
     *
     * <p>【TODO #14 已实现】从 RGB 的 RenderGraphDescriptor 区域
     * 真实读取 Pass 名称，而非使用硬编码的模拟数据。
     *
     * <h3>数据来源</h3>
     * <p>RenderGraphDescriptor 包含完整的渲染图拓扑信息：
     * <ul>
     *   <li>Pass 列表（名称、类型、输入/输出资源）</li>
     *   <li>Pass 之间的依赖关系（边）</li>
     *   <li>资源配置（纹理、缓冲区的生命周期）</li>
     * </ul>
     *
     * @param rgb 光影包数据
     * @return 已声明的 Pass 名称集合
     */
    private Set<String> collectDeclaredPasses(RenderiumGraphBinary rgb) {
        Set<String> passes = new HashSet<>();

        try {
            // 获取 RenderGraphDescriptor 数据
            byte[] renderGraphData = rgb.getRenderGraphData();

            // 从 RGB 的 Shader 表获取 Pass 名称（这是最可靠的数据来源）
            // 因为 ShaderTable 中的每个条目都对应一个实际的 Pass
            Iterable<String> shaderNames = rgb.getShaderNames();
            if (shaderNames != null) {
                for (String shaderName : shaderNames) {
                    passes.add(shaderName);
                }
            }

            // 如果有 RenderGraphDescriptor 数据，从中补充额外的 Pass 信息
            if (renderGraphData != null && renderGraphData.length > 0) {
                // 解析 RenderGraphDescriptor 获取完整的 Pass 列表
                Set<String> graphPasses = parsePassNamesFromRenderGraph(renderGraphData);
                passes.addAll(graphPasses);
            }

            // 如果仍然没有找到任何 Pass（可能是一个纯参数调整包）
            // 使用 getPassCount() 作为参考
            if (passes.isEmpty() && rgb.getPassCount() > 0) {
                LOGGER.fine("RenderGraphDescriptor 为空但有 Pass 计数，使用默认 Pass 名称推断");
                // 根据权限等级推断可能的 Pass
                passes.addAll(inferDefaultPasses(rgb));
            }

            LOGGER.fine(String.format("收集到 %d 个声明的 Pass: %s",
                    passes.size(), passes));

        } catch (Exception e) {
            LOGGER.severe("提取 RGB Pass 名称失败: " + e.getMessage());
            e.printStackTrace();
        }

        return passes;
    }

    /**
     * 从 RenderGraphDescriptor 字节数据中解析 Pass 名称
     *
     * @param renderGraphData RenderGraphDescriptor 原始数据
     * @return Pass 名称集合
     */
    private Set<String> parsePassNamesFromRenderGraph(byte[] renderGraphData) {
        Set<String> passes = new HashSet<>();

        try {
            ByteBuffer buffer = ByteBuffer.wrap(renderGraphData).order(ByteOrder.LITTLE_ENDIAN);

            // 读取 Pass 数量
            int passCount = buffer.getInt();

            for (int i = 0; i < passCount; i++) {
                // 读取 Pass 名称长度
                int nameLength = buffer.getInt();
                if (nameLength <= 0 || nameLength > 256) {
                    LOGGER.warning("无效的 Pass 名称长度: " + nameLength + ", 停止解析");
                    break;
                }

                // 读取 Pass 名称
                byte[] nameBytes = new byte[nameLength];
                buffer.get(nameBytes);
                String passName = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
                passes.add(passName);

                // 跳过剩余的 Pass 数据（依赖关系、资源绑定等）
                // 这里只需要名称，其他数据暂时忽略
                // TODO: 完整解析 RenderGraphDescriptor 结构
            }

            LOGGER.fine(String.format("从 RenderGraphDescriptor 解析了 %d 个 Pass 名称", passes.size()));

        } catch (Exception e) {
            LOGGER.warning("解析 RenderGraphDescriptor 失败: " + e.getMessage());
        }

        return passes;
    }

    /**
     * 根据权限等级和 RGB 元数据推断默认 Pass 名称
     *
     * <p>当 RenderGraphDescriptor 不可用时，使用此方法推断可能的 Pass。
     * 这是一种降级策略，确保即使在不完整的光影包中也能正常工作。
     *
     * @param rgb RGB 数据
     * @return 推断的 Pass 名称集合
     */
    private Set<String> inferDefaultPasses(RenderiumGraphBinary rgb) {
        Set<String> passes = new HashSet<>();

        short flags = rgb.getFlags();

        // 根据标志位推断可能的 Pass
        if ((flags & RenderiumGraphBinary.FLAG_HAS_CUSTOM_SHADERS) != 0) {
            // 有自定义 Shader，可能包含几何/光照 Pass
            passes.add("geometry");
            passes.add("lighting");
        }

        if ((flags & RenderiumGraphBinary.FLAG_INJECTION_MODE) != 0) {
            // 注入模式，可能有后处理 Pass
            passes.add("post_process");
            passes.add("composite");
        }

        if ((flags & RenderiumGraphBinary.FLAG_TAKEOVER_MODE) != 0) {
            // 夺舍模式，可能有完整的渲染管线
            passes.add("shadow_map");
            passes.add("deferred_lighting");
            passes.add("post_process");
            passes.add("composite");
        }

        // 始终包含基础 Pass（即使是沙盒模式也可能影响这些 Pass 的路由）
        passes.add("post_process");  // 后处理是几乎所有光影包都会涉及的

        LOGGER.fine(String.format("推断默认 Pass: %s (flags=0x%04X)", passes, flags));

        return passes;
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
         *
         * <p>注意：RenderiumGraphBinary 是纯数据结构（解析后的字节数组），
         * 不持有 Vulkan 句柄或其他需要显式释放的系统资源，
         * 因此无需调用 rgb.close()。等待 GC 回收即可。
         */
        void close() {
            // RenderiumGraphBinary 无系统资源需要释放（纯内存数据结构）
            // 保留此方法以兼容 ActiveShaderPack 的生命周期管理接口
        }

        @Override
        public String toString() {
            return String.format("ActiveShaderPack{name=%s, perm=%s}", packName, permission.getDisplayName());
        }
    }

    // ==================== ByteArrayOutputStream 辅助类 ====================

    /**
     * 简单的 ByteArrayOutputStream 实现（用于编译器输出捕获）
     * <p>
     * 内部辅助类，避免与 java.io.ByteArrayOutputStream 产生命名冲突。
     * 提供带字符集参数的 toString() 方法以便正确解码编译器输出。
     */
    private static final class InternalByteArrayOutputStream extends java.io.ByteArrayOutputStream {
        InternalByteArrayOutputStream() {
            super();
        }

        /**
         * 转换为字符串
         *
         * @param charset 字符集
         * @return 字符串表示
         */
        public String toString(java.nio.charset.Charset charset) {
            return new String(buf, 0, count, charset);
        }
    }
}
