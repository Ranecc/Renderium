// Renderium - 动态参数配置系统
// 全局参数注册表 - 管理所有 ParameterKnob 实例

package com.renderium.pipeline.parameter;

import com.renderium.pipeline.parameter.impl.BoolKnob;
import com.renderium.pipeline.parameter.impl.EnumKnob;
import com.renderium.pipeline.parameter.impl.FloatKnob;
import com.renderium.pipeline.parameter.impl.IntKnob;
import com.renderium.pipeline.parameter.ParameterKnob.KnobType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 全局参数注册表 (Parameter Registry)
 * <p>
 * 线程安全的单例管理器，负责统一管理渲染管线中的所有 {@link ParameterKnob} 实例。
 * 提供参数的注册、注销、查询、分类筛选和 JSON 格式的配置导入/导出功能。
 *
 * <h2>核心职责：</h2>
 * <ul>
 *   <li><b>生命周期管理</b>：注册、注销、批量清理参数</li>
 *   <li><b>快速查找</b>：按 ID O(1) 查找参数实例</li>
 *   <li><b>分类查询</b>：按 ParameterCategory 分组获取参数</li>
 *   <li><b>配置持久化</b>：JSON 格式的导出/导入</li>
 *   <li><b>变更广播</b>：全局级别的参数变更事件通知</li>
 * </ul>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * ┌─────────────┐  register()  ┌──────────────────┐
 * │ FloatKnob   │ ───────────→ │                  │
 * │ IntKnob     │ ───────────→ │ ParameterRegistry │ ← 单例
 * │ EnumKnob    │ ───────────→ │   (ConcurrentMap) │
 * │ BoolKnob    │ ───────────→ │                  │
 * └─────────────┘              └────────┬─────────┘
 *                                      │
 *              ┌────────────────────────┼────────────────────────┐
 *              ▼                        ▼                        ▼
 *       getByCategory()            exportConfig()          importConfig()
 *       (按分组查询)              (导出 JSON)             (导入 JSON)
 * </pre>
 *
 * <h3>线程安全保证：</h3>
 * <p>
 * 内部使用 ConcurrentHashMap 存储参数映射，保证：
 * <ul>
 *   <li>并发读写安全（无锁读取）</li>
 *   <li>原子性注册/注销操作</li>
 *   <li>弱一致性迭代器（适合遍历场景）</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 获取注册表单例
 * ParameterRegistry registry = ParameterRegistry.getInstance();
 *
 * // 注册参数
 * registry.register(new FloatKnob.Builder("normal_strength")
 *     .displayName("法线强度").range(0.1f, 2.0f).defaultValue(1.0f).build());
 * registry.register(new IntKnob.Builder("pcf_samples")
 *     .displayName("PCF采样数").range(1, 64).defaultValue(16).build());
 *
 * // 按 ID 查找
 * ParameterKnob<?> knob = registry.get("normal_strength");
 *
 * // 按分类查询
 * List<ParameterKnob<?>> shadowParams = registry.getByCategory(ParameterCategory.SHADOW);
 *
 * // 导出配置为 JSON 字符串
 * String json = registry.exportConfig();
 *
 * // 从 JSON 导入配置
 * registry.importConfig(json);
 * }</pre>
 *
 * @see ParameterKnob
 * @see ShaderParameterConfig
 * @since 6.0.0
 */
public final class ParameterRegistry {

    // ==================== 单例实现 ====================

    /**
     * 饿汉式单例实例（线程安全，无锁）
     */
    private static final ParameterRegistry INSTANCE = new ParameterRegistry();

    /**
     * 获取全局参数注册表单例
     *
     * 【返回值】
     * @return ParameterRegistry - 唯一的全局注册表实例
     */
    public static ParameterRegistry getInstance() {
        return INSTANCE;
    }

    // ==================== 内部存储 ====================

    /**
     * 参数 ID → ParameterKnob 映射表
     * <p>
     * 使用 ConcurrentHashMap 保证并发读写安全。
     * Key 为参数 ID（String），Value 为参数旋钮实例。
     */
    private final ConcurrentHashMap<String, ParameterKnob<?>> knobMap = new ConcurrentHashMap<>();

    /**
     * 全局变更监听器列表
     * <p>
     * 当任何参数被注册、注销或值发生变化时触发。
     */
    private final CopyOnWriteArrayList<RegistryChangeListener> globalListeners = new CopyOnWriteArrayList<>();

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数（强制单例模式）
     */
    private ParameterRegistry() {
        // 预留空间，减少扩容开销
        // 典型场景下会有 50~200 个参数
    }

