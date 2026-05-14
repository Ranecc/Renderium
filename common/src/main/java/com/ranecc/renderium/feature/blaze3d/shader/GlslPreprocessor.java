// Renderium - Blaze3D Shader 转译模块
// GLSL 预处理器 - #include 指令递归展开

package com.ranecc.renderium.feature.blaze3d.shader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * GLSL 预处理器 — 处理 {@code #include} 指令递归展开。
 *
 * <h2>职责：</h2>
 * <ul>
 *   <li>递归展开 {@code #include} 指令</li>
 *   <li>循环引用检测 (防止无限递归)</li>
 *   <li>搜索路径管理</li>
 *   <li>行号标记保留 (用于错误定位)</li>
 * </ul>
 *
 * <h2>使用示例：</h2>
 * <pre>{@code
 * GlslPreprocessor pp = new GlslPreprocessor();
 * pp.addSearchPath(Paths.get("shaders/lib"));
 * pp.addSearchPath(Paths.get("shaders/world0"));
 *
 * String expanded = pp.preprocess(
 *     Paths.get("shaders/gbuffers_terrain.glsl"),
 *     Map.of("SHADOW_QUALITY", "2", "OVERWORLD", "1")
 * );
 * }</pre>
 *
 * @see GlslLexer 预处理后的代码交给词法分析器
 * @since 3.0.0
 */
public final class GlslPreprocessor {

    private static final Logger LOGGER = Logger.getLogger("Renderium-GlslPreproc");

    /** 最大 include 嵌套深度 */
    private static final int MAX_INCLUDE_DEPTH = 64;

    /** Include 栈 (用于循环引用检测) */
    private final Deque<Path> includeStack = new ArrayDeque<>();

    /** 搜索路径列表 */
    private final List<Path> searchPaths = new ArrayList<>();

    // ==================== 配置 API ====================

    /**
     * 添加搜索路径。
     * 当 {@code #include} 中的文件相对于当前文件找不到时，
     * 会按添加顺序在搜索路径中查找。
     *
     * @param path 搜索目录路径 (必须存在且为目录)
     * @throws IllegalArgumentException 如果路径不是目录
     */
    public void addSearchPath(Path path) {
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("搜索路径必须是目录: " + path);
        }
        searchPaths.add(path.toAbsolutePath());
        LOGGER.fine("添加搜索路径: " + path.toAbsolutePath());
    }

    /**
     * 清除所有搜索路径
     */
    public void clearSearchPaths() {
        searchPaths.clear();
    }

    /** 获取当前配置的搜索路径列表 (不可修改副本) */
    public List<Path> getSearchPaths() {
        return List.copyOf(searchPaths);
    }

    // ==================== 核心公共 API ====================

    /**
     * 预处理单个 shader 文件，展开所有 {@code #include} 指令。
     *
     * <h3>参数说明：</h3>
     * <table>
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>shaderPath</td><td>Path</td><td>原始 shader 文件路径 (非 null)</td></tr>
     *   <tr><td>defines</td><td>Map&lt;String,String&gt;</td><td>预定义宏 (可为 null)</td></tr>
     *   <tr><td>返回值</td><td>String</td><td>展开后的 GLSL 代码</td></tr>
     * </table>
     *
     * @param shaderPath 原始 shader 文件路径 (非 null)
     * @param defines 预定义宏映射 (可为 null)
     * @return 展开后的 GLSL 代码
     * @throws ShaderPreprocessException 预处理失败时抛出 (循环引用/文件不存在等)
     */
    public String preprocess(Path shaderPath, Map<String, String> defines)
            throws ShaderPreprocessException {

        Objects.requireNonNull(shaderPath, "shaderPath 不能为 null");

        if (!Files.exists(shaderPath)) {
            throw new ShaderPreprocessException("Shader 文件不存在: " + shaderPath);
        }

        // 重置状态
        includeStack.clear();
        Map<String, String> macroDefines =
                defines != null ? new HashMap<>(defines) : new HashMap<>();

        long startTime = System.nanoTime();
        String result = processRecursive(shaderPath.toAbsolutePath(), macroDefines, 0);
        long elapsed = System.nanoTime() - startTime;

        LOGGER.fine(String.format("预处理完成: %s (%d us, %d 层 include)",
                shaderPath.getFileName(), elapsed / 1000, includeStack.size()));

        return result;
    }

    // ==================== 内部实现 ====================

    /**
     * 递归预处理实现。
     *
     * @param currentFile 当前处理的文件 (绝对路径)
     * @param defines 宏定义 (会被子 include 共享拷贝)
     * @param depth 当前嵌套深度
     * @return 预处理后的代码
     */
    private String processRecursive(Path currentFile,
                                    Map<String, String> defines,
                                    int depth) throws ShaderPreprocessException {

        // 深度保护
        if (depth > MAX_INCLUDE_DEPTH) {
            throw new ShaderPreprocessException(
                    "Include 深度超过 " + MAX_INCLUDE_DEPTH +
                    "，可能存在循环引用或过深的 include 链。" +
                    "当前栈: " + formatStack());
        }

        // 循环引用检测
        if (includeStack.contains(currentFile)) {
            throw new ShaderPreprocessException(
                    "循环 include 检测: " + currentFile + "已在栈中: " + formatStack());
        }

        // 读取文件
        String content;
        try {
            content = Files.readString(currentFile);
        } catch (IOException e) {
            throw new ShaderPreprocessException("无法读取文件: " + currentFile, e);
        }

        // 入栈
        includeStack.push(currentFile);

        try {
            return processContent(currentFile, content, defines, depth);
        } finally {
            includeStack.pop(); // 确保出栈
        }
    }

    /**
     * 处理文件内容，逐行解析指令
     */
    private String processContent(Path currentFile, String content,
                                  Map<String, String> defines, int depth)
            throws ShaderPreprocessException {

        StringBuilder output = new StringBuilder();
        output.append("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // 插入行号标记 (用于编译错误定位)
            output.append("#line ").append(i + 1).append(" \"")
                  .append(currentFile.toAbsolutePath().toString().replace("\\", "/"))
                  .append("\"\n");

            if (trimmed.startsWith("#include")) {
                // 处理 #include 指令 → 递归展开
                Path included = resolveInclude(trimmed, currentFile);
                String includedContent = processRecursive(included,
                        new HashMap<>(defines), depth + 1);
                output.append(includedContent);
                output.append("\n");
            } else if (trimmed.startsWith("#define")) {
                // 记录宏定义
                processDefine(trimmed, defines);
                output.append("\n");
            } else if (trimmed.startsWith("#undef")) {
                processUndef(trimmed, defines);
                output.append("\n");
            } else {
                // 普通行原样输出
                output.append("\n");
            }
        }

        return output.toString();
    }

    /**
     * 解析并解析 #include 路径。
     * 支持两种格式:
     * <ul>
     *   <li>{@code #include "relative/path.glsl"} — 相对路径</li>
     *   <li>{@code #include &lt;system/path.glsl&gt;} — 系统路径 (搜索路径)</li>
     * </ul>
     */
    private Path resolveInclude(String directive, Path currentFile)
            throws ShaderPreprocessException {

        String includePath = extractIncludePath(directive);
        if (includePath == null) {
            throw new ShaderPreprocessException(
                    "无效的 #include 语法: " + directive +
                            " (文件: " + currentFile + ")");
        }

        // 1. 相对于当前文件的目录
        Path baseDir = currentFile.getParent();
        Path relative = baseDir.resolve(includePath);
        if (Files.exists(relative) && Files.isRegularFile(relative)) {
            return relative.toAbsolutePath();
        }

        // 2. 在搜索路径中按顺序查找
        for (Path searchPath : searchPaths) {
            Path full = searchPath.resolve(includePath);
            if (Files.exists(full) && Files.isRegularFile(full)) {
                return full.toAbsolutePath();
            }
        }

        // 找不到
        throw new ShaderPreprocessException(
                "无法找到 include 文件: " + includePath +
                        "搜索路径: " + searchPaths);
    }

    /**
     * 从 #include 行提取路径字符串
     */
    private String extractIncludePath(String directive) {
        String path = directive.substring("#include".length()).trim();

        if (path.startsWith("\"") && path.endsWith("\"")) {
            return path.substring(1, path.length() - 1);
        } else if (path.startsWith("<") && path.endsWith(">")) {
            return path.substring(1, path.length() - 1);
        }

        return null;
    }

    /**
     * 处理 #define 指令 (记录到 defines 映射)
     */
    private void processDefine(String directive, Map<String, String> defines) {
        String body = directive.substring("#define".length()).trim();

        // 分离名称和值
        String[] parts = body.split("\\s+", 2);

        if (parts.length > 0) {
            String key = parts[0].trim();
            String value = parts.length > 1 ? parts[1].trim() : "1";
            defines.put(key, value);
        }
    }

    /**
     * 处理 #undef 指令
     */
    private void processUndef(String directive, Map<String, String> defines) {
        String name = directive.substring("#undef".length()).trim();
        defines.remove(name);
    }

    /**
     * 格式化 include 栈用于错误报告
     */
    private String formatStack() {
        StringBuilder sb = new StringBuilder();
        for (Path p : includeStack) {
            sb.append("\n").append(p.getFileName());
        }
        return sb.toString();
    }
}

/**
 * 预处理异常
 */
class ShaderPreprocessException extends Exception {

    public ShaderPreprocessException(String message) {
        super(message);
    }

    public ShaderPreprocessException(String message, Throwable cause) {
        super(message, cause);
    }
}
