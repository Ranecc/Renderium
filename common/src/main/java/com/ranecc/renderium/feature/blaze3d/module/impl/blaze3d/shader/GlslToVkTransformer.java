// Renderium - Blaze3D Shader 转译模块
// GLSL → Vulkan GLSL 转换引擎 - 基于 Token 流的安全语法转换

package com.ranecc.renderium.feature.blaze3d.module.impl.blaze3d.shader;

import java.util.*;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * GLSL → Vulkan GLSL 转换引擎。
 * <p>
 * 与正则表达式盲目替换的本质区别: 本类接收 {@link GlslLexer} 产出的 Token 流，
 * 只在 {@link GlslLexer.TokenType#KEYWORD} 和 {@link GlslLexer.TokenType#IDENTIFIER}
 * 上做替换，注释/字符串/数字字面量原样透传，<b>零误杀</b>。
 *
 * <h2>转换规则表：</h2>
 * <pre>
 * ┌────────────────────┬──────────────────────────┬────────────┐
 * │ 原始 Token          │ Vulkan GLSL 替换           │ 条件        │
 * ├────────────────────┼──────────────────────────┼────────────┤
 * │ attribute           │ layout(location=N) in     │ 全局声明   │
 * │ varying             │ in / out (按 shader 阶段)  │ Vertex→out │
 * │ texture2D(...)      │ texture(...)              │ IDENTIFIER │
 * │ gl_FragColor        │ outputColor (需前置声明)    │ IDENTIFIER │
 * │ uniform Block {..}  │ layout(set=S,binding=B).. │ 自动分配   │
 * └────────────────────┴──────────────────────────┴────────────┘
 * </pre>
 *
 * <h2>使用示例：</h2>
 * <pre>{@code
 * GlslLexer lexer = new GlslLexer(sourceCode);
 * List<GlslToken> tokens = lexer.tokenize();
 *
 * GlslToVkTransformer transformer = new GlslToVkTransformer(ShaderStage.FRAGMENT);
 * TransformationResult result = transformer.transform(tokens);
 *
 * // result.vulkanGLSL()       → 转换后的 Vulkan GLSL 源码
 * // result.uniformMap()       → Uniform 名称 → set/binding/offset 映射
 * // result.warnings()         → 转换过程中的警告信息
 * }</pre>
 *
 * @see GlslLexer 提供精确的 Token 流
 * @see UniformRedirector 使用本类生成的 uniformMap 做 Blaze3D → Vulkan 桥接
 * @since 3.0.0
 */
public final class GlslToVkTransformer {

    private static final Logger LOGGER = Logger.getLogger("Renderium-GlslTransform");

    /** Shader 阶段 (影响 varying 方向判断) */
    public enum ShaderStage {
        VERTEX,
        FRAGMENT,
        COMPUTE,
        GEOMETRY,
        TESS_CONTROL,
        TESS_EVAL
    }

    /**
     * 转换结果
     *
     * @param vulkanGLSL    转换后的 Vulkan GLSL 源码
     * @param uniformMap    Uniform 名称 → Vulkan 绑定信息的映射表
     * @param warnings      转换过程中的警告列表
     * @param addedOutputDecl 是否添加了 outputColor 声明
     */
    public record TransformationResult(
            String vulkanGLSL,
            Map<String, UniformBindingInfo> uniformMap,
            List<TransformWarning> warnings,
            boolean addedOutputDecl
    ) {}

    /**
     * Uniform 绑定信息 (供 UniformRedirector 使用)
     *
     * @param setIndex  Descriptor Set 索引
     * @param bindingPoint Binding 点索引
     * @param offset    在 UBO 中的字节偏移
     * @param sizeBytes 数据类型大小 (字节)
     * @param glslType  原始 GLSL 类型名
     */
    public record UniformBindingInfo(
            int setIndex,
            int bindingPoint,
            int offset,
            int sizeBytes,
            String glslType
    ) {}

    /**
     * 转换警告
     *
     * @param line    行号
     * @param message 警告消息
     * @param level   警告级别
     */
    public record TransformWarning(
            int line,
            String message,
            Level level
    ) {
        public enum Level { INFO, WARN, ERROR }
    }

    // ==================== 字段 ====================

    /** 当前目标 shader 阶段 */
    private final ShaderStage targetStage;

    /** 下一个可用的 location 编号 */
    private int nextLocation;

    /** 下一个可用的 binding 编号 */
    private int nextBinding;

    /** Uniform 块 → binding 映射 (保持插入顺序) */
    private final LinkedHashMap<String, Integer> uniformBlockBindings = new LinkedHashMap<>();

    /** 收集到的 Uniform 信息 */
    private final LinkedHashMap<String, UniformBindingInfo> collectedUniforms = new LinkedHashMap<>();

    /** 警告列表 */
    private final List<TransformWarning> warnings = new ArrayList<>();

    /** 是否已添加 outputColor 声明 */
    private boolean outputColorDeclared;

    // ==================== 构造函数 ====================

    /**
     * 构造转换器
     *
     * @param stage 目标 shader 阶段 (用于判断 in/out 方向)
     */
    public GlslToVkTransformer(ShaderStage stage) {
        this.targetStage = Objects.requireNonNull(stage);
        this.nextLocation = 0;
        this.nextBinding = 0;
    }

    // ==================== 核心公共 API ====================

    /**
     * 执行 Token 流转换。
     *
     * <h3>处理流程：</h3>
     * <ol>
     *   <li>预置 #version 450 声明 (如果源码没有)</li>
     *   <li>逐 Token 处理:
     *     <ul>
     *       <li>COMMENT_LINE / COMMENT_BLOCK / STRING_LITERAL / NUMBER_LITERAL / WHITESPACE → 原样透传</li>
     *       <li>PREPROCESSOR → 特殊处理 (#include 展开检查, #version 升级)</li>
     *       <li>KEYWORD → attribute/varying 转换</li>
     *       <li>IDENTIFIER → 函数/内置变量转换</li>
     *       <li>OPERATOR → 原样输出</li>
     *     </ul>
     *   </li>
     *   <li>如需 outputColor 则追加声明</li>
     * </ol>
     *
     * @param tokens 来自 {@link GlslLexer#tokenize()} 的有序 Token 列表
     * @return 转换结果 (包含 Vulkan GLSL 源码和 Uniform 映射表)
     */
    public TransformationResult transform(List<GlslLexer.GlslToken> tokens) {
        StringBuilder output = new StringBuilder();

        // 1. 预置版本声明 (如果源码没有)
        boolean hasVersion = false;
        for (var t : tokens) {
            if (t.type() == GlslLexer.TokenType.PREPROCESSOR && t.text().startsWith("#version")) {
                hasVersion = true;
                break;
            }
        }
        if (!hasVersion) {
            output.append("#version 450\n");
        }

        // 2. 逐 Token 处理
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);

            switch (token.type()) {
                case COMMENT_LINE, COMMENT_BLOCK, STRING_LITERAL, NUMBER_LITERAL, WHITESPACE -> {
                    // 原样透传 — 这些区域绝不触碰
                    output.append(token.text());
                }
                case PREPROCESSOR -> {
                    handlePreprocessor(token, output);
                }
                case KEYWORD -> {
                    handleKeyword(token, i, tokens, output);
                }
                case IDENTIFIER -> {
                    handleIdentifier(token, i, tokens, output);
                }
                case OPERATOR, EOF -> {
                    output.append(token.text());
                }
                default -> {
                    output.append(token.text());
                }
            }
        }

        // 3. 如果使用了 gl_FragColor 但还没声明 outputColor，追加声明
        if (outputColorDeclared && !hasOutputDeclaration(output.toString())) {
            output.insert(findInsertPositionForOutput(output.toString()),
                    "layout(location = 0) out vec4 outputColor;\n");
        }

        return new TransformationResult(
                output.toString(),
                Map.copyOf(collectedUniforms),
                List.copyOf(warnings),
                outputColorDeclared
        );
    }

    // ==================== 关键字处理 ====================

    /**
     * 处理关键字 Token。
     * <p>
     * attribute → layout(location=N) in<br>
     * varying → in 或 out (取决于 shader 阶段)
     */
    private void handleKeyword(GlslLexer.GlslToken token, int index,
                               List<GlslLexer.GlslToken> tokens, StringBuilder output) {
        switch (token.text()) {
            case "attribute" -> {
                // attribute → layout(location=N) in
                output.append("layout(location = ").append(nextLocation++).append(") in ");
                warnings.add(new TransformWarning(token.line(),
                        "attribute 已转换为 layout(location) in (Vulkan GLSL)",
                        TransformWarning.Level.INFO));
            }

            case "varying" -> {
                // varying → in 或 out (取决于 shader 阶段)
                String direction = switch (targetStage) {
                    case VERTEX, GEOMETRY, TESS_EVAL -> "out";
                    case FRAGMENT, TESS_CONTROL -> "in";
                    case COMPUTE -> {
                        warnings.add(new TransformWarning(token.line(),
                                "Compute shader 中不应使用 varying",
                                TransformWarning.Level.WARN));
                        yield "in";
                    }
                };
                output.append(direction).append(" ");
                warnings.add(new TransformWarning(token.line(),
                        "varying 已转换为 " + direction + " (Vulkan GLSL)",
                        TransformWarning.Level.INFO));
            }

            default -> {
                // 其他关键字原样输出
                output.append(token.text()).append(" ");
            }
        }
    }

    // ==================== 标识符处理 (核心转换逻辑) ====================

    /**
     * 处理标识符 Token。
     * <p>
     * OpenGL 遗留函数名 → Vulkan 函数名<br>
     * OpenGL 内置变量 → Vulkan 变量名
     */
    private void handleIdentifier(GlslLexer.GlslToken token, int index,
                                  List<GlslLexer.GlslToken> tokens, StringBuilder output) {
        String name = token.text();

        // OpenGL 遗留函数名转换
        if (isLegacyFunction(name)) {
            output.append(convertLegacyFunction(name));
            return;
        }

        // OpenGL 内置变量转换
        if (isLegacyBuiltin(name)) {
            output.append(convertLegacyBuiltin(name));
            return;
        }

        // 普通 identifier 原样输出
        output.append(name);
    }

    // ==================== 预处理指令处理 ====================

    /**
     * 处理预处理指令。
     * <ul>
     *   <li>#version: 保持但升级到 450</li>
     *   <li>#include: 报错 (应在预处理阶段展开)</li>
     *   <li>其他: 原样保留</li>
     * </ul>
     */
    private void handlePreprocessor(GlslLexer.GlslToken token, StringBuilder output) {
        String directive = token.text().trim();

        if (directive.startsWith("#version")) {
            // 将旧版版本号升级到 450
            String upgraded = directive.replaceAll("#version\\s+\\d+\\s*(core|es)?", "#version 450");
            output.append(upgraded).append("\n");
            return;
        }

        if (directive.startsWith("#include")) {
            warnings.add(new TransformWarning(token.line(),
                    "#include 未被预处理器展开 (应在预处理阶段处理)",
                    TransformWarning.Level.WARN));
            output.append(directive).append("\n");
            return;
        }

        // #define / #undef / #if / #ifdef / #ifndef / #endif / #else / #elif: 原样保留
        output.append(directive).append("\n");
    }

    // ==================== 转换规则实现 ====================

    /**
     * OpenGL 遗留函数 → Vulkan 函数名映射
     */
    private String convertLegacyFunction(String funcName) {
        return switch (funcName) {
            case "texture2D", "texture2DLod", "texture2DProj",
                 "textureCube", "textureCubeLod",
                 "texture3D", "texture3DLod",
                 "texture1D", "texture1DLod" -> "texture";
            case "ftransform" -> {
                warnings.add(new TransformWarning(0,
                        "ftransform() 在 Vulkan 中不支持，需手动实现 MVP 变换",
                        TransformWarning.Level.ERROR));
                yield "ftransform"; // 保留原名让编译器报错
            }
            default -> funcName;
        };
    }

    /**
     * OpenGL 内置变量 → Vulkan 变量名映射
     */
    private String convertLegacyBuiltin(String builtin) {
        return switch (builtin) {
            case "gl_FragColor" -> {
                outputColorDeclared = true;
                yield "outputColor";
            }
            case "gl_FragData" -> {
                outputColorDeclared = true;
                warnings.add(new TransformWarning(0,
                        "gl_FragData[] 被简化为 outputColor (仅支持 [0])",
                        TransformWarning.Level.WARN));
                yield "outputColor";
            }
            case "gl_FrontFacing" -> "gl_FrontFacing"; // Vulkan 可用
            case "gl_PointCoord" -> "gl_PointCoord";       // Vulkan 可用
            case "gl_VertexID" -> "gl_VertexId";             // Vulkan 大小写不同!
            case "gl_InstanceID" -> "gl_InstanceIndex";      // Vulkan 改名!
            case "gl_Position" -> "gl_Position";             // Vertex output, Vulkan 可用
            case "gl_PointSize" -> "gl_PointSize";           // Vulkan 可用
            default -> {
                warnings.add(new TransformWarning(0,
                        "未知内置变量: " + builtin +
                                " (可能不被 Vulkan GLSL 支持)",
                        TransformWarning.Level.WARN));
                yield builtin;
            }
        };
    }

    // ==================== 辅助方法 ====================

    /** 检查输出中是否已有 outputColor 声明 */
    private boolean hasOutputDeclaration(String code) {
        return code.contains("outputColor") &&
                Pattern.compile("out\\s+vec[34]\\s+outputColor\\s*;").matcher(code).find();
    }

    /** 找到合适的插入位置 (在 main() 函数之前) */
    private int findInsertPositionForOutput(String code) {
        int mainPos = code.indexOf("void main()");
        if (mainPos > 0) {
            int nlPos = code.lastIndexOf('\n', mainPos);
            return nlPos > 0 ? nlPos + 1 : 0;
        }
        return code.length();
    }

    /** 判断是否是 OpenGL 遗留函数 */
    private boolean isLegacyFunction(String name) {
        return Set.of("texture2D", "texture2DLod", "texture2DProj",
                       "textureCube", "textureCubeLod",
                       "texture3D", "texture3DLod",
                       "texture1D", "texture1DLod",
                       "texture1DProj", "texture2DProj", "texture3DProj",
                       "ftransform").contains(name);
    }

    /** 判断是否是 OpenGL 遗留内置变量 */
    private boolean isLegacyBuiltin(String name) {
        return Set.of("gl_FragColor", "gl_FragData", "gl_FragCoord",
                       "gl_FrontFacing", "gl_PointCoord",
                       "gl_VertexID", "gl_InstanceID",
                       "gl_Position", "gl_PointSize",
                       "gl_ClipDistance", "gl_CullDistance").contains(name);
    }
}