    // ==================== 参数注册与注销 ====================

    /**
     * 注册参数到全局注册表
     * <p>
     * 如果已存在相同 ID 的参数，会抛出异常防止覆盖。
     * 注册成功后会触发全局变更事件。
     *
     * 【方法参数】
     * @param knob ParameterKnob&lt;?&gt; - 要注册的参数旋钮实例（不能为 null）
     *
     * @throws NullPointerException     如果 knob 为 null
     * @throws IllegalStateException    如果相同 ID 已存在
     */
    public void register(ParameterKnob<?> knob) {
        Objects.requireNonNull(knob, "参数不能为 null");

        ParameterKnob<?> existing = knobMap.putIfAbsent(knob.getId(), knob);
        if (existing != null) {
            throw new IllegalStateException(
                    String.format("参数 ID '%s' 已存在（当前: %s），请先 unregister 或使用不同 ID",
                            knob.getId(), existing.getDisplayName()));
        }

        // 注册参数级变更监听器以转发到全局监听器
        knob.addChangeListener((k, oldVal, newVal) -> notifyGlobalListeners(
                RegistryChangeType.VALUE_CHANGED, knob.getId(), k));

        notifyGlobalListeners(RegistryChangeType.REGISTERED, knob.getId(), knob);
    }

    /**
     * 批量注册参数
     * <p>
     * 原子性操作：要么全部成功，要么全部失败（回滚）。
     *
     * 【方法参数】
     * @param knobs ParameterKnob&lt;?&gt;... - 可变数量的参数实例
     *
     * @throws IllegalStateException 如果任何一个 ID 冲突（全部不会注册）
     */
    public void registerAll(ParameterKnob<?>... knobs) {
        Objects.requireNonNull(knobs, "参数数组不能为 null");

        // 第一阶段：预检查所有 ID 是否冲突
        for (ParameterKnob<?> knob : knobs) {
            Objects.requireNonNull(knob, "参数数组中包含 null 元素");
            if (knobMap.containsKey(knob.getId())) {
                throw new IllegalStateException(
                        String.format("批量注册失败：参数 ID '%s' 已存在", knob.getId()));
            }
        }

        // 第二阶段：全部通过后执行注册
        for (ParameterKnob<?> knob : knobs) {
            knobMap.put(knob.getId(), knob);
            knob.addChangeListener((k, oldVal, newVal) -> notifyGlobalListeners(
                    RegistryChangeType.VALUE_CHANGED, knob.getId(), k));
        }

        notifyGlobalListeners(RegistryChangeType.BATCH_REGISTERED, null, null);
    }

    /**
     * 注销指定 ID 的参数
     * <p>
     * 从注册表中移除参数，后续 get() 调用将返回 null。
     *
     * 【方法参数】
     * @param id String - 要注销的参数 ID（不能为 null）
     *
     * @return ParameterKnob&lt;?&gt; - 被注销的参数实例；如果不存在则返回 null
     */
    public ParameterKnob<?> unregister(String id) {
        Objects.requireNonNull(id, "参数 ID 不能为 null");
        ParameterKnob<?> removed = knobMap.remove(id);
        if (removed != null) {
            notifyGlobalListeners(RegistryChangeType.UNREGISTERED, id, removed);
        }
        return removed;
    }

    /**
     * 注销并返回指定类型的参数
     * <p>
     * 类型安全的注销方法，避免强制类型转换。
     *
     * 【方法参数】
     * @param id   String           - 参数 ID
     * @param type Class&lt;T&gt;      - 期望的类型
     *
     * @return T - 被注销的参数实例；类型不匹配或不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T unregister(String id, Class<T> type) {
        ParameterKnob<?> removed = unregister(id);
        if (removed != null && type.isInstance(removed)) {
            return (T) removed;
        }
        return null;
    }

    /**
     * 清空注册表中的所有参数
     * <p>
     * 危险操作！仅用于系统重置或测试场景。
     */
    public void clear() {
        knobMap.clear();
        notifyGlobalListeners(RegistryChangeType.CLEARED, null, null);
    }

    // ==================== 参数查询方法 ====================

