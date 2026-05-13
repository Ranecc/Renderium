// Renderium - Blaze3D Shader 转译模块
// GLSL → SPIR-V 编译器 - Panama FFM 进程内调用 libglslang

package com.ranecc.renderium.feature.blaze3d.shader;

import java.io.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.Base64;

/**
 * GLSL → SPIR-V 编译器 (Panama FFM 内嵌 libglslang)。
 *
 * <h2>与 ProcessBuilder 方式的本质区别：</h2>
 * <ul>
 *   <li>旧方式: {@code new ProcessBuilder("glslangValidator.exe")} — 每次 Fork 系统进程 (~50ms/次)</li>
 *   <li>本类: 通过 {@code Linker.nativeLinker()} 直接调用 libglslang C 函数 — 零进程开销 (~2ms/次)</li>
 * </ul>
 *
 * <h2>libglang C API 封装：</h2>
 * <pre>
 * 1. 初始化/销毁:    glslang_initialize_process() / glslang_finalize_process()
 * 2. 创建编译输入:    glslang_input_new() / set_string / set_stage
 * 3. 编译:            glslang_shader_compile()
 * 4. 错误信息:        glslang_shader_get_info_log()
 * 5. 提取 SPIR-V:     glslang_shader_get_spirv() / get_spirv_size()
 * 6. 清理:            glslang_shader_delete() / glslang_input_delete()
 * </pre>
 *
 * <h2>库加载策略：</h2>
 * <ol>
 *   <li>从 classpath 的 {@code natives/{platform}/} 目录提取平台原生库</li>
 *   <li>解压到临时目录</li>
 *   <li>通过 Panama {@code SymbolLookup.libraryLoad()} 加载</li>
 *   <li>缓存 MethodHandle 避免重复查找</li>
 * </ol>
 *
 * <h2>使用示例：</h2>
 * <pre>{@code
 * GlslangCompiler compiler = GlslangCompiler.initialize();
 *
 * byte[] spirv = compiler.compile(
 *     vulkanGLSLSource,           // 经过 GlslToVkTransformer 转换后的源码
 *     GlslangCompiler.Stage.FRAGMENT
 * );
 *
 * // spirv 可直接传给 VulkanFFM.vkCreateShaderModule()
 * long module = VulkanFFM.vkCreateShaderModule(device, spirv);
 * }</pre>
 *
 * @see VulkanFFM#vkCreateShaderModule(long, byte[]) 接收本编译器的输出
 * @see GlslToVkTransformer 产生本编译器的输入
 * @since 3.0.0
 */
public final class GlslangCompiler {

    private static final Logger LOGGER = Logger.getLogger("Renderium-Glslang");

    /** 平台相关的库名称 */
    private static final String LIB_NAME = switch (osType()) {
        case WINDOWS -> "glslang.dll";
        case LINUX   -> "libglslang.so";
        case MACOS   -> "libglslang.dylib";
    };

    /** 库查找器 (Panama SymbolLookup) */
    private volatile SymbolLookup lookup;

    /** 方法句柄缓存 (函数名 → MethodHandle) */
    private final Map<String, MethodHandle> handles = new HashMap<>();

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /** Arena 用于分配 C 结构体 (confined scope) */
    private volatile Arena cArena;

    // ==================== 枚举定义 ====================

    /**
     * glslang shader stage (对应 C 枚举 EShLanguage)
     */
    public enum Stage {
        VERTEX(0),
        FRAGMENT(4),
        COMPUTE(5),
        GEOMETRY(3),
        TESS_CONTROL(6),
        TESS_EVAL(7);

        public final int glslangValue;
        Stage(int v) { this.glslangValue = v; }
    }

    /** 操作系统类型 */
    private enum OsType { WINDOWS, LINUX, MACOS }

