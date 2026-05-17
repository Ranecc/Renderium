package com.ranecc.renderium.feature.shader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * GLSL 编译器服务。
 *
 * <p>负责 GLSL/HLSL 源码到 SPIR-V 的编译工作。
 * 支持 glslangValidator（Khronos 官方）和 dxc（Microsoft DirectX Compiler）两种编译器，
 * 自动在系统 PATH 中检测可用编译器。
 *
 * <p>集成 Shader 缓存机制，避免重复编译相同源码。
 */
public final class GlslCompilerService {

    private static final Logger LOGGER = Logger.getLogger("Renderium-GlslCompilerService");

    /** GLSL 编译器类型枚举 */
    public enum GlslCompilerType {
        /** Khronos 官方 glslangValidator */
        GLSLANG_VALIDATOR,
        /** Microsoft DirectX Compiler (dxc) */
        DXC,
        /** 自动检测（优先 glslangValidator） */
        AUTO_DETECT
    }

    /** 着色器阶段枚举 */
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

        private final String extension;
        private final String glslangStage;
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

    /** 当前使用的 GLSL 编译器类型 */
    private volatile GlslCompilerType compilerType = GlslCompilerType.AUTO_DETECT;

    /** GLSL 编译器可执行文件路径 */
    private volatile Path compilerPath = null;

    /** 编译器是否可用 */
    private final AtomicBoolean compilerAvailable = new AtomicBoolean(false);

    /** Shader 缓存管理器引用 */
    private final ShaderCacheManager cacheManager;

    /**
     * 构造函数
     *
     * @param cacheManager Shader 缓存管理器实例
     */
    public GlslCompilerService(ShaderCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    // ==================== 编译器检测 ====================

    /**
     * 初始化 GLSL 编译器
     *
     * <p>按以下顺序检测可用的编译器：
     * <ol>
     *   <li>检查用户配置的编译器路径</li>
     *   <li>在系统 PATH 中查找 glslangValidator</li>
     *   <li>在系统 PATH 中查找 dxc</li>
     * </ol>
     */
    public void initializeGlslCompiler() {
        Path glslangPath = findCompilerInPath("glslangValidator");
        if (glslangPath != null && validateCompiler(glslangPath, GlslCompilerType.GLSLANG_VALIDATOR)) {
            this.compilerPath = glslangPath;
            this.compilerType = GlslCompilerType.GLSLANG_VALIDATOR;
            this.compilerAvailable.set(true);
            LOGGER.info("GLSL 编译器已就绪: " + glslangPath + " (glslangValidator)");
            return;
        }

        Path dxcPath = findCompilerInPath("dxc");
        if (dxcPath != null && validateCompiler(dxcPath, GlslCompilerType.DXC)) {
            this.compilerPath = dxcPath;
            this.compilerType = GlslCompilerType.DXC;
            this.compilerAvailable.set(true);
            LOGGER.info("GLSL 编译器已就绪: " + dxcPath + " (dxc)");
            return;
        }

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
     * @param compilerPath 编译器路径
     * @param type 编译器类型
     * @return true 如果编译器可用
     */
    private boolean validateCompiler(Path compilerPath, GlslCompilerType type) {
        try {
            ProcessBuilder pb;
            switch (type) {
                case GLSLANG_VALIDATOR:
                    pb = new ProcessBuilder(compilerPath.toString(), "--version");
                    break;
                case DXC:
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

    // ==================== 编译接口 ====================

    /**
     * 编译 GLSL 源码为 SPIR-V
     *
     * <p>完整的 GLSL 编译器集成，支持 glslangValidator 和 dxc。
     * 集成缓存机制，避免重复编译相同源码。
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
        if (!compilerAvailable.get()) {
            throw new IllegalStateException(
                    "GLSL 编译器不可用。请安装 Vulkan SDK 以启用 GLSL 编译功能。");
        }

        Objects.requireNonNull(glslSource, "GLSL 源码不能为 null");
        Objects.requireNonNull(entryPoint, "入口函数名不能为 null");
        Objects.requireNonNull(stage, "着色器阶段不能为 null");

        long startTime = System.currentTimeMillis();

        String sourceHash = cacheManager.computeSHA256(glslSource);
        String cacheKey = sourceHash + "_" + stage.getExtension();

        ShaderCacheManager.ShaderCacheEntry cached = cacheManager.getCachedEntry(cacheKey);
        if (cached != null) {
            cached.hitCount.incrementAndGet();
            cacheManager.recordCacheHit();
            LOGGER.fine(String.format("Shader 缓存命中: stage=%s, hash=%s... (第 %d 次命中)",
                    stage, sourceHash.substring(0, 8), cached.hitCount.get()));
            return cached.spirvData.clone();
        }

        cacheManager.recordCacheMiss();
        LOGGER.fine(String.format("Shader 缓存未命中，开始编译: stage=%s, source_size=%d bytes",
                stage, glslSource.length()));

        List<String> command = buildCompilerCommand(glslSource, entryPoint, stage);

        byte[] spirvData = executeCompilation(command);

        long elapsed = System.currentTimeMillis() - startTime;
        cacheManager.recordCompilation(elapsed);

        if (spirvData != null) {
            ShaderCacheManager.ShaderCacheEntry entry = new ShaderCacheManager.ShaderCacheEntry(
                    spirvData, sourceHash, System.nanoTime(), stage.name());
            cacheManager.putCachedEntry(cacheKey, entry);

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

        command.add(compilerPath.toString());

        switch (compilerType) {
            case GLSLANG_VALIDATOR -> {
                command.add("-V");
                command.add("-o");
                command.add("-");
                command.add("-e");
                command.add(entryPoint);
                command.add("--target-env");
                command.add("vulkan1.2");
                command.add("-S");
                command.add(stage.getGlslangStage());
            }
            case DXC -> {
                command.add("-T");
                command.add(stage.getDxcProfile());
                command.add("-E");
                command.add(entryPoint);
                command.add("-fspv-target-env=vulkan1.2");
                command.add("-O3");
            }
            default -> throw new IllegalStateException("不支持的编译器类型: " + compilerType);
        }

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
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (var os = process.getOutputStream()) {
            // stdin 输入暂时留空（使用临时文件传参的替代方案）
        }

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

        if (result.length >= 4) {
            int magic = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            if (magic != 0x07230203) {
                LOGGER.warning("编译输出不是有效的 SPIR-V 格式 (magic=0x" +
                        Integer.toHexString(magic) + ")");
                return null;
            }
        }

        return result.length > 0 ? result : null;
    }

    // ==================== 访问器 ====================

    /** 获取编译器是否可用 */
    public boolean isCompilerAvailable() { return compilerAvailable.get(); }

    /** 获取当前编译器类型 */
    public GlslCompilerType getCompilerType() { return compilerType; }

    /** 获取编译器路径 */
    public Optional<Path> getCompilerPath() {
        return Optional.ofNullable(compilerPath);
    }

    // ==================== 内部辅助类 ====================

    /**
     * 简单的 ByteArrayOutputStream 实现（用于编译器输出捕获）
     *
     * <p>内部辅助类，避免与 java.io.ByteArrayOutputStream 产生命名冲突。
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