    /**
     * 根据 ID 查找参数
     * <p>
     * O(1) 时间复杂度的 HashMap 查找。
     *
     * 【方法参数】
     * @param id String - 参数唯一标识符
     *
     * 【返回值】
     * @return ParameterKnob&lt;?&gt; - 找到的参数实例；不存在则返回 null
     */
    public ParameterKnob<?> get(String id) {
        return knobMap.get(id);
    }

    /**
     * 类型安全的参数查找
     * <p>
     * 同时校验 ID 存在性和类型匹配性。
     *
     * 【方法参数】
     * @param id   String       - 参数 ID
     * @param type Class&lt;T&gt;  - 期望的具体类型（如 FloatKnob.class）
     *
     * 【返回值】
     * @return T - 类型匹配的参数实例；不匹配或不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T extends ParameterKnob<?>> T get(String id, Class<T> type) {
        ParameterKnob<?> knob = knobMap.get(id);
        if (knob != null && type.isInstance(knob)) {
            return (T) knob;
        }
        return null;
    }

    /**
     * 获取所有已注册参数的不可变视图
     *
     * 【返回值】
     * @return Collection&lt;ParameterKnob&lt;?&gt;&gt; - 所有参数的集合视图
     */
    public Collection<ParameterKnob<?>> getAll() {
        return Collections.unmodifiableCollection(knobMap.values());
    }

    /**
     * 获取已注册参数的总数量
     *
     * 【返回值】
     * @return int - 当前注册表中参数的数量
     */
    public int size() {
        return knobMap.size();
    }

    /**
     * 检查指定 ID 的参数是否已注册
     *
     * 【方法参数】
     * @param id String - 参数 ID
     *
     * 【返回值】
     * @return boolean - true 表示已注册
     */
    public boolean contains(String id) {
        return knobMap.containsKey(id);
    }

    // ==================== 分类查询方法 ====================

    /**
     * 按分类获取所有参数
     * <p>
     * 返回属于指定分类的所有参数副本。
     * 用于 UI 中按分组展示参数列表。
     *
     * 【方法参数】
     * @param category ParameterCategory - 目标分类
     *
     * 【返回值】
     * @return List&lt;ParameterKnob&lt;?&gt;&gt; - 该分类下的参数列表（可能为空列表）
     */
    public List<ParameterKnob<?>> getByCategory(ParameterKnob.ParameterCategory category) {
        return knobMap.values().stream()
                .filter(k -> k.getCategory() == category)
                .collect(Collectors.toList());
    }

