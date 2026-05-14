// Renderium - Blaze3D Shader 转译模块
// GLSL 轻量级词法分析器 - Token 级精确解析，区分代码/注释/字符串

package com.ranecc.renderium.feature.blaze3d.shader;

import java.util.*;

/**
 * GLSL lightweight lexer for token-level parsing.
 * <p>
 * Does not aim to be a complete GLSL compiler frontend, but focuses on one thing:
 * <b>precisely distinguishing "code tokens that should be replaced" from
 * "comments/strings/macro concatenations that must not be touched".</b>
 *
 * <h2>Token Type Classification:</h2>
 * <ul>
 * <li>KEYWORD:       attribute, uniform             -> REPLACABLE</li>
 * <li>IDENTIFIER:    texture2D, gl_FragColor         -> REPLACABLE</li>
 * <li>LITERAL:       "attribute", 123                -> NOT REPLACEABLE</li>
 * <li>COMMENT_LINE:  // example comment               -> NOT REPLACEABLE</li>
 * <li>COMMENT_BLOCK: block comment                   -> NOT REPLACEABLE</li>
 * <li>PREPROCESSOR:  include, define                 -> SPECIAL HANDLING</li>
 * </ul>
 *
 * @see GlslToVkTransformer performs safe syntax transformation based on Token list
 * @since 3.0.0
 */
public final class GlslLexer {

    // ==================== 常量: GLSL 关键字集合 ====================

    /** GLSL 关键字集合 (用于快速识别) */
    private static final Set<String> GLSL_KEYWORDS = Set.of(
            // 类型关键字
            "void", "bool", "int", "uint", "float", "double",
            "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4",
            "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4",
            "mat2", "mat3", "mat4", "mat2x3", "mat3x2", "mat2x4", "mat4x2", "mat3x4", "mat4x3",
            "sampler2D", "sampler3D", "samplerCube", "samplerCubeShadow",
            "sampler1D", "sampler1DArray", "sampler2DArray", "sampler2DMS",
            "isampler2D", "usampler2D", "isampler3D", "usampler3D",
            "image2D", "image3D", "iimage2D", "uimage2D",
            // 存储限定符 (OpenGL → Vulkan 目标)
            "attribute", "varying", "in", "out", "inout",
            "uniform", "const", "flat", "smooth", "noperspective",
            "centroid", "sample", "patch",
            // 精度限定符
            "lowp", "mediump", "highp", "precision",
            // 流程控制
            "if", "else", "for", "while", "do", "switch", "case", "default",
            "break", "continue", "return", "discard",
            // 其他
            "struct", "layout", "subroutine", "invariant", "coherent", "volatile",
            "readonly", "writeonly", "restrict", "atomic_uint", "shared"
    );

    /** OpenGL 遗留函数名 (需要转换为 Vulkan 语法的) */
    private static final Set<String> LEGACY_FUNCTIONS = Set.of(
            "texture2D", "texture2DLod", "texture2DProj",
            "textureCube", "textureCubeLod",
            "texture3D", "texture3DLod",
            "texture1D", "texture1DLod",
            "texture1DProj", "texture2DProj", "texture3DProj",
            "texelFetchOffset", "textureGrad", "textureGradOffset",
            "ftransform"
    );

    /** OpenGL 内置变量名 (需要转换的) */
    private static final Set<String> LEGACY_BUILTINS = Set.of(
            "gl_FragColor", "gl_FragData", "gl_FragCoord",
            "gl_FrontFacing", "gl_PointCoord",
            "gl_VertexID", "gl_InstanceID",
            "gl_Position", "gl_PointSize",
            "gl_ClipDistance", "gl_CullDistance"
    );

    // ==================== 字段 ====================

    /** 源代码 */
    private final String source;

    /** 源代码长度 */
    private final int length;

    /** 当前扫描位置 */
    private int position;

    /** 当前行号 */
    private int currentLine;

    /** 当前列号 */
    private int currentColumn;

    // ==================== 构造函数 ====================

    /**
     * 构造词法分析器
     *
     * @param source GLSL 源代码 (非 null)
     */
    public GlslLexer(String source) {
        this.source = Objects.requireNonNull(source, "source 不能为 null");
        this.length = source.length();
        this.position = 0;
        this.currentLine = 1;
        this.currentColumn = 1;
    }

    // ==================== 公共 API ====================

