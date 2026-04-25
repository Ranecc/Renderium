// Renderium - 光影系统 v2.0
// RGB 光影包解析器 - 仅读取 .rgb 二进制文件（YAML 由 renderium-cli 编译处理）

package com.renderium.module.impl.shader;

import com.renderium.module.ModuleContext;
import com.renderium.pipeline.node.PipelineNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RGB 光影包解析器（纯二进制模式）
 * <p>
 * <b>只读取 .rgb 二进制文件</b>，不解析任何 YAML。
 * <p>
 * YAML 配置文件（pack.yaml / nodes.yaml / params.yaml）是开发时的编排脚本，
 * 必须通过 {@code renderium-cli} (Rust) 工具编译为 .rgb 二进制包后才能被 Java 端加载。
 *
 * <h2>架构原则：</h2>
 * <pre>
 *  开发者 (YAML)           CLI (Rust)              Runtime (Java)
 * ┌─────────────┐      ┌──────────────┐       ┌──────────────────┐
 * │ pack.yaml   │ ──→ │ 编译+打包     │ ───→  │ RgbBinaryReader  │
 * │ nodes.yaml  │      │ (GLSL→SPIR-V)│       │ mmap 零拷贝加载   │
 * │ params.yaml │      │ 序列化元数据  │       │                  │
 * │ *.comp/.frag│      │ 输出 .rgb    │       │ RGBPackParser    │
 * └─────────────┘      └──────────────┘       └──────────────────┘
 * </pre>
 *
 * @see RgbBinaryReader
 * @since 2.1.0
 */
