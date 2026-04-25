// Renderium - 光影模块
// RGB 光影包清单文件模型
// 对应 pack.yaml 的 Java 表示
// 包含元数据、效果列表、默认配置

package com.renderium.module.impl.shader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.*;

/**
 * RGB Pack 清单文件 (pack.yaml) 的完整 Java 模型
 *
 * <p>表示 .rgb 光影包的完整清单信息，包括：
 * <ul>
 *   <li>元数据（名称、版本、作者等）</li>
 *   <li>兼容性要求（Minecraft 版本、Renderium 版本）</li>
 *   <li>效果列表（有序，支持依赖关系）</li>
 *   <li>全局设置</li>
 * </ul>
 *
 * <h2>使用示例：</h2>
 * <pre>{@code
 * // 从 InputStream 解析 pack.yaml
 * try (InputStream is = Files.newInputStream(packPath)) {
 *     RGBPackManifest manifest = RGBPackManifest.fromYaml(is);
 *
 *     // 验证依赖关系
 *     manifest.validate();
 *
 *     // 获取拓扑排序后的执行顺序
 *     List<EffectEntry> orderedEffects = manifest.getExecutionOrder();
 *
 *     // 检查版本兼容性
 *     if (!manifest.isCompatibleWith("1.21.1", "1.0.0")) {
 *         throw new VersionIncompatibilityException("版本不兼容");
 *     }
 * }
 * }</pre>
 *
 * @see EffectEntry
 * @see ShaderFile
 * @author Renderium Team
 * @since 2.0.0
 * @version 2.0 (完整重构)
 */
public class RGBPackManifest {

    private static final Logger LOGGER = Logger.getLogger(RGBPackManifest.class.getName());

    // ==================== 元数据字段 ====================

    /** 光影包名称 */
    private final String name;

    /** 语义化版本号 (major.minor.patch) */
    private final String version;

    /** 作者名称 */
    private final String author;

    /** 可选描述文本 */
    private final String description;

    /** 许可证类型 */
    private final String license;

    // ==================== 兼容性字段 ====================

    /** Minecraft 版本范围（如 "1.21+" 或 "1.20.4-1.21.1"）*/
    private final String minecraftVersion;

    /** Renderium 最低版本要求（如 "1.0.0+"）*/
    private final String renderiumVersion;

    /** 权限模式（SANDBOX / INJECTION / TAKEOVER）*/
    private final String permission;

    // ==================== 效果与配置 ====================

    /** 效果列表（保持声明顺序）*/
    private final List<EffectEntry> effects;

    /** 全局默认配置（键值对）*/
    private final Map<String, Object> settings;

    // ==================== 构造函数 ====================