    /**
     * 执行词法分析，返回所有 Token 列表。
     *
     * <h3>算法:</h3>
     * <ol>
     *   <li>跳过前导空白</li>
     *   <li>根据当前字符判断 Token 类型</li>
     *   <li>记录 Token 的行号和列号 (用于错误报告)</li>
     *   <li>重复直到 EOF</li>
     * </ol>
     *
     * @return 有序 Token 列表 (保留源代码顺序)
     */
    public List<GlslToken> tokenize() {
        List<GlslToken> tokens = new ArrayList<>();

        while (position < length) {
            // 跳过空白字符 (保留 WHITESPACE token 用于重建源码)
            if (Character.isWhitespace(peek())) {
                int startLine = currentLine;
                int startCol = currentColumn;
                StringBuilder ws = new StringBuilder();
                while (position < length && Character.isWhitespace(peek())) {
                    ws.append(consume());
                }
                tokens.add(new GlslToken(TokenType.WHITESPACE, ws.toString(), startLine, startCol));
                continue;
            }

            char c = peek();

            // 注释检测
            if (c == '/' && position + 1 < length) {
                char next = source.charAt(position + 1);
                if (next == '/') {
                    tokens.add(scanLineComment());
                    continue;
                } else if (next == '*') {
                    tokens.add(scanBlockComment());
                    continue;
                }
            }

            // 预处理指令
            if (c == '#') {
                tokens.add(scanPreprocessor());
                continue;
            }

            // 字符串字面量
            if (c == '"') {
                tokens.add(scanStringLiteral());
                continue;
            }

            // 数字字面量
            if (Character.isDigit(c) ||
                    (c == '.' && position + 1 < length &&
                            Character.isDigit(source.charAt(position + 1)))) {
                tokens.add(scanNumberLiteral());
                continue;
            }

            // 标识符或关键字
            if (Character.isLetter(c) || c == '_') {
                tokens.add(scanIdentifierOrKeyword());
                continue;
            }

            // 运算符/标点
            tokens.add(scanOperator());
        }

        tokens.add(new GlslToken(TokenType.EOF, "", currentLine, currentColumn));
        return tokens;
    }

    // ==================== 扫描方法 (内部) ====================

    /** 扫描单行注释 */
    private GlslToken scanLineComment() {
        int startLine = currentLine;
        int startCol = currentColumn;
        consume(); // '/'
        consume(); // '/'
        StringBuilder sb = new StringBuilder("//");

        while (position < length && peek() != '\n') {
            sb.append(consume());
        }
        return new GlslToken(TokenType.COMMENT_LINE, sb.toString(), startLine, startCol);
    }

    /** 扫描多行注释 */
    private GlslToken scanBlockComment() {
        int startLine = currentLine;
        int startCol = currentColumn;
        consume(); // '/'
        consume(); // '*'
        StringBuilder sb = new StringBuilder("/*");

        while (position < length) {
            if (peek() == '*' && position + 1 < length &&
                    source.charAt(position + 1) == '/') {
                sb.append(consume()); // '*'
                sb.append(consume()); // '/'
                break;
            }
            sb.append(consume());
        }
        return new GlslToken(TokenType.COMMENT_BLOCK, sb.toString(), startLine, startCol);
    }

    /** 扫描预处理指令 (从 # 到行尾) */
    private GlslToken scanPreprocessor() {
        int startLine = currentLine;
        int startCol = currentColumn;
        StringBuilder sb = new StringBuilder();

        do {
            sb.append(consume());
        } while (position < length && peek() != '\n' && peek() != '\r');

        return new GlslToken(TokenType.PREPROCESSOR, sb.toString(), startLine, startCol);
    }

    /** 扫描字符串字面量 (支持转义字符) */
    private GlslToken scanStringLiteral() {
        int startLine = currentLine;
        int startCol = currentColumn;
        consume(); // 开头 '"'
        StringBuilder sb = new StringBuilder("\"");

        while (position < length && peek() != '"') {
            if (peek() == '\\') {
                sb.append(consume()); // 反斜杠
                if (position < length) {
                    sb.append(consume()); // 转义字符
                }
            } else {
                sb.append(consume());
            }
        }

        if (position < length) {
            sb.append(consume()); // 结尾 '"'
        }
        return new GlslToken(TokenType.STRING_LITERAL, sb.toString(), startLine, startCol);
    }

    /** 扫描数字字面量 (支持整数/浮点数/科学计数法/十六进制) */
    private GlslToken scanNumberLiteral() {
        int startLine = currentLine;
        int startCol = currentColumn;
        StringBuilder sb = new StringBuilder();

        // 十六进制前缀
        if (peek() == '0' && position + 1 < length &&
                (source.charAt(position + 1) == 'x' || source.charAt(position + 1) == 'X')) {
            sb.append(consume()); // '0'
            sb.append(consume()); // 'x'/'X'
            while (position < length && isHexDigit(peek())) {
                sb.append(consume());
            }
            return new GlslToken(TokenType.NUMBER_LITERAL, sb.toString(), startLine, startCol);
        }

        // 整数部分
        while (position < length && Character.isDigit(peek())) {
            sb.append(consume());
        }

        // 小数部分
        if (position < length && peek() == '.') {
            sb.append(consume());
            while (position < length && Character.isDigit(peek())) {
                sb.append(consume());
            }
        }

        // 指数部分
        if (position < length && (peek() == 'e' || peek() == 'E')) {
            sb.append(consume());
            if (position < length && (peek() == '+' || peek() == '-')) {
                sb.append(consume());
            }
            while (position < length && Character.isDigit(peek())) {
                sb.append(consume());
            }
        }

        // 后缀 (f, u, lf 等)
        if (position < length &&
                (peek() == 'f' || peek() == 'F' || peek() == 'u' || peek() == 'U' ||
                        peek() == 'l' || peek() == 'L')) {
            sb.append(consume());
            if (position < length && (peek() == 'f' || peek() == 'F')) {
                sb.append(consume());
            }
        }

        return new GlslToken(TokenType.NUMBER_LITERAL, sb.toString(), startLine, startCol);
    }