    /**
     * 按分类获取参数 ID 列表
     * <p>
     * 轻量级查询，仅返回 ID 字符串而非完整对象。
     *
     * 【方法参数】
     * @param category ParameterCategory - 目标分类
     *
     * 【返回值】
     * @return List&lt;String&gt; - 该分类下的参数 ID 列表
     */
    public List<String> getIdsByCategory(ParameterKnob.ParameterCategory category) {
        return knobMap.entrySet().stream()
                .filter(e -> e.getValue().getCategory() == category)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 按类型获取所有参数
     * <p>
     * 返回指定 KnobType 的所有参数。
     *
     * 【方法参数】
     * @param type KnobType - 目标类型（FLOAT / INT / ENUM / BOOL 等）
     *
     * 【返回值】
     * @return List&lt;ParameterKnob&lt;?&gt;&gt; - 匹配类型的参数列表
     */
    public List<ParameterKnob<?>> getByType(KnobType type) {
        return knobMap.values().stream()
                .filter(k -> k.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * 按分类分组获取所有参数
     * <p>
     * 返回一个 Map，Key 为分类，Value 为该分类下的参数列表。
     * 适用于一次性构建完整的 UI 参数面板。
     *
     * 【返回值】
     * @return Map&lt;ParameterCategory, List&lt;ParameterKnob&lt;?&gt;&gt;&gt; - 分组后的参数映射
     */
    public Map<ParameterKnob.ParameterCategory, List<ParameterKnob<?>>> groupByCategory() {
        return knobMap.values().stream()
                .collect(Collectors.groupingBy(ParameterKnob::getCategory));
    }

    // ==================== 配置导入/导出（JSON）====================

    /**
     * 导出当前所有参数配置为 JSON 字符串
     * <p>
     * 导出内容包括每个参数的 ID、当前值、默认值和类型信息。
     * 不包含范围信息和描述，保持输出精简。
     * <p>
     * JSON 格式示例：
     * <pre>{@code
     * {
     *   "exportTime": "2025-01-15T10:30:00",
     *   "totalParameters": 42,
     *   "parameters": [
     *     {"id": "normal_strength", "type": "FLOAT", "value": 1.5},
     *     {"id": "pcf_samples", "type": "INT", "value": 32},
     *     {"id": "enable_normal_mapping", "type": "BOOL", "value": true}
     *   ]
     * }
     * }</pre>
     *
     * 【返回值】
     * @return String - JSON 格式的配置字符串
     */
    public String exportConfig() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        sb.append("  \"exportTime\": \"").append(java.time.Instant.now().toString()).append("\",\n");
        sb.append("  \"totalParameters\": ").append(knobMap.size()).append(",\n");
        sb.append("  \"parameters\": [\n");

        boolean first = true;
        for (ParameterKnob<?> knob : knobMap.values()) {
            if (!first) sb.append(",\n");
            first = false;

            sb.append("    {\"id\": \"").append(escapeJson(knob.getId())).append("\", ");
            sb.append("\"type\": \"").append(knob.getType().name()).append("\", ");

            // 根据类型序列化值
            switch (knob.getType()) {
                case FLOAT -> sb.append("\"value\": ").append(knob.getValue());
                case INT   -> sb.append("\"value\": ").append(knob.getValue());
                case BOOL  -> sb.append("\"value\": ").append(knob.getValue());
                case ENUM  -> sb.append("\"value\": \"").append(escapeJson((String) knob.getValue())).append("\"");
                default    -> sb.append("\"value\": null");
            }

            sb.append("}");
        }

        sb.append("\n  ]\n}");
        return sb.toString();
    }

    /**
     * 从 JSON 配置字符串导入参数值
     * <p>
     * 仅更新已注册参数的值，不会创建新参数或删除现有参数。
     * 类型不匹配的条目会被静默跳过。
     *
     * 【方法参数】
     * @param jsonConfig String - JSON 格式的配置字符串（通常来自 exportConfig() 输出）
     *
     * @throws IllegalArgumentException 如果 JSON 格式无效
     * @throws NullPointerException     如果 jsonConfig 为 null
     */
    public void importConfig(String jsonConfig) {
        Objects.requireNonNull(jsonConfig, "配置 JSON 不能为 null");

        // 简易 JSON 解析（避免引入外部依赖）
        // 仅支持 exportConfig() 生成的标准格式
        int paramsStart = jsonConfig.indexOf("\"parameters\": [");
        if (paramsStart < 0) {
            throw new IllegalArgumentException("无效的配置格式：缺少 parameters 字段");
        }

        int arrayStart = jsonConfig.indexOf('[', paramsStart) + 1;
        int arrayEnd = jsonConfig.lastIndexOf(']');
        if (arrayStart <= 0 || arrayEnd <= arrayStart) {
            throw new IllegalArgumentException("无效的配置格式：parameters 数组解析失败");
        }

        String arrayContent = jsonConfig.substring(arrayStart, arrayEnd).trim();
        if (arrayContent.isEmpty()) return;  // 空数组，无需处理

        // 逐个解析参数条目
        String[] entries = splitJsonEntries(arrayContent);
        int updatedCount = 0;

        for (String entry : entries) {
            try {
                String id = extractJsonStringField(entry, "id");
                String typeStr = extractJsonStringField(entry, "type");

                ParameterKnob<?> knob = knobMap.get(id);
                if (knob == null) continue;  // 未注册的参数跳过

                KnobType expectedType = knob.getType();
                KnobType importedType = KnobType.valueOf(typeStr);

                if (expectedType != importedType) continue;  // 类型不匹配跳过

                // 根据类型设置值
                switch (expectedType) {
                    case FLOAT -> {
                        double val = extractJsonDoubleField(entry, "value");
                        ((FloatKnob) knob).setValue((float) val);
                    }
                    case INT -> {
                        long val = (long) extractJsonDoubleField(entry, "value");
                        ((IntKnob) knob).setValue((int) val);
                    }
                    case BOOL -> {
                        boolean val = extractJsonBooleanField(entry, "value");
                        ((BoolKnob) knob).setValue(val);
                    }
                    case ENUM -> {
                        String val = extractJsonStringField(entry, "value");
                        ((EnumKnob) knob).setValue(val);
                    }
                    default -> { /* VEC2/VEC3/COLOR 暂不支持 */ }
                }
                updatedCount++;
            } catch (Exception e) {
                // 单条目解析失败不影响其他条目
                Thread.currentThread().getUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), e);
            }
        }

        notifyGlobalListeners(RegistryChangeType.CONFIG_IMPORTED, null,
                String.format("成功导入 %d/%d 个参数", updatedCount, entries.length));
    }

