// Renderium - GLSL 预处理器
// 处理 #include、#define、#version 等预处理指令
// 为 SPIR-V 编译准备干净的 GLSL 源码

package com.ranecc.renderium.feature.shader;

import java.util.*;
import java.util.logging.Logger;

/**
 * GLSL 预处理器。
 *
 * <p>在 GLSL 源码提交给 SPIR-V 编译器之前，执行以下预处理：
 * <ul>
 *   <li><b>#include</b>：内联包含文件（递归展开，防止循环引用）</li>
 *   <li><b>#version</b>：统一版本声明（确保 Vulkan 兼容）</li>
 *   <li><b>#define</b>：注入平台相关的宏定义</li>
 *   <li><b>#extension</b>：自动添加必要的扩展声明</li>
 *   <li><b>条件编译</b>：#ifdef/#ifndef/#endif 处理</li>
 * </ul>
 *
 * <h2>与 Minecraft 光影包的关系</h2>
 * <p>Minecraft 光影包（OptiFine/Iris 格式）使用自定义的 #include 语法
 * 和大量预定义宏。ShaderPreprocessor 负责将这些非标准 GLSL
 * 转换为标准 Vulkan GLSL，以便 glslangValidator 编译为 SPIR-V。
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class ShaderPreprocessor {

    private static final Logger LOGGER = Logger.getLogger(ShaderPreprocessor.class.getName());

    /** 默认 GLSL 版本（Vulkan 需要 450+） */
    private static final String DEFAULT_GLSL_VERSION = "#version 450";

    /** Vulkan GLSL 必需的扩展 */
    private static final String VULKAN_EXTENSIONS =
            "#extension GL_ARB_separate_shader_objects : enable" +
            "#extension GL_ARB_shading_language_420pack : enable";

    // ==================== 预定义宏 ====================

    /** 平台相关宏定义 */
    private final Map<String, String> predefinedMacros;

    /** 包含文件解析器 */
    private final IncludeResolver includeResolver;

    /** 最大递归深度（防止循环包含） */
    private int maxIncludeDepth = 16;

    // ==================== 构造 ====================

    public ShaderPreprocessor(IncludeResolver includeResolver) {
        this.includeResolver = includeResolver;
        this.predefinedMacros = new LinkedHashMap<>();

        // 注入 Renderium 预定义宏
        predefinedMacros.put("RENDERIUM", "1");
        predefinedMacros.put("RENDERIUM_VULKAN", "1");
        predefinedMacros.put("RENDERIUM_VERSION", "100"); // 1.0.0
    }

    // ==================== 核心预处理 ====================

    /**
     * 预处理 GLSL 源码
     *
     * <p>执行完整的预处理流水线：
     * <ol>
     *   <li>注入预定义宏</li>
     *   <li>处理 #include 指令</li>
     *   <li>统一 #version 声明</li>
     *   <li>注入 Vulkan 扩展</li>
     *   <li>处理条件编译</li>
     * </ol>
     *
     * @param source 原始 GLSL 源码
     * @param stage 着色器阶段（vertex/fragment/compute）
     * @return 预处理后的 GLSL 源码
     * @throws ShaderPreprocessException 预处理失败
     */
    public String preprocess(String source, ShaderStage stage) throws ShaderPreprocessException {
        if (source == null || source.isBlank()) {
            throw new ShaderPreprocessException("Empty shader source");
        }

        // Step 1: 移除原有 #version
        String processed = removeVersionDirectives(source);

        // Step 2: 处理 #include（递归展开）
        Set<String> includedFiles = new HashSet<>();
        processed = processIncludes(processed, includedFiles, 0);

        // Step 3: 构建最终源码
        StringBuilder sb = new StringBuilder();

        // 注入版本声明
        sb.append(DEFAULT_GLSL_VERSION).append('\n');

        // 注入 Vulkan 扩展
        sb.append(VULKAN_EXTENSIONS);

        // 注入 Vulkan 特定的宏和类型
        sb.append(generateVulkanPreamble(stage));

        // 注入预定义宏
        for (Map.Entry<String, String> entry : predefinedMacros.entrySet()) {
            sb.append("#define ").append(entry.getKey());
            if (!entry.getValue().isEmpty()) {
                sb.append('\n').append(entry.getValue());
            }
            sb.append('\n');
        }

        // 注入用户自定义宏
        sb.append(processed);

        return sb.toString();
    }

    /**
     * 添加自定义宏定义
     */
    public void defineMacro(String name, String value) {
        predefinedMacros.put(name, value);
    }

    /**
     * 移除宏定义
     */
    public void undefMacro(String name) {
        predefinedMacros.remove(name);
    }

    // ==================== 内部方法 ====================

    /**
     * 移除原有的 #version 声明
     */
    private String removeVersionDirectives(String source) {
        return source.replaceAll("(?m)^\\s*#version\\s+.*$", "");
    }

    /**
     * 递归处理 #include 指令
     */
    private String processIncludes(String source, Set<String> includedFiles, int depth)
            throws ShaderPreprocessException {
        if (depth > maxIncludeDepth) {
            throw new ShaderPreprocessException("Include depth exceeded " + maxIncludeDepth +
                    ", possible circular include");
        }

        StringBuilder result = new StringBuilder();
        result.append("\n");

        for (String line : lines) {
            String trimmed = line.trim();

            // 匹配 #include "file" 或 #include <file>
            if (trimmed.startsWith("#include")) {
                String includePath = extractIncludePath(trimmed);
                if (includePath == null) {
                    result.append(line).append('\n');
                    continue;
                }

                // 防止循环包含
                if (includedFiles.contains(includePath)) {
                    continue;
                }

                // 解析包含文件
                String includeContent = includeResolver.resolve(includePath);
                if (includeContent == null) {
                    throw new ShaderPreprocessException("Include file not found: " + includePath);
                }

                includedFiles.add(includePath);

                // 递归处理包含文件中的 #include
                String expanded = processIncludes(includeContent, includedFiles, depth + 1);
                result.append("// --- Begin include: ").append(includePath).append(" ---\n");
                result.append(expanded);
                result.append("// --- End include: ").append(includePath).append(" ---\n");
            } else {
                result.append(line).append('\n');
            }
        }

        return result.toString();
    }

    /**
     * 从 #include 指令中提取文件路径
     */
    private String extractIncludePath(String includeLine) {
        // #include "path" 或 #include <path>
        int startQuote = includeLine.indexOf('"');
        int endQuote = includeLine.lastIndexOf('"');
        if (startQuote >= 0 && endQuote > startQuote) {
            return includeLine.substring(startQuote + 1, endQuote);
        }

        int startAngle = includeLine.indexOf('<');
        int endAngle = includeLine.lastIndexOf('>');
        if (startAngle >= 0 && endAngle > startAngle) {
            return includeLine.substring(startAngle + 1, endAngle);
        }

        return null;
    }

    /**
     * 生成 Vulkan GLSL 前导码
     *
     * <p>Vulkan GLSL 与桌面 GLSL 的关键差异：
     * <ul>
     *   <li>使用 gl_VertexIndex 而非 gl_VertexID</li>
     *   <li>使用 gl_FragCoord 而非 gl_FragCoord（兼容）</li>
     *   <li>输出变量需要使用 layout(location = N)</li>
     *   <li>Uniform 缓冲需要 layout(binding = N)</li>
     * </ul>
     */
    private String generateVulkanPreamble(ShaderStage stage) {
        StringBuilder sb = new StringBuilder();

        switch (stage) {
            case VERTEX -> {
                sb.append("// Vulkan vertex shader preamble\n");
                sb.append("#define gl_VertexID gl_VertexIndex\n");
                sb.append("#define gl_InstanceID gl_InstanceIndex\n");
            }
            case FRAGMENT -> {
                sb.append("// Vulkan fragment shader preamble\n");
                sb.append("layout(early_fragment_tests) in;\n");
            }
            case COMPUTE -> {
                sb.append("// Vulkan compute shader preamble\n");
                sb.append("layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n");
            }
        }

        return sb.toString();
    }

    // ==================== 内部类型 ====================

    /**
     * 着色器阶段
     */
    public enum ShaderStage {
        VERTEX, FRAGMENT, COMPUTE
    }

    /**
     * 包含文件解析器接口
     */
    @FunctionalInterface
    public interface IncludeResolver {
        /**
         * 解析包含文件
         *
         * @param path 文件路径
         * @return 文件内容，如果文件不存在返回 null
         */
        String resolve(String path);
    }

    /**
     * 预处理异常
     */
    public static final class ShaderPreprocessException extends Exception {
        public ShaderPreprocessException(String message) {
            super(message);
        }
    }
}