    /**
     * 完整构造函数
     *
     * @param name             光影包名称（必填）
     * @param version          语义化版本号（必填）
     * @param author           作者名称（必填）
     * @param description      可选描述（可为 null）
     * @param license          许可证（可为 null）
     * @param minecraftVersion MC 版本范围（必填）
     * @param renderiumVersion Renderium 版本要求（必填）
     * @param permission       权限模式（默认 "SANDBOX"）
     * @param effects          效果列表（不可为 null）
     * @param settings         全局配置（可为空 map）
     */
    public RGBPackManifest(String name, String version, String author,
                           String description, String license,
                           String minecraftVersion, String renderiumVersion,
                           String permission,
                           List<EffectEntry> effects,
                           Map<String, Object> settings) {
        this.name = Objects.requireNonNull(name, "name 不能为 null");
        this.version = Objects.requireNonNull(version, "version 不能为 null");
        this.author = Objects.requireNonNull(author, "author 不能为 null");
        this.description = description != null ? description : "";
        this.license = license != null ? license : "Unknown";
        this.minecraftVersion = Objects.requireNonNull(minecraftVersion);
        this.renderiumVersion = Objects.requireNonNull(renderiumVersion);
        this.permission = permission != null ? permission : "SANDBOX";
        this.effects = Collections.unmodifiableList(
            new ArrayList<>(Objects.requireNonNull(effects))
        );
        this.settings = Collections.unmodifiableMap(
            new HashMap<>(settings != null ? settings : new HashMap<>())
        );
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 从 YAML 输入流解析 pack.yaml 文件
     *
     * <p>支持的 YAML 特性：
     * <ul>
     *   <li>键值对映射</li>
     *   <li>嵌套对象（缩进表示层级）</li>
     *   <li>数组（- 前缀）</li>
     *   <li>字符串/数字/布尔值自动类型推断</li>
     * </ul>
     *
     * @param inputStream pack.yaml 的输入流（将自动关闭）
     * @return 解析后的 RGBPackManifest 实例
     * @throws IOException              如果读取失败
     * @throws ValidationException      如果 YAML 格式无效或缺少必填字段
     *
     * @implNote 当前实现使用轻量级手写 YAML 解析器。
     *           如需更复杂的 YAML 支持，可考虑引入 SnakeYAML 库。
     */
    public static RGBPackManifest fromYaml(InputStream inputStream) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream 不能为 null");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            // 使用内部解析器处理 YAML
            YamlParser parser = new YamlParser(reader);
            Map<String, Object> yamlRoot = parser.parse();

            return fromMap(yamlRoot);
        }
    }

    /**
     * 从已解析的 Map 构建 RGBPackManifest
     * （内部使用，供测试和扩展解析器调用）
     */
    static RGBPackManifest fromMap(Map<String, Object> yamlRoot) {
        // 提取顶层字段
        String name = getString(yamlRoot, "name");
        String version = getString(yamlRoot, "version");
        String author = getString(yamlRoot, "author");
        String description = getStringOrDefault(yamlRoot, "description", "");
        String license = getStringOrDefault(yamlRoot, "license", "Unknown");
        String mcVersion = getString(yamlRoot, "minecraft_version");
        String rvVersion = getString(yamlRoot, "renderium_version");
        String permission = getStringOrDefault(yamlRoot, "permission", "SANDBOX");

        // 解析 effects 数组
        List<Map<String, Object>> effectsRaw = getList(yamlRoot, "effects");
        List<EffectEntry> effects = new ArrayList<>();

        for (Map<String, Object> effectRaw : effectsRaw) {
            effects.add(EffectEntry.fromMap(effectRaw));
        }

        // 解析全局 settings
        @SuppressWarnings("unchecked")
        Map<String, Object> settings =
            yamlRoot.containsKey("settings") ?
            (Map<String, Object>) yamlRoot.get("settings") :
            new HashMap<>();

        return new RGBPackManifest(
            name, version, author,
            description, license,
            mcVersion, rvVersion,
            permission,
            effects,
            settings
        );
    }

    // ==================== 验证方法 ====================

    /**
     * 验证清单文件的完整性
     *
     * <p>检查项目：
     * <ol>
     *   <li>所有必填字段是否存在</li>
     *   <li>效果 ID 是否唯一</li>
     *   <li>依赖关系是否有效（无循环依赖）</li>
     *   <li>着色器路径是否非空</li>
     * </ol>
     *
     * @throws ValidationException 如果验证失败
     */
    public void validate() throws ValidationException {
        List<String> errors = new ArrayList<>();

        // 1. 检查必填字段
        if (name == null || name.isBlank()) {
            errors.add("name 字段为空");
        }
        if (version == null || !isValidSemVer(version)) {
            errors.add("version 不是有效的语义化版本: " + version);
        }
        if (author == null || author.isBlank()) {
            errors.add("author 字段为空");
        }
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            errors.add("minecraft_version 字段为空");
        }
        if (renderiumVersion == null || renderiumVersion.isBlank()) {
            errors.add("renderium_version 字段为空");
        }

        // 2. 检查效果 ID 唯一性
        Set<String> effectIds = new HashSet<>();
        for (EffectEntry effect : effects) {
            if (!effectIds.add(effect.getId())) {
                errors.add("重复的效果 ID: " + effect.getId());
            }
        }

        // 3. 检查依赖关系有效性
        for (EffectEntry effect : effects) {
            for (String depId : effect.getDependsOn()) {
                boolean found = false;
                for (EffectEntry e : effects) {
                    if (e.getId().equals(depId)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    errors.add(String.format(
                        "效果 '%s' 依赖的不存在效果: %s",
                        effect.getId(), depId
                    ));
                }
            }
        }

        // 4. 检查循环依赖
        try {
            getExecutionOrder();  // 内部会检测环
        } catch (CircularDependencyException e) {
            errors.add(e.getMessage());
        }

        // 5. 检查着色器路径
        for (EffectEntry effect : effects) {
            for (ShaderFile shader : effect.getShaders()) {
                if (shader.getPath() == null || shader.getPath().isBlank()) {
                    errors.add(String.format(
                        "效果 '%s' 包含空的着色器路径",
                        effect.getId()
                    ));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(
                "RGBPackManifest 验证失败:\n  - " + String.join("\n  - ", errors)
            );
        }

        LOGGER.fine("RGBPackManifest 验证通过: " + name + " v" + version);
    }

    /**
     * 拓扑排序：返回按依赖关系排序后的效果列表
     *
     * <p>使用 Kahn 算法进行拓扑排序，确保：
     * <ul>
     *   <li>被依赖的效果先执行</li>
     *   <li>相同优先级时按 order 字段升序排列</li>
     *   <li>相同 order 时按声明顺序排列</li>
     * </ul>
     *
     * @return 拓扑排序后的效果列表（不可修改）
     * @throws CircularDependencyException 如果检测到循环依赖
     */
    public List<EffectEntry> getExecutionOrder() {
        // 构建邻接表和入度表
        Map<String, EffectEntry> effectMap = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjList = new HashMap<>();

        // 初始化
        for (EffectEntry effect : effects) {
            effectMap.put(effect.getId(), effect);
            inDegree.put(effect.getId(), 0);
            adjList.put(effect.getId(), new ArrayList<>());
        }

        // 填充邻接表和入度
        for (EffectEntry effect : effects) {
            for (String depId : effect.getDependsOn()) {
                if (effectMap.containsKey(depId)) {
                    adjList.get(depId).add(effect.getId());
                    inDegree.merge(effect.getId(), 1, Integer::sum);
                }
            }
        }

        // Kahn 算法：从入度为 0 的节点开始
        Queue<String> queue = new PriorityQueue<>((a, b) -> {
            // 先比较 order 字段
            int orderCompare = Integer.compare(
                effectMap.get(a).getOrder(),
                effectMap.get(b).getOrder()
            );
            if (orderCompare != 0) return orderCompare;

            // 相同 order 时按声明顺序（LinkedHashMap 保证插入顺序）
            int indexA = -1, indexB = -1;
            int idx = 0;
            for (EffectEntry e : effects) {
                if (e.getId().equals(a)) indexA = idx;
                if (e.getId().equals(b)) indexB = idx;
                idx++;
            }
            return Integer.compare(indexA, indexB);
        });

        // 找到所有入度为 0 的节点
        for (String id : inDegree.keySet()) {
            if (inDegree.get(id) == 0) {
                queue.offer(id);
            }
        }

        // 执行拓扑排序
        List<EffectEntry> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(effectMap.get(current));

            // 减少邻居节点的入度
            for (String neighbor : adjList.get(current)) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.offer(neighbor);
                }
            }
        }

        // 检测环：如果结果数量小于总效果数，说明存在环
        if (result.size() != effects.size()) {
            // 找出参与环的效果
            Set<String> sortedIds = new HashSet<>();
            for (EffectEntry e : result) {
                sortedIds.add(e.getId());
            }

            List<String> cycleNodes = new ArrayList<>();
            for (EffectEntry e : effects) {
                if (!sortedIds.contains(e.getId())) {
                    cycleNodes.add(e.getId());
                }
            }

            throw new CircularDependencyException(
                "检测到循环依赖，涉及效果: " + cycleNodes
            );
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * 版本兼容性检查
     *
     * <p>检查当前光影包是否与指定的 Minecraft 和 Renderium 版本兼容。
     *
     * <h3>版本格式支持：</h3>
     * <ul>
     *   <li>"1.21+": 1.21 及以上版本</li>
     *   <li>"1.20.4": 精确匹配</li>
     *   <li>"1.20.4-1.21.1": 范围匹配（包含边界）</li>
     * </ul>
     *
     * @param mcVersion  当前运行的 Minecraft 版本（如 "1.21.1"）
     * @param rvVersion  当前运行的 Renderium 版本（如 "1.0.0"）
     * @return true 如果兼容，false 如果不兼容
     */
    public boolean isCompatibleWith(String mcVersion, String rvVersion) {
        return isMcVersionCompatible(mcVersion) && isRvVersionCompatible(rvVersion);
    }

    // ==================== Getter 方法 ====================

    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getAuthor() { return author; }
    public String getDescription() { return description; }
    public String getLicense() { return license; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public String getRenderiumVersion() { return renderiumVersion; }
    public String getPermission() { return permission; }
    public List<EffectEntry> getEffects() { return effects; }
    public Map<String, Object> getSettings() { return settings; }

    /**
     * 获取效果总数
     */
    public int getEffectCount() { return effects.size(); }

    /**
     * 根据 ID 查找效果
     *
     * @param id 效果标识符
     * @return 对应的 EffectEntry，如果不存在则返回 null
     */
    public EffectEntry getEffectById(String id) {
        for (EffectEntry effect : effects) {
            if (effect.getId().equals(id)) {
                return effect;
            }
        }
        return null;
    }

    /**
     * 检查是否包含 Compute Shader 效果
     */
    public boolean hasComputeShaders() {
        return effects.stream()
            .anyMatch(e -> "compute".equalsIgnoreCase(e.getType()));
    }

    @Override
    public String toString() {
        return String.format(
            "RGBPackManifest{name='%s', version='%s', author='%s', effects=%d}",
            name, version, author, effects.size()
        );
    }

    // ==================== 私有辅助方法 ====================

    private boolean isValidSemVer(String ver) {
        return ver.matches("\\d+\\.\\d+\\.\\d+(-[\\w.]+)?");
    }

    private boolean isMcVersionCompatible(String currentMcVersion) {
        String required = this.minecraftVersion;

        // 范围格式："min-max"
        if (required.contains("-")) {
            String[] parts = required.split("-");
            String minVer = parts[0].trim();
            String maxVer = parts[1].trim();
            return compareVersions(currentMcVersion, minVer) >= 0 &&
                   compareVersions(currentMcVersion, maxVer) <= 0;
        }

        // 最小版本格式："ver+"
        if (required.endsWith("+")) {
            String minVer = required.substring(0, required.length() - 1);
            return compareVersions(currentMcVersion, minVer) >= 0;
        }

        // 精确匹配
        return currentMcVersion.equals(required);
    }

    private boolean isRvVersionCompatible(String currentRvVersion) {
        String required = this.renderiumVersion;

        // 支持最小版本格式："ver+"
        if (required.endsWith("+")) {
            String minVer = required.substring(0, required.length() - 1);
            return compareVersions(currentRvVersion, minVer) >= 0;
        }

        // 精确匹配或前缀匹配
        return currentRvVersion.startsWith(required) ||
               currentRvVersion.equals(required);
    }

    /**
     * 比较两个语义化版本号
     *
     * @return 负数如果 v1 < v2，0 如果相等，正数如果 v1 > v2
     */
    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        return 0;
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== 静态工具方法（用于解析）====================

    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("缺少必填字段: " + key);
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private static String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 从 Map 安全获取 int 值，缺失时返回默认值
     */
    @SuppressWarnings("unchecked")
    private static int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 从 Map 安全获取 boolean 值，缺失时返回默认值
     */
    @SuppressWarnings("unchecked")
    private static boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("字段 '" + key + "' 应为数组类型");
        }
        return (List<Map<String, Object>>) value;
    }

    // ==================== 内部类：EffectEntry ====================

    /**
     * 效果条目
     *
     * <p>代表 pack.yaml 中 effects 数组的一个元素，
     * 包含单个后处理效果的完整定义。
     */
    public static final class EffectEntry {

        // ---- 字段 ----

        /** 唯一标识符（用于依赖引用和日志）*/
        private final String id;

        /** 显示名称（UI 展示用）*/
        private final String name;

        /** 效果类型：postprocess / screen / compute */
        private final String type;

        /** 执行顺序权重（越小越先执行）*/
        private final int order;

        /** 是否启用（运行时可切换）*/
        private final boolean enabled;

        /** 依赖的其他效果 ID 列表 */
        private final List<String> dependsOn;

        /** 着色器文件列表 */
        private final List<ShaderFile> shaders;

        /** 效果级别的参数配置 */
        private final Map<String, Object> settings;

        // ---- 构造函数 ----

        public EffectEntry(String id, String name, String type, int order,
                           boolean enabled,
                           List<String> dependsOn,
                           List<ShaderFile> shaders,
                           Map<String, Object> settings) {
            this.id = Objects.requireNonNull(id);
            this.name = Objects.requireNonNull(name);
            this.type = Objects.requireNonNull(type);
            this.order = order;
            this.enabled = enabled;
            this.dependsOn = Collections.unmodifiableList(new ArrayList<>(dependsOn));
            this.shaders = Collections.unmodifiableList(new ArrayList<>(shaders));
            this.settings = Collections.unmodifiableMap(new HashMap<>(settings));
        }

        /**
         * 从 Map 创建 EffectEntry（供解析器使用）
         */
        static EffectEntry fromMap(Map<String, Object> map) {
            String id = getString(map, "id");
            String name = getString(map, "name");
            String type = getString(map, "type");
            int order = getIntOrDefault(map, "order", 0);
            boolean enabled = getBooleanOrDefault(map, "enabled", true);

            @SuppressWarnings("unchecked")
            List<String> dependsOn = map.containsKey("depends_on") ?
                (List<String>) map.get("depends_on") :
                new ArrayList<>();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> shadersRaw = getList(map, "shaders");
            List<ShaderFile> shaders = new ArrayList<>();
            for (Map<String, Object> shaderRaw : shadersRaw) {
                shaders.add(ShaderFile.fromMap(shaderRaw));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> settings = map.containsKey("settings") ?
                (Map<String, Object>) map.get("settings") :
                new HashMap<>();

            return new EffectEntry(id, name, type, order, enabled,
                                   dependsOn, shaders, settings);
        }

        // ---- Getter ----

        public String getId() { return id; }
        public String getName() { return name; }
        public String getType() { return type; }
        public int getOrder() { return order; }
        public boolean isEnabled() { return enabled; }
        public List<String> getDependsOn() { return dependsOn; }
        public List<ShaderFile> getShaders() { return shaders; }
        public Map<String, Object> getSettings() { return settings; }

        /**
         * 获取指定参数值
         *
         * @param key 参数名
             * @param defaultValue 默认值
         * @return 参数值
         */
        public Object getSetting(String key, Object defaultValue) {
            return settings.getOrDefault(key, defaultValue);
        }

        @Override
        public String toString() {
            return String.format(
                "EffectEntry{id='%s', name='%s', type='%s', order=%d}",
                id, name, type, order
            );
        }
    }

    // ==================== 内部类：ShaderFile ====================

    /**
     * 着色器文件定义
     *
     * <p>描述单个着色器文件的路径、阶段和入口点信息。
     */
    public static final class ShaderFile {

        // ---- 字段 ----

        /** 相对于 .rgb 包根目录的路径（如 "shaders/bloom.frag"）*/
        private final String path;

        /** 着色器阶段（vertex/fragment/compute/geometry）*/
        private final String stage;

        /** 入口函数名（默认 "main"）*/
        private final String entryPoint;

        // ---- 构造函数 ----

        public ShaderFile(String path, String stage, String entryPoint) {
            this.path = Objects.requireNonNull(path);
            this.stage = Objects.requireNonNull(stage);
            this.entryPoint = entryPoint != null ? entryPoint : "main";
        }

        /**
         * 从 Map 创建 ShaderFile（供解析器使用）
         */
        static ShaderFile fromMap(Map<String, Object> map) {
            String path = getString(map, "path");
            String stage = getString(map, "stage");
            String entryPoint = getStringOrDefault(map, "entry_point", "main");
            return new ShaderFile(path, stage, entryPoint);
        }

        // ---- Getter ----

        public String getPath() { return path; }
        public String getStage() { return stage; }
        public String getEntryPoint() { return entryPoint; }

        /**
         * 获取文件扩展名（不含点号）
         *
         * @return 扩展名字符串（如 "frag"、"comp"、"vert"）
         */
        public String getExtension() {
            int dotIndex = path.lastIndexOf('.');
            return dotIndex > 0 ? path.substring(dotIndex + 1) : "";
        }

        @Override
        public String toString() {
            return String.format("ShaderFile{path='%s', stage='%s'}", path, stage);
        }
    }

    // ==================== 异常类 ====================

    /**
     * RGB Pack 相关异常基类
     */
    public static class RGBPackException extends RuntimeException {
        public RGBPackException(String message) { super(message); }
        public RGBPackException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * 清单验证失败异常
     */
    public static class ValidationException extends RGBPackException {
        public ValidationException(String message) { super(message); }
    }

    /**
     * 循环依赖检测异常
     */
    public static class CircularDependencyException extends RGBPackException {
        public CircularDependencyException(String message) { super(message); }
    }

    /**
     * 版本不兼容异常
     */
    public static class VersionIncompatibilityException extends RGBPackException {
        public VersionIncompatibilityException(String message) { super(message); }
    }

    // ==================== 内部 YAML 解析器 ====================

    /**
     * 轻量级 YAML 子集解析器
     *
     * <p>支持的特性：
     * <ul>
     *   <li>缩进表示层级结构</li>
     *   <li>- 开头的数组项</li>
     *   <li>字符串/数字/布尔值/浮点数自动识别</li>
     *   <li># 开头的注释行（忽略）</li>
     * </ul>
     *
     * <p><b>限制：</b>不支持多行字符串、锚点引用、复杂类型标签等高级特性。
     */
    private static final class YamlParser {

        private final BufferedReader reader;
        private final List<YamlLine> lines = new ArrayList<>();
        private int currentLine = 0;

        YamlParser(BufferedReader reader) throws IOException {
            this.reader = reader;
            parseLines();
        }

        /**
         * 解析并返回根 Map
         */
        Map<String, Object> parse() {
            return parseBlock(0, lines.size());
        }

        /**
         * 解析指定行范围内的内容块
         */
        private Map<String, Object> parseBlock(int startIndent, int endLine) {
            Map<String, Object> result = new LinkedHashMap<>();

            while (currentLine < endLine && currentLine < lines.size()) {
                YamlLine line = lines.get(currentLine);

                // 跳过空白行和注释
                if (line.isEmpty() || line.isComment()) {
                    currentLine++;
                    continue;
                }

                // 检查缩进级别是否匹配
                if (line.indent < startIndent) {
                    break;  // 返回上层
                }

                if (line.isArrayItem()) {
                    // 数组项：收集同级数组元素
                    List<Object> arrayItems = new ArrayList<>();
                    while (currentLine < endLine && currentLine < lines.size()) {
                        YamlLine arrLine = lines.get(currentLine);
                        if (!arrLine.isArrayItem() || arrLine.indent != startIndent) {
                            break;
                        }

                        String itemContent = arrLine.content.trim();
                        if (itemContent.contains(":")) {
                            // 数组中的对象
                            currentLine++;
                            Map<String, Object> obj = parseBlock(arrLine.indent + 2, endLine);
                            // 合并当前行的键值对
                            String[] kv = itemContent.split(":", 2);
                            if (kv.length == 2) {
                                obj.put(kv[0].trim(), parseValue(kv[1].trim()));
                            }
                            arrayItems.add(obj);
                        } else {
                            // 简单值
                            arrayItems.add(parseValue(itemContent));
                            currentLine++;
                        }
                    }
                    return result;  // 数组由调用者处理
                }

                if (line.content.contains(":")) {
                    String[] kv = line.content.split(":", 2);
                    String key = kv[0].trim();
                    String valueStr = kv.length > 1 ? kv[1].trim() : "";

                    currentLine++;

                    if (valueStr.isEmpty()) {
                        // 检查下一行是否有子内容
                        if (currentLine < lines.size()) {
                            YamlLine nextLine = lines.get(currentLine);
                            if (nextLine.indent > line.indent) {
                                // 有子块：可能是数组或嵌套对象
                                if (nextLine.isArrayItem()) {
                                    // 解析数组
                                    List<Object> list = new ArrayList<>();
                                    while (currentLine < endLine && currentLine < lines.size()) {
                                        YamlLine itemLine = lines.get(currentLine);
                                        if (!itemLine.isArrayItem() || itemLine.indent <= line.indent) {
                                            break;
                                        }

                                        String itemContent = itemLine.content.trim();
                                        currentLine++;

                                        if (itemContent.contains(":")) {
                                            // 数组中的对象
                                            String[] ikv = itemContent.split(":", 2);
                                            String ikey = ikv[0].trim();
                                            String ivalue = ikv.length > 1 ? ikv[1].trim() : "";

                                            Map<String, Object> obj = new LinkedHashMap<>();
                                            obj.put(ikey, ivalue.isEmpty() ? parseValue(ivalue) : parseObject(itemLine.indent));

                                            // 继续解析对象的其余属性
                                            while (currentLine < endLine && currentLine < lines.size()) {
                                                YamlLine subLine = lines.get(currentLine);
                                                if (subLine.indent <= itemLine.indent) {
                                                    break;
                                                }
                                                if (subLine.content.contains(":")) {
                                                    String[] skv = subLine.content.split(":", 2);
                                                    String skey = skv[0].trim();
                                                    String svalue = skv.length > 1 ? skv[1].trim() : "";
                                                    currentLine++;
                                                    if (svalue.isEmpty()) {
                                                        obj.put(skey, parseObject(subLine.indent));
                                                    } else {
                                                        obj.put(skey, parseValue(svalue));
                                                    }
                                                } else {
                                                    currentLine++;
                                                }
                                            }

                                            list.add(obj);
                                        } else {
                                            list.add(parseValue(itemContent));
                                        }
                                    }
                                    result.put(key, list);
                                } else {
                                    // 嵌套对象
                                    result.put(key, parseObject(line.indent));
                                }
                            } else {
                                // 空值
                                result.put(key, "");
                            }
                        } else {
                            result.put(key, "");
                        }
                    } else {
                        // 简单键值对
                        result.put(key, parseValue(valueStr));
                    }
                } else {
                    currentLine++;  // 无法解析的行，跳过
                }
            }

            return result;
        }

        /**
         * 解析嵌套对象块
         */
        private Map<String, Object> parseObject(int parentIndent) {
            Map<String, Object> obj = new LinkedHashMap<>();

            while (currentLine < lines.size()) {
                YamlLine line = lines.get(currentLine);

                if (line.isEmpty() || line.isComment()) {
                    currentLine++;
                    continue;
                }

                if (line.indent <= parentIndent) {
                    break;  // 返回上层
                }

                if (line.content.contains(":")) {
                    String[] kv = line.content.split(":", 2);
                    String key = kv[0].trim();
                    String valueStr = kv.length > 1 ? kv[1].trim() : "";
                    currentLine++;

                    if (valueStr.isEmpty()) {
                        // 可能是嵌套对象或数组
                        if (currentLine < lines.size()) {
                            YamlLine nextLine = lines.get(currentLine);
                            if (nextLine.indent > line.indent) {
                                if (nextLine.isArrayItem()) {
                                    // 解析数组
                                    List<Object> list = new ArrayList<>();
                                    while (currentLine < lines.size()) {
                                        YamlLine itemLine = lines.get(currentLine);
                                        if (!itemLine.isArrayItem() || itemLine.indent <= line.indent) {
                                            break;
                                        }
                                        String itemContent = itemLine.content.trim();
                                        currentLine++;

                                        if (itemContent.contains(":")) {
                                            Map<String, Object> itemObj = new LinkedHashMap<>();
                                            // 解析数组项的对象属性
                                            while (currentLine < lines.size()) {
                                                YamlLine subLine = lines.get(currentLine);
                                                if (subLine.indent <= itemLine.indent || !subLine.content.contains(":")) {
                                                    break;
                                                }
                                                String[] skv = subLine.content.split(":", 2);
                                                String skey = skv[0].trim();
                                                String svalue = skv.length > 1 ? skv[1].trim() : "";
                                                currentLine++;
                                                if (svalue.isEmpty()) {
                                                    itemObj.put(skey, parseObject(itemLine.indent));
                                                } else {
                                                    itemObj.put(skey, parseValue(svalue));
                                                }
                                            }
                                            list.add(itemObj);
                                        } else {
                                            list.add(parseValue(itemContent));
                                        }
                                    }
                                    obj.put(key, list);
                                } else {
                                    obj.put(key, parseObject(line.indent));
                                }
                            } else {
                                obj.put(key, "");
                            }
                        } else {
                            obj.put(key, "");
                        }
                    } else {
                        obj.put(key, parseValue(valueStr));
                    }
                } else {
                    currentLine++;
                }
            }

            return obj;
        }

        /**
         * 自动推断值的类型
         */
        private Object parseValue(String value) {
            if (value.isEmpty()) return "";

            // 带引号的字符串
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length() - 1);
            }

            // 布尔值
            if ("true".equalsIgnoreCase(value)) return true;
            if ("false".equalsIgnoreCase(value)) return false;

            // 浮点数
            if (value.matches("^-?\\d+\\.\\d+$")) {
                return Double.parseDouble(value);
            }

            // 整数
            if (value.matches("^-?\\d+$")) {
                return Long.parseLong(value);
            }

            // 默认作为字符串
            return value;
        }

        /**
         * 预处理所有行
         */
        private void parseLines() throws IOException {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                lines.add(new YamlLine(rawLine));
            }
        }

        // ---- 额外的静态工具方法（供 EffectEntry 使用）----

        private static int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
            Object value = map.get(key);
            if (value == null) return defaultValue;
            if (value instanceof Number) return ((Number) value).intValue();
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        private static boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
            Object value = map.get(key);
            if (value == null) return defaultValue;
            if (value instanceof Boolean) return (Boolean) value;
            return Boolean.parseBoolean(value.toString());
        }
    }

    /**
     * YAML 行表示
     */
    private static final class YamlLine {
        final int indent;
        final String content;
        final boolean isArray;
        final boolean isEmpty;
        final boolean isComment;

        YamlLine(String raw) {
            this.indent = countLeadingSpaces(raw);
            String trimmed = raw.trim();

            this.isEmpty = trimmed.isEmpty();
            this.isComment = trimmed.startsWith("#");
            this.isArray = !isEmpty && !isComment && trimmed.startsWith("- ");

            // 移除数组标记
            if (isArray) {
                this.content = trimmed.substring(2);  // 移除 "- "
            } else {
                this.content = trimmed;
            }
        }

        boolean isArrayItem() { return isArray; }
        boolean isComment() { return isComment; }
        boolean isEmpty() { return isEmpty; }

        private static int countLeadingSpaces(String str) {
            int count = 0;
            for (char c : str.toCharArray()) {
                if (c == ' ' || c == '\t') {
                    count++;
                } else {
                    break;
                }
            }
            return count;
        }
    }
}
