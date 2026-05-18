package com.ranecc.renderium.feature.blaze3d.shader;

import java.io.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public final class GlslangCompiler {

    private static final Logger LOGGER = Logger.getLogger("Renderium-Glslang");

    private static final String LIB_NAME = switch (osType()) {
        case WINDOWS -> "glslang.dll";
        case LINUX   -> "libglslang.so";
        case MACOS   -> "libglslang.dylib";
    };

    private volatile SymbolLookup lookup;
    private final Map<String, MethodHandle> handles = new HashMap<>();
    private volatile boolean initialized = false;
    private volatile Arena cArena;

    // ==================== 枚举定义 ====================

    public enum Stage {
        VERTEX(0),
        TESSCONTROL(1),
        TESSEVALUATION(2),
        GEOMETRY(3),
        FRAGMENT(4),
        COMPUTE(5);

        public final int glslangValue;
        Stage(int v) { this.glslangValue = v; }
    }

    public enum SourceLanguage {
        GLSL(0),
        HLSL(1);

        public final int apiValue;
        SourceLanguage(int v) { this.apiValue = v; }
    }

    private enum OsType { WINDOWS, LINUX, MACOS }

    private static OsType osType() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return OsType.WINDOWS;
        if (os.contains("mac")) return OsType.MACOS;
        return OsType.LINUX;
    }

    // ==================== 常量（glslang_c_interface.h 枚举值） ====================

    private static final int GLSLANG_SOURCE_GLSL = 0;
    private static final int GLSLANG_SOURCE_HLSL = 1;
    private static final int GLSLANG_CLIENT_VULKAN = 1;
    private static final int GLSLANG_TARGET_VULKAN_1_1 = 0x004000;
    private static final int GLSLANG_TARGET_VULKAN_1_2 = 0x004001;
    private static final int GLSLANG_TARGET_SPV = 1;
    private static final int GLSLANG_TARGET_SPV_1_5 = 0x010500;
    private static final int GLSLANG_NO_PROFILE = 0;
    private static final int GLSLANG_MSG_DEFAULT_BIT = 0;

    // ==================== Struct 偏移常量（x64） ====================

    private static final int INPUT_LANGUAGE_OFF = 0;
    private static final int INPUT_STAGE_OFF = 4;
    private static final int INPUT_CLIENT_OFF = 8;
    private static final int INPUT_CLIENT_VERSION_OFF = 12;
    private static final int INPUT_TARGET_LANG_OFF = 16;
    private static final int INPUT_TARGET_LANG_VERSION_OFF = 20;
    private static final int INPUT_CODE_OFF = 24;
    private static final int INPUT_DEFAULT_VERSION_OFF = 32;
    private static final int INPUT_DEFAULT_PROFILE_OFF = 36;
    private static final int INPUT_FORCE_DEFAULT_OFF = 40;
    private static final int INPUT_FORWARD_COMPAT_OFF = 44;
    private static final int INPUT_MESSAGES_OFF = 48;
    private static final int INPUT_RESOURCE_OFF = 56;
    private static final int INPUT_CALLBACKS_OFF = 64;
    private static final int INPUT_SIZE = 96;

    // glslang_resource_t: ~83 int fields + 9 bools + padding
    private static final int RESOURCE_FIELD_COUNT = 83;
    private static final int RESOURCE_SIZE = (RESOURCE_FIELD_COUNT * 4) + 16;

    // ==================== 单例 & 生命周期 ====================

    private static volatile GlslangCompiler instance;

    private GlslangCompiler() {}

    public static synchronized GlslangCompiler initialize() {
        if (instance != null && instance.initialized) {
            return instance;
        }
        GlslangCompiler compiler = new GlslangCompiler();
        compiler.doInitialize();
        instance = compiler;
        return instance;
    }

    public static GlslangCompiler getInstance() {
        return instance;
    }

    private void doInitialize() {
        try {
            Path libPath = extractNativeLibrary();
            LOGGER.info("Loading libglslang: " + libPath);
            cArena = Arena.ofConfined();
            lookup = SymbolLookup.libraryLookup(libPath.toString(), cArena);
            resolveAllHandles();
            int result = (int) callInt("glslang_initialize_process");
            if (result == 0) {
                throw new IllegalStateException("glslang_initialize_process returned 0 (failure)");
            }
            initialized = true;
            LOGGER.info("glslang initialized via Panama FFM");
        } catch (Throwable t) {
            throw new IllegalStateException("Cannot initialize libglslang: " + t.getMessage(), t);
        }
    }

    private Path extractNativeLibrary() throws Exception {
        String resourcePath = "natives/" + LIB_NAME;
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new FileNotFoundException("Native library not found: " + resourcePath
                + "\nWindows: glslang.dll\nLinux: libglslang.so\nmacOS: libglslang.dylib");
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

    // ==================== 核心编译接口 ====================

    public byte[] compile(String glslSource, Stage stage) throws GlslCompileException {
        return compile(glslSource, stage, SourceLanguage.GLSL);
    }

    public byte[] compile(String source, Stage stage, SourceLanguage lang) throws GlslCompileException {
        if (!initialized) {
            throw new IllegalStateException("Compiler not initialized, call initialize() first");
        }
        if (source == null || source.isEmpty()) {
            throw new GlslCompileException("Empty shader source");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = createInputStruct(arena, source, stage, lang);
            MemorySegment shader = (MemorySegment) callPointer("glslang_shader_create", input);
            if (shader.equals(MemorySegment.NULL)) {
                throw new GlslCompileException("glslang_shader_create returned NULL");
            }
            try {
                int preprocOk = (int) callInt("glslang_shader_preprocess", shader, input);
                if (preprocOk == 0) {
                    String log = getInfoLogSafe(shader);
                    throw new GlslCompileException("Preprocess failed:\n" + log);
                }
                int parseOk = (int) callInt("glslang_shader_parse", shader, input);
                if (parseOk == 0) {
                    String log = getInfoLogSafe(shader);
                    throw new GlslCompileException("Parse failed:\n" + log);
                }
                MemorySegment prog = (MemorySegment) callPointer("glslang_program_create");
                if (prog.equals(MemorySegment.NULL)) {
                    throw new GlslCompileException("glslang_program_create returned NULL");
                }
                try {
                    callVoid("glslang_program_add_shader", prog, shader);
                    int linkOk = (int) callInt("glslang_program_link", prog, GLSLANG_MSG_DEFAULT_BIT);
                    if (linkOk == 0) {
                        String log = getProgramInfoLogSafe(prog);
                        throw new GlslCompileException("Link failed:\n" + log);
                    }
                    callVoid("glslang_program_SPIRV_generate", prog, stage.glslangValue);
                    byte[] spirv = extractSPIRV(prog);
                    return spirv;
                } finally {
                    callVoid("glslang_program_delete", prog);
                }
            } finally {
                callVoid("glslang_shader_delete", shader);
            }
        } catch (GlslCompileException e) {
            throw e;
        } catch (Throwable t) {
            throw new GlslCompileException("Compilation failed: " + t.getMessage(), t);
        }
    }

    public CompletableFuture<byte[]> compileAsync(String glslSource, Stage stage) {
        return compileAsync(glslSource, stage, SourceLanguage.GLSL);
    }

    public CompletableFuture<byte[]> compileAsync(String source, Stage stage, SourceLanguage lang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return compile(source, stage, lang);
            } catch (GlslCompileException e) {
                throw new CompletionException(e);
            }
        });
    }

    // ==================== C API 调用 ====================

    private MemorySegment createInputStruct(Arena arena, String source, Stage stage, SourceLanguage lang) {
        MemorySegment input = arena.allocate(INPUT_SIZE);
        input.set(ValueLayout.JAVA_INT, INPUT_LANGUAGE_OFF, lang == SourceLanguage.HLSL ? GLSLANG_SOURCE_HLSL : GLSLANG_SOURCE_GLSL);
        input.set(ValueLayout.JAVA_INT, INPUT_STAGE_OFF, stage.glslangValue);
        input.set(ValueLayout.JAVA_INT, INPUT_CLIENT_OFF, GLSLANG_CLIENT_VULKAN);
        input.set(ValueLayout.JAVA_INT, INPUT_CLIENT_VERSION_OFF, GLSLANG_TARGET_VULKAN_1_1);
        input.set(ValueLayout.JAVA_INT, INPUT_TARGET_LANG_OFF, GLSLANG_TARGET_SPV);
        input.set(ValueLayout.JAVA_INT, INPUT_TARGET_LANG_VERSION_OFF, GLSLANG_TARGET_SPV_1_5);
        MemorySegment codeStr = arena.allocateFrom(source);
        input.set(ValueLayout.ADDRESS, INPUT_CODE_OFF, codeStr);
        input.set(ValueLayout.JAVA_INT, INPUT_DEFAULT_VERSION_OFF, 450);
        input.set(ValueLayout.JAVA_INT, INPUT_DEFAULT_PROFILE_OFF, GLSLANG_NO_PROFILE);
        input.set(ValueLayout.JAVA_INT, INPUT_FORCE_DEFAULT_OFF, 0);
        input.set(ValueLayout.JAVA_INT, INPUT_FORWARD_COMPAT_OFF, 0);
        input.set(ValueLayout.JAVA_INT, INPUT_MESSAGES_OFF, GLSLANG_MSG_DEFAULT_BIT);
        MemorySegment resource = createDefaultResource(arena);
        input.set(ValueLayout.ADDRESS, INPUT_RESOURCE_OFF, resource);
        input.set(ValueLayout.ADDRESS, INPUT_CALLBACKS_OFF, MemorySegment.NULL);
        input.set(ValueLayout.ADDRESS, INPUT_CALLBACKS_OFF + 8, MemorySegment.NULL);
        input.set(ValueLayout.ADDRESS, INPUT_CALLBACKS_OFF + 16, MemorySegment.NULL);
        input.set(ValueLayout.ADDRESS, INPUT_CALLBACKS_OFF + 24, MemorySegment.NULL);
        return input;
    }

    private MemorySegment createDefaultResource(Arena arena) {
        MemorySegment r = arena.allocate(RESOURCE_SIZE);
        int[] defaults = {
            32,    // max_lights
            8,     // max_clip_planes
            32,    // max_texture_units
            32,    // max_texture_coords
            64,    // max_vertex_attribs
            4096,  // max_vertex_uniform_components
            64,    // max_varying_floats
            32,    // max_vertex_texture_image_units
            80,    // max_combined_texture_image_units
            80,    // max_texture_image_units
            4096,  // max_fragment_uniform_components
            8,     // max_draw_buffers
            256,   // max_vertex_uniform_vectors
            60,    // max_varying_vectors
            256,   // max_fragment_uniform_vectors
            16,    // max_vertex_output_vectors
            15,    // max_fragment_input_vectors
            -8,    // min_program_texel_offset
            7,     // max_program_texel_offset
            8,     // max_clip_distances
            65535, // max_compute_work_group_count_x
            65535, // max_compute_work_group_count_y
            65535, // max_compute_work_group_count_z
            1024,  // max_compute_work_group_size_x
            1024,  // max_compute_work_group_size_y
            64,    // max_compute_work_group_size_z
            1024,  // max_compute_uniform_components
            32,    // max_compute_texture_image_units
            8,     // max_compute_image_uniforms
            8,     // max_compute_atomic_counters
            1,     // max_compute_atomic_counter_buffers
            64,    // max_varying_components
            64,    // max_vertex_output_components
            128,   // max_geometry_input_components
            128,   // max_geometry_output_components
            128,   // max_fragment_input_components
            8,     // max_image_units
            8,     // max_combined_image_units_and_fragment_outputs
            8,     // max_combined_shader_output_resources
            0,     // max_image_samples
            4,     // max_vertex_image_uniforms
            4,     // max_tess_control_image_uniforms
            4,     // max_tess_evaluation_image_uniforms
            4,     // max_geometry_image_uniforms
            8,     // max_fragment_image_uniforms
            8,     // max_combined_image_uniforms
            32,    // max_geometry_texture_image_units
            256,   // max_geometry_output_vertices
            1024,  // max_geometry_total_output_components
            4096,  // max_geometry_uniform_components
            64,    // max_geometry_varying_components
            128,   // max_tess_control_input_components
            128,   // max_tess_control_output_components
            32,    // max_tess_control_texture_image_units
            4096,  // max_tess_control_uniform_components
            4096,  // max_tess_control_total_output_components
            128,   // max_tess_evaluation_input_components
            128,   // max_tess_evaluation_output_components
            32,    // max_tess_evaluation_texture_image_units
            4096,  // max_tess_evaluation_uniform_components
            128,   // max_tess_patch_components
            32,    // max_patch_vertices
            64,    // max_tess_gen_level
            16,    // max_viewports
            8,     // max_vertex_atomic_counters
            8,     // max_tess_control_atomic_counters
            8,     // max_tess_evaluation_atomic_counters
            8,     // max_geometry_atomic_counters
            8,     // max_fragment_atomic_counters
            8,     // max_combined_atomic_counters
            1,     // max_atomic_counter_bindings
            0,     // max_vertex_atomic_counter_buffers
            0,     // max_tess_control_atomic_counter_buffers
            0,     // max_tess_evaluation_atomic_counter_buffers
            0,     // max_geometry_atomic_counter_buffers
            1,     // max_fragment_atomic_counter_buffers
            1,     // max_combined_atomic_counter_buffers
            16384, // max_atomic_counter_buffer_size
            4,     // max_transform_feedback_buffers
            64,    // max_transform_feedback_interleaved_components
            8,     // max_cull_distances
            8,     // max_combined_clip_and_cull_distances
            4,     // max_samples
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0  // remaining fields (mesh/task/etc) zeroed
        };
        for (int i = 0; i < defaults.length && i < RESOURCE_FIELD_COUNT; i++) {
            r.set(ValueLayout.JAVA_INT, i * 4L, defaults[i]);
        }
        return r;
    }

    private MemorySegment glslang_shader_create(MemorySegment input) throws Throwable {
        return (MemorySegment) callPointer("glslang_shader_create", input);
    }

    private byte[] extractSPIRV(MemorySegment prog) throws Throwable {
        long wordCount = (long) callLong("glslang_program_SPIRV_get_size", prog);
        if (wordCount == 0) {
            throw new GlslCompileException("SPIR-V generation produced zero words");
        }
        MemorySegment spirvPtr = (MemorySegment) callPointer("glslang_program_SPIRV_get_ptr", prog);
        if (spirvPtr.equals(MemorySegment.NULL)) {
            throw new GlslCompileException("SPIR-V pointer is NULL");
        }
        long byteSize = wordCount * 4L;
        MemorySegment spirvSegment = spirvPtr.reinterpret(byteSize);
        byte[] result = new byte[(int) byteSize];
        spirvSegment.asByteBuffer().get(result);
        validateSpirvMagic(result);
        return result;
    }

    private void validateSpirvMagic(byte[] spirv) throws GlslCompileException {
        if (spirv.length < 4) {
            throw new GlslCompileException("SPIR-V data too short");
        }
        int magic = (spirv[0] & 0xFF) | ((spirv[1] & 0xFF) << 8)
                  | ((spirv[2] & 0xFF) << 16) | ((spirv[3] & 0xFF) << 24);
        if (magic != 0x07230203) {
            throw new GlslCompileException("Invalid SPIR-V magic: 0x" + Long.toHexString(magic & 0xFFFFFFFFL));
        }
    }

    private String getInfoLogSafe(MemorySegment shader) {
        try {
            MemorySegment ptr = (MemorySegment) callPointer("glslang_shader_get_info_log", shader);
            if (ptr.equals(MemorySegment.NULL)) return "";
            return ptr.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable e) {
            return "(failed to get log: " + e.getMessage() + ")";
        }
    }

    private String getProgramInfoLogSafe(MemorySegment prog) {
        try {
            MemorySegment ptr = (MemorySegment) callPointer("glslang_program_get_info_log", prog);
            if (ptr.equals(MemorySegment.NULL)) return "";
            return ptr.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable e) {
            return "(failed to get log: " + e.getMessage() + ")";
        }
    }

    // ==================== FFM 方法句柄管理 ====================

    private void resolveAllHandles() throws Exception {
        String[] functions = {
            "glslang_initialize_process",
            "glslang_finalize_process",
            "glslang_shader_create",
            "glslang_shader_delete",
            "glslang_shader_preprocess",
            "glslang_shader_parse",
            "glslang_shader_get_info_log",
            "glslang_shader_get_info_debug_log",
            "glslang_program_create",
            "glslang_program_delete",
            "glslang_program_add_shader",
            "glslang_program_link",
            "glslang_program_SPIRV_generate",
            "glslang_program_SPIRV_get_size",
            "glslang_program_SPIRV_get_ptr",
            "glslang_program_get_info_log",
            "glslang_program_get_info_debug_log"
        };
        Linker linker = Linker.nativeLinker();
        for (String fn : functions) {
            MemorySegment addr = lookup.find(fn)
                    .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found in glslang: " + fn));
            FunctionDescriptor desc = getDescriptor(fn);
            handles.put(fn, linker.downcallHandle(addr, desc));
        }
    }

    private FunctionDescriptor getDescriptor(String name) {
        return switch (name) {
            case "glslang_initialize_process" -> FunctionDescriptor.of(ValueLayout.JAVA_INT);
            case "glslang_finalize_process" -> FunctionDescriptor.ofVoid();
            case "glslang_shader_create" -> FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            case "glslang_shader_delete" -> FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
            case "glslang_shader_preprocess" -> FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            case "glslang_shader_parse" -> FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            case "glslang_shader_get_info_log", "glslang_shader_get_info_debug_log" ->
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            case "glslang_program_create" -> FunctionDescriptor.of(ValueLayout.ADDRESS);
            case "glslang_program_delete" -> FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
            case "glslang_program_add_shader" -> FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            case "glslang_program_link" -> FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
            case "glslang_program_SPIRV_generate" -> FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
            case "glslang_program_SPIRV_get_size" -> FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
            case "glslang_program_SPIRV_get_ptr" -> FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            case "glslang_program_get_info_log", "glslang_program_get_info_debug_log" ->
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            default -> throw new AssertionError("Unknown function: " + name);
        };
    }

    private int callInt(String name, Object... args) throws Throwable {
        return (int) handles.get(name).invokeExact(args);
    }

    private void callVoid(String name, Object... args) throws Throwable {
        handles.get(name).invokeExact(args);
    }

    private Object callPointer(String name, Object... args) throws Throwable {
        return handles.get(name).invokeExact(args);
    }

    private long callLong(String name, Object... args) throws Throwable {
        return (long) handles.get(name).invokeExact(args);
    }

    // ==================== 生命周期管理 ====================

    public void shutdown() {
        if (initialized) {
            try {
                callVoid("glslang_finalize_process");
            } catch (Throwable ignored) {}
            initialized = false;
            LOGGER.info("libglslang shut down");
        }
        if (cArena != null && cArena.scope().isAlive()) {
            cArena.close();
        }
    }

    public boolean isInitialized() {
        return initialized;
    }
}

class GlslCompileException extends Exception {
    public GlslCompileException(String message) { super(message); }
    public GlslCompileException(String message, Throwable cause) { super(message, cause); }
}