public class RGBPackParser implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(RGBPackParser.class.getName());

    /** .rgb 文件扩展名 */
    public static final String EXTENSION_RGB = ".rgb";

    // ==================== 实例字段 ====================

    private final ModuleContext context;

    /** 当前打开的 .rgb 读取器 */
    private volatile RgbBinaryReader binaryReader;

    /** 解析后的结果缓存 */
    private volatile ParsedPackResult lastParsedResult;

    // ==================== 构造函数 ====================

    /**
     * 创建解析器实例
     *
     * 【方法参数】
     * @param context ModuleContext - 模块上下文
     */
    public RGBPackParser(ModuleContext context) {
        this.context = Objects.requireNonNull(context);
    }

    // ==================== 核心解析 API ====================

    /**
     * 解析 .rgb 光影包（仅支持二进制格式）
     * <p>
     * 使用 {@link RgbBinaryReader} 以 mmap 方式零拷贝加载，
     * 提取着色器、参数、节点状态等全部数据。
     *
     * 【方法参数】
     * @param rgbPath String - .rgb 文件路径（必须以 .rgb 结尾）
     *
     * 【返回值】
     * @return ParsedPackResult - 解析结果，失败返回 null
     */
    public ParsedPackResult parse(String rgbPath) {
        if (rgbPath == null || rgbPath.isBlank()) {
            LOGGER.warning("光影包路径为空");
            return null;
        }

        Path path = Path.of(rgbPath);

        if (!Files.exists(path)) {
            LOGGER.severe(".rgb 文件不存在: " + rgbPath);
            return null;
        }

        if (!rgbPath.toLowerCase().endsWith(EXTENSION_RGB)) {
            LOGGER.severe("不支持的格式: " + rgbPath +
                    " (Java 端仅接受 .rgb 二进制包，请使用 renderium-cli 编译 YAML)");
            return null;
        }

        try {
            return parseBinary(path);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "解析 .rgb 失败: " + rgbPath, e);
            return null;
        }
    }

    /**
     * 从 .rgb 二进制文件解析（内部实现）
     *
     * 【方法参数】
     * @param rgbPath Path - .rgb 文件路径
     *
     * 【返回值】
     * @return ParsedPackResult - 完整解析结果
     *
     * 【异常】
     * @throws IOException 文件读取错误
     */
    public ParsedPackResult parseBinary(Path rgbPath) throws IOException {
        long startTime = System.nanoTime();

        // 关闭之前的读取器
        close();

        // 打开新的 .rgb 读取器（mmap 零拷贝）
        this.binaryReader = RgbBinaryReader.fromFile(rgbPath);

        RgbBinaryReader.RgbHeader header = binaryReader.getHeader();
        RgbBinaryReader.RunMode mode = binaryReader.getRunMode();

        // 1. 提取参数表 → ParameterKnob 列表（含完整元数据）
        List<ParameterKnob> knobs = extractParameterKnobs(binaryReader);

        // 2. 提取节点状态配置（从渲染图数据段反序列化）
        Map<String, PipelineNode.State> nodeStates = extractNodeStates(binaryReader);

        // 3. 提取已编译的 SPIR-V 着色器列表
        List<CompiledShader> shaders = extractCompiledShaders(binaryReader);

        // 4. 从 Header + 渲染图数据构建 Manifest
        RGBPackManifest manifest = buildManifestFromBinary(binaryReader, header);

        ParsedPackResult result = new ParsedPackResult(
                manifest,
                knobs,
                nodeStates,
                shaders,
                binaryReader,
                mode,
                System.nanoTime() - startTime
        );

        this.lastParsedResult = result;

        double elapsedMs = (double)(result.parseTimeNanos()) / 1_000_000.0;
        LOGGER.info(String.format(
                "✓ .rgb 加载完成: %s [v%d|%s] (%d shaders, %d params, %.1fms)",
                rgbPath.getFileName(),
                header.version(),
                mode.getDisplayName(),
                shaders.size(),
                knobs.size(),
                elapsedMs));

        return result;
    }

    // ==================== 数据提取方法 ====================

    /**
     * 从 .rgb 参数表提取 ParameterKnob 列表
     * <p>
     * .rgb 的参数表由 renderium-cli 从 params.yaml 编译而来，
     * 包含完整的类型和范围元数据。
     * 如果 Rust 端已写入完整元数据则直接使用，否则回退到命名约定推断。
     *
     * 【方法参数】
     * @param reader RgbBinaryReader - 已打开的读取器
     *
     * 【返回值】
     * @return List&lt;ParameterKnob&gt; - 参数旋钮列表
     */
    private List<ParameterKnob> extractParameterKnobs(RgbBinaryReader reader) {
        Map<String, Float> rawParams = reader.getParameters();
        List<ParameterKnob> knobs = new ArrayList<>(rawParams.size());

        for (Map.Entry<String, Float> entry : rawParams.entrySet()) {
            String id = entry.getKey();
            float value = entry.getValue();

            // 尝试从 .rgb 渲染图数据段获取完整元数据
            // （如果 Rust 端已写入扩展元数据的话）
            ParameterKnob knob = extractFullMetadata(reader, id, value);

            // 回退：根据命名约定推断类型和范围
            if (knob == null) {
                knob = inferParameterKnob(id, value);
            }

            if (knob != null) {
                knobs.add(knob);
            }
        }

        return knobs;
    }

    /**
     * 尝试从渲染图数据段获取完整参数元数据
     * <p>
     * 当 renderium-cli 写入了扩展的参数定义（包含 type/min/max/step/label）时使用。
     * 返回 null 表示未找到完整元数据，需要回退到推断。
     *
     * TODO: 与 Rust 端对齐序列化格式后实现此方法
     */
    private ParameterKnob extractFullMetadata(RgbBinaryReader reader, String id, float defaultValue) {
        // TODO: 从 render_graph_data 段查找参数元数据
        // 格式待与 Rust 端对齐后确定
        return null;
    }

    /**
     * 根据参数 ID 命名约定推断 ParameterKnob 类型（回退方案）
     * <p>
     * 仅在 .rgb 中没有完整元数据时使用。
     * 推荐在 renderium-cli 编译时写入完整元数据以避免依赖推断。
     */
    private ParameterKnob inferParameterKnob(String id, float value) {
        String lowerId = id.toLowerCase();

        // 布尔开关
        if (lowerId.endsWith("_enabled") || lowerId.endsWith("_active") ||
                lowerId.endsWith("_toggle") || lowerId.endsWith("_on")) {
            return new ParameterKnob(id, formatLabel(id), value > 0.5f);
        }

        // 整数计数类
        if (lowerId.endsWith("_samples") || lowerId.endsWith("_count") ||
                lowerId.endsWith("_iterations") || lowerId.endsWith("_steps") ||
                lowerId.endsWith("_cascades") || lowerId.endsWith("_mips") ||
                lowerId.endsWith("_quality")) {
            int iVal = Math.max(1, Math.round(value));
            int max = switch (true) {
                case boolean s when lowerId.contains("sample") -> 64;
                case boolean s when lowerId.contains("step") || lowerId.contains("iteration") -> 128;
                case boolean s when lowerId.contains("cascade") -> 8;
                case boolean s when lowerId.contains("mip") -> 12;
                default -> 256;
            };
            return new ParameterKnob(id, formatLabel(id),
                    ParameterKnob.KnobType.INT, 1, max, iVal, 1.0f);
        }

        // 分辨率（必须为 2 的幂）
        if (lowerId.endsWith("_resolution") || lowerId.equals("shadow_resolution")) {
            int res = Math.max(512, roundToPowerOf2(Math.round(value)));
            return new ParameterKnob(id, formatLabel(id),
                    ParameterKnob.KnobType.INT, 512, 8192, res, 512.0f);
        }

        // 浮点强度/数量类
        if (lowerId.endsWith("_intensity") || lowerId.endsWith("_strength") ||
                lowerId.endsWith("_amount") || lowerId.endsWith("_exposure") ||
                lowerId.endsWith("_brightness") || lowerId.endsWith("_threshold")) {
            float max = switch (true) {
                case boolean s when lowerId.contains("threshold") -> 10.0f;
                case boolean s when lowerId.contains("exposure") -> 8.0f;
                default -> 3.0f;
            };
            return new ParameterKnob(id, formatLabel(id),
                    ParameterKnob.KnobType.FLOAT, 0.0f, max, value, 0.01f);
        }

        // 半径/距离类
        if (lowerId.endsWith("_radius") || lowerId.endsWith("_distance") ||
                lowerId.endsWith("_range") || lowerId.contains("focal")) {
            return new ParameterKnob(id, formatLabel(id),
                    ParameterKnob.KnobType.FLOAT, 0.01f, 100.0f, value, 0.1f);
        }

        // 角度/比例类
        if (lowerId.endsWith("_angle") || lowerId.endsWith("_fov") ||
                lowerId.endsWith("_lambda") || lowerId.contains("saturation") ||
                lowerId.contains("contrast") || lowerId.contains("gamma")) {
            return new ParameterKnob(id, formatLabel(id),
                    ParameterKnob.KnobType.FLOAT, 0.0f, 5.0f, value, 0.01f);
        }

        // 默认通用浮点
        return new ParameterKnob(id, formatLabel(id),
                ParameterKnob.KnobType.FLOAT, 0.0f, 10.0f, value, 0.01f);
    }

    /**
     * 从 .rgb 渲染图数据段提取节点状态配置
     * <p>
     * 节点状态由 renderium-cli 从 nodes.yaml 编译并序列化到此段中。
     * Java 端按约定的二进制格式反序列化。
     *
     * 格式（待与 Rust 端最终确认）：
     * [nodeCount: u32] ([nodeIdLen: u32][nodeId: UTF-8][state: u8])*
     *
     * state 值: 0=DISABLED, 1=ENABLED, 2=OFFICIAL, 3=BYPASS
     */
    private Map<String, PipelineNode.State> extractNodeStates(RgbBinaryReader reader) {
        byte[] graphData = reader.getRenderGraphData();
        if (graphData == null || graphData.length == 0) {
            return Collections.emptyMap();
        }

        Map<String, PipelineNode.State> states = new LinkedHashMap<>();

        try {
            ByteBuffer buf = ByteBuffer.wrap(graphData).order(java.nio.ByteOrder.LITTLE_ENDIAN);

            if (buf.remaining() < 4) return states;

            int count = buf.getInt();

            for (int i = 0; i < count && buf.remaining() >= 5; i++) {
                int nameLen = buf.getInt();
                if (buf.remaining() < nameLen + 1) break;

                byte[] nameBytes = new byte[nameLen];
                buf.get(nameBytes);
                String nodeId = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

                int stateByte = buf.get() & 0xFF;
                PipelineNode.State state = switch (stateByte) {
                    case 0 -> PipelineNode.State.DISABLED;
                    case 1 -> PipelineNode.State.ENABLED;
                    case 2 -> PipelineNode.State.OFFICIAL;
                    case 3 -> PipelineNode.State.BYPASS;
                    default -> PipelineNode.State.ENABLED;
                };

                states.put(nodeId, state);
            }

            if (!states.isEmpty()) {
                LOGGER.fine("从 .rgb 提取节点状态: " + states.size() + " 个");
            }

        } catch (Exception e) {
            LOGGER.warning("反序列化节点状态失败: " + e.getMessage() +
                    " (将使用默认状态)");
        }

        return states;
    }

    /**
     * 从 .rgb 读取器提取已编译的 SPIR-V 着色器列表
     */
    private List<CompiledShader> extractCompiledShaders(RgbBinaryReader reader) {
        List<String> names = reader.getShaderNames();
        List<CompiledShader> shaders = new ArrayList<>(names.size());

        for (String name : names) {
            byte[] spirvData = reader.getSpirvData(name);
            if (spirvData != null && spirvData.length > 0) {

                ShaderStage stage = inferStageFromName(name);

                shaders.add(new CompiledShader(
                        name,
                        stage,
                        spirvData,
                        "main",
                        true
                ));
            }
        }

        return shaders;
    }

    /**
     * 从着色器名称推断阶段类型
     */
    private ShaderStage inferStageFromName(String name) {
        String lower = name.toLowerCase();

        if (lower.contains("vert") || lower.contains("vertex") || lower.contains("gbuffer"))
            return ShaderStage.VERTEX;
        if (lower.contains("frag") || lower.contains("pixel") || lower.contains("tonemap") ||
                lower.contains("bloom") || lower.contains("post") || lower.contains("fxaa"))
            return ShaderStage.FRAGMENT;
        if (lower.contains("geom") || lower.contains("geometry"))
            return ShaderStage.GEOMETRY;

        return ShaderStage.COMPUTE;
    }

    // ==================== Manifest 构建 ====================

    /**
     * 从 .rgb 二进制数据构建 RGBPackManifest
     * <p>
     * 包名、版本等信息由 renderium-cli 在编译时嵌入到渲染图数据段头部。
     */
    private RGBPackManifest buildManifestFromBinary(RgbBinaryReader reader,
                                                     RgbBinaryReader.RgbHeader header) {
        byte[] graphData = reader.getRenderGraphData();

        String name = "Unknown Pack";
        String version = "v" + header.version();
        String author = "Unknown";
        String description = "";

        // 尝试从渲染图数据段提取包信息
        if (graphData != null && graphData.length > 0) {
            String extracted = extractUtf8String(graphData, 0, 128);
            if (extracted != null && !extracted.isEmpty()) {
                name = extracted.split("\n")[0].trim();
                if (name.length() > 64) name = name.substring(0, 64);
            }
        }

        return new RGBPackManifest(
                name, version, version.replaceFirst("v", ""),
                author, description,
                ">=1.21", ">=2.1",
                header.getRunMode().name(),
                List.of(),
                Map.of()
        );
    }

    /** 从字节数组提取 UTF-8 字符串（用于包名等元数据）*/
    private static String extractUtf8String(byte[] data, int offset, int maxLen) {
        int end = Math.min(offset + maxLen, data.length);
        int start = offset;

        while (start < end && data[start] <= 0x20) start++;

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            byte b = data[i];
            if (b >= 0x20 && b < 0x7F) {
                sb.append((char) b);
            } else if (sb.length() > 2) {
                break;
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    // ==================== 工具方法 ====================

    /** ID → 显示标签转换 (snake_case → Title Case) */
    private static String formatLabel(String id) {
        if (id == null || id.isEmpty()) return "Unknown";

        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;

        for (char c : id.toCharArray()) {
            if (c == '_') {
                sb.append(' ');
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    /** 四舍五入到最近的 2 的幂 */
    private static int roundToPowerOf2(int value) {
        if (value <= 0) return 1;
        int power = Integer.highestOneBit(value);
        if (value > power + (power >> 1)) power <<= 1;
        return power;
    }

    // ==================== 查询 API ====================

    public ParsedPackResult getLastResult() { return lastParsedResult; }
    public boolean hasActiveBinaryReader() { return binaryReader != null && !binaryReader.isClosed(); }

    // ==================== 资源管理 ====================

    @Override
    public void close() {
        if (binaryReader != null) {
            try {
                binaryReader.close();
            } catch (Exception e) {
                LOGGER.warning("关闭 RgbBinaryReader 时出错: " + e.getMessage());
            }
            binaryReader = null;
        }
    }

    // ==================== 内部数据结构 ====================

    /** 已编译的着色器记录 */
    public record CompiledShader(
            String name,
            ShaderStage stage,
            byte[] spirvData,
            String entryPoint,
            boolean isCompiled
    ) {}

    /** 着色器阶段枚举 */
    public enum ShaderStage {
        VERTEX, FRAGMENT, GEOMETRY, COMPUTE
    }

    /** 解析结果（完整的 .rgb 包数据）*/
    public record ParsedPackResult(
            RGBPackManifest manifest,
            List<ParameterKnob> parameterKnobs,
            Map<String, PipelineNode.State> nodeStates,
            List<CompiledShader> compiledShaders,
            RgbBinaryReader binaryReader,
            RgbBinaryReader.RunMode runMode,
            long parseTimeNanos
    ) {
        public int getShaderCount() { return compiledShaders.size(); }
        public int getParameterCount() { return parameterKnobs.size(); }
        public boolean hasCompiledShaders() {
            return compiledShaders.stream().anyMatch(s -> s.isCompiled());
        }
        public double getParseTimeMs() { return parseTimeNanos / 1_000_000.0; }
    }
}
