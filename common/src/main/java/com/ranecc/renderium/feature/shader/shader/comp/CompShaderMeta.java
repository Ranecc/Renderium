// Renderium - Compute Shader 节点系统
// CompShaderMeta - .comp 文件的参数元数据描述符
//
// 功能：
//   1. 描述一个 GLSL Compute Shader (.comp) 的可调参数
//   2. 定义 push_constant / UBO 的字段布局和 UI 映射
//   3. 从 .comp.meta JSON 文件加载
//
// 配套文件：
//   shaders/compute/xxx.comp        - GLSL Compute Shader 源码
//   shaders/compute/xxx.comp.meta  - 本类描述的 JSON 序列化
//
// 使用方式：
//   CompShaderMeta meta = CompShaderMeta.loadFrom("shaders/compute/composite.comp.meta");
//   // meta.getPushConstantFields() → 所有可调参数
//   // meta.getField("lightIntensity") → { offset:20, type:float, range:[0,5], default:1.0 }

package com.ranecc.renderium.feature.shader.shader.comp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Compute Shader 元数据描述符
 * <p>
 * 对应 `.comp.meta` JSON 文件，描述一个 GLSL Compute Shader 的：
 * <ul>
 *   <li>工作组大小 (local_size)</li>
 *   <li>push_constant 布局（偏移、类型、UI 控件类型）</li>
 *   <li>Descriptor Set 绑定（输入输出纹理/缓冲区）</li>
 * </ul>
 *
 * <h3>.comp.meta JSON 格式示例：</h3>
 * <pre>{@code
 * {
 *   "id": "composite",
 *   "displayName": "画面合成",
 *   "version": "1.0.0",
 *   "workgroupSize": [8, 8, 1],
 *   "pushConstantLayout": {
 *     "size": 64,
 *     "fields": [
 *       { "name": "resolution",    "offset": 0,  "type": "vec2",  "ui": "hidden" },
 *       { "name": "lightDir",      "offset": 8,  "type": "vec3",  "ui": "direction" },
 *       { "name": "lightIntensity","offset": 20, "type": "float", "ui": "slider", "min": 0, "max": 5 },
 *       { "name": "ambientStrength","offset":36,  "type": "float", "ui": "slider", "min":0, "max":1 }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * @since 3.0.0
 */
public final class CompShaderMeta {

    /** Shader 唯一标识符（用于注册到 ShaderNodeRegistry） */
    private final String id;

    /** 显示名称 */
    private final String displayName;

    /** 版本号 */
    private final String version;

    /** 工作组大小 [x, y, z] */
    private final int[] workgroupSize;

    /** push_constant 布局描述 */
    private final PushConstantLayout pushConstantLayout;

    /** Descriptor Set 绑定列表 */
    private final List<BindingDesc> bindings;

    // ==================== 内部数据类 ====================

    /**
     * Push Constant 布局描述
     */
    public static final class PushConstantLayout {
        @SerializedName("size")
        private final int sizeBytes;

        @SerializedName("fields")
        private final List<FieldDesc> fields;

        public PushConstantLayout(int sizeBytes, List<FieldDesc> fields) {
            this.sizeBytes = sizeBytes;
            this.fields = fields != null ? fields : Collections.emptyList();
        }

        public int getSizeBytes() { return sizeBytes; }
        public List<FieldDesc> getFields() { return Collections.unmodifiableList(fields); }
    }

    /**
     * 单个 push_constant 字段描述
     * <p>
     * 映射 GLSL 的 layout(push_constant) struct 中的一个成员，
     * 同时定义其在 UI 上的展示方式和取值范围。
     *
     * <h4>支持的 UI 类型 (ui 字段)：</h4>
     * <table border="1">
     *   <tr><th>值</th><th>控件</th><th>适用类型</th></tr>
     *   <tr><td>"slider"</td><td>滑块</td><td>float, int</td></tr>
     *   <tr><td>"dropdown"</td><td>下拉菜单</td><td>int (枚举)</td></tr>
     *   <tr><td>"checkbox"</td><td>复选框</td><td>bool/int(0|1)</td></tr>
     *   <tr><td>"color"</td><td>颜色选择器</td><td>vec3, vec4</td></tr>
     *   <tr><td>"direction"</td><td>方向向量编辑器</td><td>vec3</td></tr>
     *   <tr><td>"hidden"</td><td>不显示</td><td>任意 (系统自动填充)</td></tr>
     * </table>
     */
    public static final class FieldDesc {

        @SerializedName("name")
        private final String name;

        @SerializedName("offset")
        private final int byteOffset;

        @SerializedName("type")
        private final String glslType;

        @SerializedName("ui")
        private final String uiType;

        @SerializedName("min")
        private final Float minValue;

        @SerializedName("max")
        private final Float maxValue;

        @SerializedName("step")
        private final Float stepValue;

        @SerializedName("default")
        private final Object defaultValue;

        @SerializedName("options")
        private final List<String> enumOptions;

        @SerializedName("description")
        private final String description;

        public FieldDesc(String name, int byteOffset, String glslType,
                          String uiType, Float min, Float max, Float step,
                          Object defaultValue, List<String> options, String desc) {
            this.name = name;
            this.byteOffset = byteOffset;
            this.glslType = glslType;
            this.uiType = uiType != null ? uiType : "hidden";
            this.minValue = min;
            this.maxValue = max;
            this.stepValue = step;
            this.defaultValue = defaultValue;
            this.enumOptions = options;
            this.description = desc;
        }

        /** @return String - 字段名（与 GLSL struct 成员同名） */
        public String getName() { return name; }

        /** @return int - 在 push_constant buffer 中的字节偏移 */
        public int getByteOffset() { return byteOffset; }

        /** @return String - GLSL 类型 ("float", "vec2", "vec3", "vec4", "int", "uint", "bool") */
        public String getGlslType() { return glslType; }

        /** @return String - UI 控件类型 */
        public String getUiType() { return uiType; }

        /** @return float - 最小值（仅 slider 有效） */
        public float getMinValue() { return minValue != null ? minValue : 0f; }

        /** @return float - 最大值（仅 slider 有效） */
        public float getMaxValue() { return maxValue != null ? maxValue : 1f; }

        /** @return float - 步进值 */
        public float getStepValue() { return stepValue != null ? stepValue : 0.01f; }

        /** @return Object - 默认值 */
        public Object getDefaultValue() { return defaultValue; }

        /** @return List<String> - 枚举选项（仅 dropdown 有效） */
        public List<String> getEnumOptions() { return enumOptions; }

        /** @return String - 参数说明文字 */
        public String getDescription() { return description; }

        /** @return int - 该类型的字节大小 */
        public int getTypeSize() {
            return switch (glslType) {
                case "float", "int", "uint" -> 4;
                case "vec2", "ivec2" -> 8;
                case "vec3", "ivec3" -> 12;
                case "vec4", "ivec4", "mat2" -> 16;
                case "mat3" -> 36;
                case "mat4" -> 64;
                default -> 4;
            };
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FieldDesc that)) return false;
            return byteOffset == that.byteOffset && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() { return Objects.hash(name, byteOffset); }

        @Override
        public String toString() {
            return String.format("FieldDesc{name=%s, offset=%d, type=%s, ui=%s}",
                    name, byteOffset, glslType, uiType);
        }
    }

    /**
     * Descriptor Set 绑定描述
     */
    public static final class BindingDesc {
        @SerializedName("binding")
        private final int bindingIndex;

        @SerializedName("name")
        private final String name;

        @SerializedName("type")
        private final String resourceType;

        @SerializedName("format")
        private final String format;

        @SerializedName("access")
        private final String accessMode;

        public BindingDesc(int bindingIndex, String name, String type, String format, String access) {
            this.bindingIndex = bindingIndex;
            this.name = name;
            this.resourceType = type;
            this.format = format;
            this.accessMode = access;
        }

        public int getBindingIndex() { return bindingIndex; }
        public String getName() { return name; }
        public String getResourceType() { return resourceType; }
        public String getFormat() { return format; }
        public String getAccessMode() { return accessMode; }
    }

    // ==================== 构造器 ====================

    public CompShaderMeta(String id, String displayName, String version,
                           int[] workgroupSize, PushConstantLayout pcLayout,
                           List<BindingDesc> bindings) {
        this.id = Objects.requireNonNull(id);
        this.displayName = displayName != null ? id : displayName;
        this.version = version != null ? "1.0.0" : version;
        this.workgroupSize = workgroupSize != null ? workgroupSize : new int[]{8, 8, 1};
        this.pushConstantLayout = pcLayout;
        this.bindings = bindings != null ? bindings : Collections.emptyList();
    }

    // ==================== 加载方法 ====================

    /**
     * 从 .comp.meta JSON 文件加载元数据
     *
     * 【方法参数】
     * @param metaPath Path - .comp.meta 文件的路径
     *
     * @return CompShaderMeta - 解析后的元数据对象
     *
     * @throws IOException 文件不存在或格式错误时抛出
     *
     * 【示例】
     * <pre>
     * CompShaderMeta meta = CompShaderMeta.loadFrom(
     *     Path.of("shaders/compute/composite.comp.meta"));
     * </pre>
     */
    public static CompShaderMeta loadFrom(Path metaPath) throws IOException {
        if (!Files.exists(metaPath)) {
            throw new IOException(".comp.meta 文件不存在: " + metaPath);
        }

        String json = Files.readString(metaPath, StandardCharsets.UTF_8);
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
        return gson.fromJson(json, CompShaderMeta.class);
    }

    /**
     * 从 classpath 资源加载元数据（用于内嵌的 shader）
     */
    public static CompShaderMeta loadFromClasspath(String resourcePath) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(
                CompShaderMeta.class.getClassLoader().getResourceAsStream(resourcePath),
                StandardCharsets.UTF_8)) {
            Gson gson = new GsonBuilder().create();
            return gson.fromJson(reader, CompShaderMeta.class);
        }
    }

    // ==================== 访问器 ====================

    /** @return String - Shader ID */
    public String getId() { return id; }

    /** @return String - 显示名称 */
    public String getDisplayName() { return displayName; }

    /** @return String - 版本号 */
    public String getVersion() { return version; }

    /** @return int[] - 工作组大小 [x, y, z] */
    public int[] getWorkgroupSize() { return workgroupSize.clone(); }

    /** @return int - X 维度工作组大小 */
    public int getWorkgroupX() { return workgroupSize[0]; }

    /** @return int - Y 维度工作组大小 */
    public int getWorkgroupY() { return workgroupSize.length > 1 ? workgroupSize[1] : 1; }

    /** @return int - Z 维度工作组大小 */
    public int getWorkgroupZ() { return workgroupSize.length > 2 ? workgroupSize[2] : 1; }

    /** @return PushConstantLayout - push_constant 布局（可能为 null） */
    public PushConstantLayout getPushConstantLayout() { return pushConstantLayout; }

    /** @return List<BindingDesc> - 所有 Descriptor Set 绑定 */
    public List<BindingDesc> getBindings() { return Collections.unmodifiableList(bindings); }

    /**
     * 按名称查找字段
     *
     * @param name String - 字段名
     * @return FieldDesc - 字段描述，未找到返回 null
     */
    public FieldDesc getField(String name) {
        if (pushConstantLayout == null) return null;
        for (FieldDesc f : pushConstantLayout.fields) {
            if (f.name.equals(name)) return f;
        }
        return null;
    }

    /**
     * 获取所有用户可见的（非 hidden）字段
     *
     * @return List<FieldDesc> - 可在 UI 中展示的字段列表
     */
    public List<FieldDesc> getVisibleFields() {
        if (pushConstantLayout == null) return Collections.emptyList();
        return pushConstantLayout.fields.stream()
                .filter(f -> !"hidden".equals(f.uiType))
                .toList();
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompShaderMeta that)) return false;
        return id.equals(that.id) && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }

    @Override
    public String toString() {
        return String.format("CompShaderMeta{id=%s, name=%s, wg=[%d,%d,%d], pcSize=%d, fields=%d}",
                id, displayName, workgroupSize[0],
                workgroupSize.length > 1 ? workgroupSize[1] : 1,
                workgroupSize.length > 2 ? workgroupSize[2] : 1,
                pushConstantLayout != null ? pushConstantLayout.sizeBytes : 0,
                pushConstantLayout != null ? pushConstantLayout.fields.size() : 0);
    }
}