    /** 扫描标识符或关键字 */
    private GlslToken scanIdentifierOrKeyword() {
        int startLine = currentLine;
        int startCol = currentColumn;
        StringBuilder sb = new StringBuilder();

        while (position < length &&
                (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            sb.append(consume());
        }

        String text = sb.toString();
        TokenType type = GLSL_KEYWORDS.contains(text) ? TokenType.KEYWORD : TokenType.IDENTIFIER;

        return new GlslToken(type, text, startLine, startCol);
    }

    /** 扫描运算符/标点 (单字符或多字符) */
    private GlslToken scanOperator() {
        int startLine = currentLine;
        int startCol = currentColumn;
        char c = consume();

        // 多字符运算符
        if (position < length) {
            char next = peek();
            String twoChar = String.valueOf(c) + next;

            if (Set.of("++", "--", "<<", ">>", "<=", ">=", "==", "!=",
                            "&&", "||", "+=", "-=", "*=", "/=", "%=",
                            "&=", "|=", "^=", "^^", "::<").contains(twoChar)) {
                consume(); // 消耗第二个字符
                return new GlslToken(TokenType.OPERATOR, twoChar, startLine, startCol);
            }
        }
        return new GlslToken(TokenType.OPERATOR, String.valueOf(c), startLine, startCol);
    }

    // ==================== 辅助方法 ====================

    /** 查看当前字符但不消耗 */
    private char peek() {
        return source.charAt(position);
    }

    /** 消耗当前字符并更新行列号 */
    private char consume() {
        char c = source.charAt(position++);
        if (c == '\n') {
            currentLine++;
            currentColumn = 1;
        } else {
            currentColumn++;
        }
        return c;
    }

    /** 是否是十六进制数字 */
    private boolean isHexDigit(char c) {
        return Character.isDigit(c) ||
                (c >= 'a' && c <= 'f') ||
                (c >= 'A' && c <= 'F');
    }

    // ==================== 静态工具方法 ====================

    /** 判断指定文本是否是 OpenGL 遗留函数名 */
    public static boolean isLegacyFunction(String name) {
        return LEGACY_FUNCTIONS.contains(name);
    }

    /** 判断指定文本是否是 OpenGL 遗留内置变量 */
    public static boolean isLegacyBuiltin(String name) {
        return LEGACY_BUILTINS.contains(name);
    }

    /** 判断指定文本是否是 GLSL 关键字 */
    public static boolean isKeyword(String text) {
        return GLSL_KEYWORDS.contains(text);
    }

    // ==================== 内部数据结构 ====================

    /**
     * Token 类型枚举
     */
    public enum TokenType {
        /** 关键字 (attribute, uniform, varying 等) — 可参与替换 */
        KEYWORD,
        /** 标识符 (函数名、变量名) — 可参与替换 */
        IDENTIFIER,
        /** 数字字面量 — 不可替换 */
        NUMBER_LITERAL,
        /** 字符串字面量 ("...") — 不可替换 */
        STRING_LITERAL,
        /** 单行注释 (// ...) — 不可替换 */
        COMMENT_LINE,
        /** 多行注释 (/ * ... * /) — 不可替换 */
        COMMENT_BLOCK,
        /** 预处理指令 (#include, #define 等) — 特殊处理 */
        PREPROCESSOR,
        /** 运算符/标点 — 一般不替换 */
        OPERATOR,
        /** 空白字符 — 跳过 */
        WHITESPACE,
        /** 文件结束 */
        EOF
    }

    /**
     * GLSL Token 数据结构
     *
     * @param type Token 类型
     * @param text 原始文本内容
     * @param line 起始行号 (1-based)
     * @param column 起始列号 (1-based)
     */
    public record GlslToken(TokenType type, String text, int line, int column) {

        /** 是否可参与语法替换 (仅 KEYWORD 和 IDENTIFIER) */
        public boolean isReplaceable() {
            return type == TokenType.KEYWORD || type == TokenType.IDENTIFIER;
        }

        /** 是否是 OpenGL 遗留关键字 (需要转换: attribute, varying) */
        public boolean isLegacyKeyword() {
            return type == TokenType.KEYWORD &&
                    ("attribute".equals(text) || "varying".equals(text));
        }

        /** 是否是 OpenGL 遗留函数 (需要转换: texture2D 等) */
        public boolean isLegacyFunction() {
            return type == TokenType.IDENTIFIER && LEGACY_FUNCTIONS.contains(text);
        }

        /** 是否是 OpenGL 遗留内置变量 (需要转换: gl_FragColor 等) */
        public boolean isLegacyBuiltin() {
            return type == TokenType.IDENTIFIER && LEGACY_BUILTINS.contains(text);
        }
    }
}
