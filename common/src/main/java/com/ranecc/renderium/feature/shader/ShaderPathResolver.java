package com.ranecc.renderium.feature.shader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Shader 路径动态解析器
 * <p>
 * 将 shader key（如 "pipeline/lighting/shadow_style"）解析为 SPIR-V 字节码。
 * 支持三级优先级：
 * <ol>
 *   <li>RGB 光影包覆盖（如果已加载 .rgb 包）</li>
 *   <li>预编译 SPIR-V 二进制（/shaders/xxx.spv）</li>
 *   <li>GLSL 源码编译回退（/shaders-src/xxx.comp）</li>
 * </ol>
 *
 * <h3>Shader Key 命名规范：</h3>
 * <pre>
 *   pipeline/geometry/gbuffer_fill          → /shaders/pipeline/geometry/gbuffer_fill.spv
 *   pipeline/lighting/shadow_style          → /shaders/pipeline/lighting/shadow_style.spv
 *   pipeline/postprocess/bloom/bloom_bright → /shaders/pipeline/postprocess/bloom/bloom_bright.spv
 *   compute/culling/frustum_culling         → /shaders/compute/culling/frustum_culling.spv
 * </pre>
 *
 * @since 2.1.0
 */
public final class ShaderPathResolver {

    private static final Logger LOGGER = Logger.getLogger("Renderium");

    /** SPIR-V 预编译产物根路径（classpath 内） */
    private static final String SPV_ROOT = "/shaders/";

    /** GLSL 源码根路径（classpath 内，开发模式回退） */
    private static final String SRC_ROOT = "/shaders-src/";

    /** SPIR-V magic number: 0x07230203 (little-endian: 03 02 23 07) */
    private static final int SPIRV_MAGIC_0 = 0x03;
    private static final int SPIRV_MAGIC_1 = 0x02;
    private static final int SPIRV_MAGIC_2 = 0x23;
    private static final int SPIRV_MAGIC_3 = 0x07;

    /** RGB 光影包提供的 SPIR-V 覆盖（shader key → SPIR-V 字节码），volatile 保证可见性 */
    private static volatile Map<String, byte[]> rgbOverrides = Map.of();

    private ShaderPathResolver() {
        // 工具类禁止实例化
    }

    /**
     * 解析 shader key 到 SPIR-V 字节码
     *
     * @param shaderKey shader 标识，如 "pipeline/lighting/shadow_style"
     * @return SPIR-V 字节码，如果所有方式均失败则返回 null
     */
    public static byte[] resolveSPIRV(String shaderKey) {
        Objects.requireNonNull(shaderKey, "shaderKey cannot be null");

        // 优先级 1: RGB 光影包覆盖
        byte[] rgbSpirv = rgbOverrides.get(shaderKey);
        if (rgbSpirv != null) {
            LOGGER.fine("Shader [" + shaderKey + "] 使用 RGB 光影包覆盖");
            return rgbSpirv;
        }

        // 优先级 2: 预编译 SPIR-V 二进制
        String spvPath = SPV_ROOT + shaderKey + ".spv";
        byte[] spvBytes = loadFromClasspath(spvPath);
        if (spvBytes != null && validateSpirvMagic(spvBytes)) {
            LOGGER.fine("Shader [" + shaderKey + "] 加载预编译 SPIR-V: " + spvPath + " (" + spvBytes.length + " bytes)");
            return spvBytes;
        }

        // 优先级 3: GLSL 源码编译回退
        String compPath = SRC_ROOT + shaderKey + ".comp";
        byte[] compiled = compileFromSource(compPath);
        if (compiled != null) {
            LOGGER.fine("Shader [" + shaderKey + "] 从源码编译: " + compPath);
            return compiled;
        }

        LOGGER.severe("Shader [" + shaderKey + "] 解析失败（RGB/SPV/源码均不可用）");
        return null;
    }

    /**
     * 获取默认 SPIR-V 资源路径（不加载，仅返回路径字符串）
     *
     * @param shaderKey shader 标识
     * @return 默认 SPV 路径，如 "/shaders/pipeline/lighting/shadow_style.spv"
     */
    public static String getDefaultSpvPath(String shaderKey) {
        return SPV_ROOT + shaderKey + ".spv";
    }

    /**
     * 注册 RGB 光影包覆盖
     * <p>
     * 当加载 .rgb 光影包时调用，替换默认 SPIR-V 为光影包提供的版本。
     *
     * @param overrides shader key → SPIR-V 字节码的映射
     */
    public static void registerRGBOverrides(Map<String, byte[]> overrides) {
        rgbOverrides = (overrides != null) ? Map.copyOf(overrides) : Map.of();
        LOGGER.info("RGB 光影包覆盖已注册: " + rgbOverrides.size() + " 个 shader");
    }

    /**
     * 清除 RGB 光影包覆盖
     * <p>
     * 当卸载 .rgb 光影包时调用，恢复默认 SPIR-V 加载。
     */
    public static void clearRGBOverrides() {
        rgbOverrides = Map.of();
        LOGGER.info("RGB 光影包覆盖已清除");
    }

    // ==================== 内部方法 ====================

    /** 从 classpath 加载资源 */
    private static byte[] loadFromClasspath(String path) {
        try (InputStream is = ShaderPathResolver.class.getResourceAsStream(path)) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            LOGGER.fine("classpath 加载失败: " + path + " - " + e.getMessage());
        }
        return null;
    }

    /** 验证 SPIR-V magic number */
    private static boolean validateSpirvMagic(byte[] data) {
        return data.length >= 4
                && (data[0] & 0xFF) == SPIRV_MAGIC_0
                && (data[1] & 0xFF) == SPIRV_MAGIC_1
                && (data[2] & 0xFF) == SPIRV_MAGIC_2
                && (data[3] & 0xFF) == SPIRV_MAGIC_3;
    }

    /** 从 GLSL 源码编译为 SPIR-V */
    private static byte[] compileFromSource(String compPath) {
        try {
            ClassLoader cl = ShaderPathResolver.class.getClassLoader();
            // 去掉前导 / 以适配 ClassLoader.getResourceAsStream()
            String clPath = compPath.startsWith("/") ? compPath.substring(1) : compPath;
            try (InputStream is = cl.getResourceAsStream(clPath)) {
                if (is == null) return null;
                String source = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                // MC 26.2 API 修复：GlslangCompiler.compile() 是实例方法而非静态方法
                // 方法签名: compile(String source, Stage stage) throws GlslCompileException
                // 需要通过 getInstance() 获取编译器实例
                com.ranecc.renderium.feature.blaze3d.shader.GlslangCompiler compiler =
                    com.ranecc.renderium.feature.blaze3d.shader.GlslangCompiler.getInstance();

                if (compiler == null) {
                    LOGGER.severe("GlslangCompiler 未初始化，无法编译 shader: " + compPath);
                    return null;
                }

                // 使用正确的实例方法调用，Stage 枚举作为第二个参数
                return compiler.compile(
                    source,
                    com.ranecc.renderium.feature.blaze3d.shader.GlslangCompiler.Stage.COMPUTE
                );
            }
        } catch (Exception e) {
            LOGGER.fine("源码编译失败: " + compPath + " - " + e.getMessage());
            return null;
        }
    }
}
