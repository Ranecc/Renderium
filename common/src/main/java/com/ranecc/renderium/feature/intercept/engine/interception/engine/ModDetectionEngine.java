package com.ranecc.renderium.feature.intercept.engine.interception.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模组检测引擎接口
 * <p>
 * 用于检测 Minecraft 中已加载的第三方渲染模组（如 Iris、Oculus），
 * 并缓存检测结果以避免重复的反射开销。
 * </p>
 *
 * <h3>支持的模组：</h3>
 * <table border="1">
 *   <tr><th>模组 ID</th><th>主类路径</th><th>功能</th></tr>
 *   <tr><td>iris</td><td>net.coderbot.iris.Iris</td><td>光影加载器</td></tr>
 *   <tr><td>oculus</td><td>curse.oculus.Oculus</td><td>VR 模组</td></tr>
 * </table>
 *
 * <h3>调用示例：</h3>
 * <pre>
 * ModDetectionEngine engine = new DefaultModDetectionEngine();
 *
 * // 检测单个模组
 * if (engine.detectMod("iris")) {
 *     System.out.println("光影模组已加载，应用兼容性优化");
 * }
 *
 * // 批量检测所有已知模组
 * Map&lt;String, Boolean&gt; results = engine.batchDetect();
 * results.forEach((mod, found) -&gt; System.out.println(mod + ": " + found));
 *
 * // 获取已检测到的模组列表
 * String[] mods = engine.getDetectedMods();  // ["iris"]
 * </pre>
 *
 * @see com.renderium.interception.engine.DefaultModDetectionEngine
 * @since 5.2.0
 */
public interface ModDetectionEngine {

    /**
     * 检测指定模组是否已加载
     * <p>
     * 先从内部缓存查询（O(1)），缓存未命中时使用 {@link Class#forName(String)} 反射检测，
     * 检测结果存入缓存以供后续快速查询。
     * </p>
     *
     * 【方法参数】
     * @param modId String - 模组标识符（小写），有效值："sodium", "iris", "oculus"
     *
     * 【返回值】
     * @return boolean - 检测结果：
     *         true  - 模组存在且已成功加载
     *         false - 模组不存在、未加载、或 modId 不在支持列表中
     *
     * 【异常】
     * @throws IllegalArgumentException 如果 modId 为 null 或空字符串
     *
     * 【性能特征】
     * - 缓存命中：< 0.001ms (ConcurrentHashMap.get)
     * - 首次检测：~1ms (ClassLoader.forName)
     * - 线程安全：ConcurrentHashMap 保证
     */
    boolean detectMod(String modId);

    /**
     * 异步批量预检测所有已知模组
     * <p>
     * 在游戏启动时调用一次，预热缓存。
     * 遍历所有已注册的模组 ID 并逐个检测。
     * </p>
     *
     * 【返回值】
     * @return Map&lt;String, Boolean&gt; - 检测结果映射：
     *         key   - 模组 ID
     *         value - 是否存在（true/false）
     *
     * 【注意事项】
     * - 结果会自动写入内部缓存
     * - 后续调用 detectMod() 将直接返回缓存值
     */
    Map<String, Boolean> batchDetect();

    /**
     * 获取已检测到的模组列表
     * <p>
     * 过滤内部缓存中值为 true 的条目，返回模组 ID 数组。
     * </p>
     *
     * 【返回值】
     * @return String[] - 已加载的模组 ID 数组（可能为空数组，但不会为 null）
     */
    String[] getDetectedMods();
}