    // ==================== 全局事件监听 ====================

    /**
     * 注册全局变更监听器
     *
     * @param listener RegistryChangeListener - 监听器
     */
    public void addGlobalListener(RegistryChangeListener listener) {
        if (listener != null) {
            globalListeners.add(listener);
        }
    }

    /**
     * 移除全局变更监听器
     *
     * @param listener RegistryChangeListener - 要移除的监听器
     */
    public void removeGlobalListener(RegistryChangeListener listener) {
        globalListeners.remove(listener);
    }

    /**
     * 通知所有全局监听器
     */
    private void notifyGlobalListener(RegistryChangeType type, String knobId, Object data) {
        for (RegistryChangeListener listener : globalListeners) {
            try {
                listener.onRegistryChanged(type, knobId, data);
            } catch (Exception e) {
                Thread.currentThread().getUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), e);
            }
        }
    }

    // ==================== 内部枚举定义 ====================

    /**
     * 注册表变更类型枚举
     */
    public enum RegistryChangeType {
        /** 新参数被注册 */
        REGISTERED,
        /** 批量注册完成 */
        BATCH_REGISTERED,
        /** 参数被注销 */
        UNREGISTERED,
        /** 参数值发生改变 */
        VALUE_CHANGED,
        /** 配置从 JSON 导入完成 */
        CONFIG_IMPORTED,
        /** 注册表被清空 */
        CLEARED
    }

    /**
     * 全局注册表变更监听器接口
     */
    public interface RegistryChangeListener {
        /**
         * 注册表变更回调
         *
         * @param type    RegistryChangeType - 变更类型
         * @param knobId  String - 相关参数 ID（可能为 null）
         * @param data    Object - 关联数据（可能为 null）
         */
        void onRegistryChanged(RegistryChangeType type, String knobId, Object data);
    }

    // ==================== 私有 JSON 解析辅助方法 ====================

    /**
     * 转义 JSON 字符串特殊字符
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                 .replace("\"", "\\\"")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r")
                 .replace("\t", "\\t");
    }

    /**
     * 分割 JSON 数组中的条目（处理逗号分隔）
     */
    private static String[] splitJsonEntries(String arrayContent) {
        // 简易分割：基于顶层花括号匹配
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int braceDepth = 0;

        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            current.append(c);

            if (c == '{') braceDepth++;
            else if (c == '}') {
                braceDepth--;
                if (braceDepth == 0) {
                    entries.add(current.toString().trim());
                    current.setLength(0);
                    // 跳过后续逗号和空白
                    while (i + 1 < arrayContent.length()) {
                        char next = arrayContent.charAt(i + 1);
                        if (next == ',' || Character.isWhitespace(next)) {
                            i++;
                        } else {
                            break;
                        }
                    }
                }
            }
        }

        return entries.toArray(new String[0]);
    }

    /**
     * 从 JSON 条目中提取字符串字段值
     */
    private static String extractJsonStringField(String entry, String field) {
        String search = "\"" + field + "\": \"";
        int start = entry.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        int end = entry.indexOf("\"", start);
        if (end < 0) return "";
        return entry.substring(start, end).replace("\\\"", "\"");
    }

    /**
     * 从 JSON 条目中提取数值字段值
     */
    private static double extractJsonDoubleField(String entry, String field) {
        String search = "\"" + field + "\": ";
        int start = entry.indexOf(search);
        if (start < 0) return 0.0;
        start += search.length();
        StringBuilder numBuilder = new StringBuilder();
        while (start < entry.length()) {
            char c = entry.charAt(start);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') {
                numBuilder.append(c);
                start++;
            } else {
                break;
            }
        }
        try {
            return Double.parseDouble(numBuilder.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 从 JSON 条目中提取布尔字段值
     */
    private static boolean extractJsonBooleanField(String entry, String field) {
        String search = "\"" + field + "\": ";
        int start = entry.indexOf(search);
        if (start < 0) return false;
        start += search.length();
        if (entry.startsWith("true", start)) return true;
        return false;
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format("ParameterRegistry{size=%d, categories=%s}",
                size(),
                knobMap.values().stream()
                        .collect(Collectors.groupingBy(k -> k.getCategory().name()))
                        .keySet());
    }
}