    private static OsType osType() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return OsType.WINDOWS;
        if (os.contains("mac")) return OsType.MACOS;
        return OsType.LINUX;
    }

    // ==================== 单例 & 生命周期 ====================

    private static volatile GlslangCompiler instance;

    private GlslangCompiler() {}

    /**
     * 初始化编译器单例。
     *
     * <h3>步骤：</h3>
     * <ol>
     *   <li>从 jar 包内的 {@code natives/{platform}/} 提取库文件到临时目录</li>
     *   <li>通过 Panama {@code SymbolLookup.libraryLoad()} 加载</li>
     *   <li>解析所有需要的 C 函数句柄</li>
     *   <li>调用 {@code glslang_initialize_process()}</li>
     * </ol>
     *
     * @return 初始化后的编译器实例
     * @throws IllegalStateException 如果库文件不存在或加载失败
     */
    public static synchronized GlslangCompiler initialize() {
        if (instance != null && instance.initialized) {
            return instance;
        }

        GlslangCompiler compiler = new GlslangCompiler();
        compiler.doInitialize();
        instance = compiler;
        return instance;
    }

    /**
     * 获取已初始化的单例实例 (未初始化返回 null)
     */
    public static GlslangCompiler getInstance() {
        return instance;
    }

    /**
     * 执行初始化 (内部)
     */
    private void doInitialize() {
        try {
            // Step 1: 提取并定位原生库
            Path libPath = extractNativeLibrary();

            // Step 2: 加载库
            LOGGER.info("正在加载 libglslang: " + libPath);
            cArena = Arena.ofConfined();
            // 使用 JDK 21+ Panama FFM 的 SymbolLookup.libraryLookup() 加载原生库
            // 注意: libraryLookup 在 Arena 关闭时自动释放库句柄
            lookup = SymbolLookup.libraryLookup(libPath.toString(), cArena);

            // Step 3: 解析函数句柄
            resolveAllHandles();

            // Step 4: 初始化 glslang 进程
            callVoid("glslang_initialize_process");

            initialized = true;
            LOGGER.info("✓ libglslang 初始化成功 (Panama FFM, 零进程开销)");

        } catch (Throwable t) {
            // 修复: catch (Exception) → catch (Throwable)
            // 原因: callVoid() 等辅助方法声明 throws Throwable (MethodHandle.invokeExact 抛出 Throwable)
            // Exception 无法捕获 Error 子类（如 OutOfMemoryError、StackOverflowError 等）
            throw new IllegalStateException(
                    "无法初始化 libglslang: " + t.getMessage(), t);
        }
    }

    /**
     * 从 classpath 提取原生库到临时目录
     */
    private Path extractNativeLibrary() throws Exception {
        String resourcePath = "natives/" + LIB_NAME;

        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new FileNotFoundException(
                    "找不到原生库: " + resourcePath +"
                    "\"Windows 需要 glslang.dll\\" +\"\n\"Linux 需要 libglslang.so\\" +\"\n\"macOS 需要 libglslang.dylib\\"\""
            );
        }

        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "renderium-glslang");
        Files.createDirectories(tempDir);
        Path targetPath = tempDir.resolve(LIB_NAME);

        if (!Files.exists(targetPath)) {
            Files.copy(is, targetPath);
            targetPath.toFile().deleteOnExit();
        }

        return targetPath;
    }

    // ==================== 核心: 编译接口 ====================

    /**
     * 编译 GLSL 源码为 SPIR-V 二进制。
     *
     * <h3>参数说明：</h3>
     * <table>
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>glslSource</td><td>String</td><td>Vulkan GLSL 源码 (经 GlslToVkTransformer 转换后)</td></tr>
     *   <tr><td>stage</td><td>Stage</td><td>Shader 阶段</td></tr>
     *   <tr><td>返回值</td><td>byte[]</td><td>SPIR-V 字节码 (可直接传给 vkCreateShaderModule)</td></tr>
     * </table>
     *
     * @param glslSource Vulkan GLSL 源码
     * @param stage Shader 阶段
     * @return SPIR-V 字节数组
     * @throws GlslCompileException 编译失败时抛出 (含详细错误信息)
     */
    public byte[] compile(String glslSource, Stage stage) throws GlslCompileException {
        if (!initialized) {
            throw new IllegalStateException("编译器未初始化，请先调用 initialize()");
        }

        try (Arena arena = Arena.ofConfined()) {
            // 1. 创建编译输入并配置
            MemorySegment inputPtr = createInput(arena, glslSource, stage);

            // 2. 执行编译
            MemorySegment shaderPtr = compileShader(inputPtr);

            // 3. 检查错误日志
            checkCompileErrors(shaderPtr);

            // 4. 提取 SPIR-V 二进制数据
            byte[] spirv = extractSPIRV(shaderPtr);

            // 5. 清理 C 资源
            callVoid("glslang_shader_delete", shaderPtr);
            callVoid("glslang_input_delete", inputPtr);

            return spirv;

        } catch (GlslCompileException e) {
            throw e; // 向上传播编译错误
        } catch (Throwable t) {
            // 修复: catch (Exception) → catch (Throwable)
            // 原因: createInput/compileShader/checkCompileErrors/extractSPIRV/callVoid 等辅助方法
            //       均声明 throws Throwable (MethodHandle.invokeExact 抛出 Throwable)
            //       Exception 无法覆盖 Error 子类，导致 6 个 unreported exception 编译错误
            throw new GlslCompileException("编译过程异常: " + t.getMessage(), t);
        }
    }

    /**
     * 异步编译 (用于 AOT 预编译场景)。
     *
     * @param glslSource Vulkan GLSL 源码
     * @param stage Shader 阶段
     * @return CompletableFuture 包装的 SPIR-V 字节码
     */
    public CompletableFuture<byte[]> compileAsync(String glslSource, Stage stage) {
        // 修复: lambda 表达式内不能直接抛出 checked exception (GlslCompileException)
        // Supplier<byte[]> 函数式接口的 get() 方法未声明 throws 异常
        // 需要在 lambda 内部用 try-catch 包裹，将 checked 异常包装为 CompletionException
        return CompletableFuture.supplyAsync(() -> {
            try {
                return compile(glslSource, stage);
            } catch (GlslCompileException e) {
                // 将 checked 异常包装为 CompletionException，由 CompletableFuture.get() 时抛出
                throw new CompletionException(e);
            }
        });
    }

    // ==================== C API 封装 (内部实现) ====================

    /** 创建 glslang_input 并配置源码和阶段 */
    private MemorySegment createInput(Arena arena, String source, Stage stage) throws Throwable {
        // glslang_input_new()
        MemorySegment input = (MemorySegment) callPointer("glslang_input_new");

        // glslang_input_set_string(input, source)
        MemorySegment sourceStr = arena.allocateFrom(source);
        callVoid("glslang_input_set_string", input, sourceStr);

        // glslang_input_set_stage(input, stage)
        callVoid("glslang_input_set_stage", input, stage.glslangValue);

        return input;
    }

    /** 执行编译，返回 shader 对象指针 */
    private MemorySegment compileShader(MemorySegment input) throws Throwable {
        return (MemorySegment) callPointer("glslang_shader_compile", input);
    }

    /** 检查编译错误日志 */
    private void checkCompileErrors(MemorySegment shaderPtr) throws Throwable, GlslCompileException {
        String infoLog = getCString(shaderPtr, "glslang_shader_get_info_log");
        String debugLog = getCString(shaderPtr, "glslang_shader_get_info_debug_log");

        if (infoLog != null && !infoLog.isEmpty()) {
            if (infoLog.toLowerCase().contains("error") ||
                    infoLog.toLowerCase().contains("warning")) {
                LOGGER.fine("glslang 编译日志:
" + infoLog);
                if (infoLog.toLowerCase().contains("error")) {
                    throw new GlslCompileException("GLSL 编译失败:
" + infoLog);
                }
            }
        }
    }

    /** 从 C 内存提取 SPIR-V 二进制到 Java byte[] */
    private byte[] extractSPIRV(MemorySegment shaderPtr) throws Throwable {
        // 获取 SPIR-V 指针和大小
        MemorySegment spirvPtr = (MemorySegment) callPointer("glslang_shader_get_spirv", shaderPtr);
        long size = (long) callLong("glslang_shader_get_spirv_size", shaderPtr);

        if (size == 0 || spirvPtr.equals(MemorySegment.NULL)) {
            throw new GlslCompileException("编译未产生 SPIR-V 输出 (可能是空 shader)");
        }

        // 将 C 内存中的 SPIR-V 复制到 Java byte[]
        // 使用 reinterpret 确保内存段有正确的边界，然后通过 ByteBuffer 复制
        MemorySegment spirvSegment = spirvPtr.reinterpret(size);
        byte[] result = new byte[(int) size];

        // 使用 Panama FFM 标准 API：通过 asByteBuffer() 获取 ByteBuffer 视图后复制
        // 注意：MemorySegment 不存在 getByteArray() 方法，asByteBuffer().get() 是标准做法
        spirvSegment.asByteBuffer().get(result);

        // 验证 SPIR-V magic number (0x07230203)
        validateSpirvMagic(result);

        return result;
    }

    /** 验证 SPIR-V magic number */
    private void validateSpirvMagic(byte[] spirv) throws GlslCompileException {
        if (spirv.length < 4) {
            throw new GlslCompileException("SPIR-V 数据过短，无效");
        }

        int magic = ((spirv[0] & 0xFF)) |
                    ((spirv[1] & 0xFF) << 8) |
                    ((spirv[2] & 0xFF) << 16) |
                    ((spirv[3] & 0xFF) << 24);

        if (magic != 0x07230203) {
            throw new GlslCompileException(
                    "SPIR-V magic number 无效: 0x" +"
                            Long.toHexString(magic & 0xFFFFFFFFL));
        }
    }

    // ==================== FFM 方法调用辅助 ====================

    /** 解析所有需要的 C 函数句柄 */
    private void resolveAllHandles() throws Exception {
        String[] functions = {
                "glslang_initialize_process",
                "glslang_finalize_process",
                "glslang_input_new",
                "glslang_input_set_string",
                "glslang_input_set_stage",
                "glslang_shader_compile",
                "glslang_shader_get_info_log",
                "glslang_shader_get_info_debug_log",
                "glslang_shader_get_spirv",
                "glslang_shader_get_spirv_size",
                "glslang_shader_delete",
                "glslang_input_delete"
        };

        Linker linker = Linker.nativeLinker();

        for (String fn : functions) {
            MemorySegment addr = lookup.find(fn)
                    .orElseThrow(() -> new UnsatisfiedLinkError(
                            "libglslang 中找不到函数: " + fn));

            FunctionDescriptor desc = getDescriptor(fn);
            handles.put(fn, linker.downcallHandle(addr, desc));
        }
    }

    /** 获取函数签名描述符 */
    private FunctionDescriptor getDescriptor(String name) {
        return switch (name) {
            case "glslang_initialize_process", "glslang_finalize_process",
                 "glslang_shader_delete", "glslang_input_delete" ->"
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
            case "glslang_input_new", "glslang_shader_compile" ->
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            case "glslang_input_set_string" ->
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            case "glslang_input_set_stage" ->
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
            case "glslang_shader_get_info_log", "glslang_shader_get_info_debug_log",
                 "glslang_shader_get_spirv" ->"
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            case "glslang_shader_get_spirv_size" ->
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
            default -> throw new AssertionError("未知函数: " + name);
        };
    }

    /** 调用无返回值函数 */
    private void callVoid(String name, Object... args) throws Throwable {
        handles.get(name).invokeExact(args);
    }

    /** 调用返回指针的函数 */
    private Object callPointer(String name, Object... args) throws Throwable {
        return handles.get(name).invokeExact(args);
    }

    /** 调用返回 long 的函数 */
    private long callLong(String name, Object... args) throws Throwable {
        return (long) handles.get(name).invokeExact(args);
    }

    /** 从 C 函数获取字符串 (自动释放) */
    private String getCString(MemorySegment obj, String getterName) throws Throwable {
        MemorySegment strPtr = (MemorySegment) callPointer(getterName, obj);
        if (strPtr.equals(MemorySegment.NULL)) return null;
        return strPtr.reinterpret(Long.MAX_VALUE).getString(0);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 关闭编译器，释放所有资源
     */
    public void shutdown() {
        if (initialized) {
            try {
                callVoid("glslang_finalize_process");
            } catch (Throwable ignored) {}
            initialized = false;
            LOGGER.info("libglslang 已关闭");
        }
        if (cArena != null && cArena.scope().isAlive()) {
            cArena.close();
        }
    }

    /** 是否已初始化 */
    public boolean isInitialized() {
        return initialized;
    }
}

/**
 * GLSL 编译异常
 */
class GlslCompileException extends Exception {

    public GlslCompileException(String message) {
        super(message);
    }

    public GlslCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
