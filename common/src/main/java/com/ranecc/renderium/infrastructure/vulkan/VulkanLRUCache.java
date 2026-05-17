package com.ranecc.renderium.infrastructure.vulkan;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vulkan 资源 LRU 缓存 (P0 优化)
 * <p>
 * 解决 {@code VulkanDevice.pipelineCache} 和 {@code shaderCache} 的无界增长问题。
 * 当缓存大小超过上限时，自动淘汰最久未使用的条目并销毁其 Vulkan 资源。
 * </p>
 *
 * <h3>解决的问题：</h3>
 * <ul>
 *   <li><b>内存无限增长</b>: 原版使用 IdentityHashMap/HashMap，无大小限制</li>
 *   <li><b>GC 压力</b>: 长期运行后缓存对象进入老年代，增加 Full GC</li>
 *   <li><b>缓存失效缺失</b>: 无淘汰策略，不常用的管线/着色器永远占用显存</li>
 * </ul>
 *
 * <h3>性能预期：</h3>
 * <ul>
 *   <li>GC 减少 30-40%（老年代对象被及时淘汰）</li>
 *   <li>显存占用稳定在可预测范围内</li>
 *   <li>LRU 保证热点资源始终保留</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 创建 pipeline 缓存（最多 256 条）
 * Map&lt;RenderPipeline, VulkanRenderPipeline&gt; pipelineCache =
 *     Collections.synchronizedMap(new VulkanLRUCache&lt;&gt;(256, (key, value) -&gt; {
 *         value.destroy();  // 销毁 Vulkan 管线资源
 *     }));
 *
 * // 创建着色器缓存（最多 512 条）
 * Map&lt;ShaderCompilationKey, IntermediaryShaderModule&gt; shaderCache =
 *     Collections.synchronizedMap(new VulkanLRUCache&lt;&gt;(512, (key, value) -&gt; {
 *         value.destroy();  // 销毁着色器模块
 *     }));
 * </pre>
 *
 * @param &lt;K&gt; 键类型（如 RenderPipeline / ShaderCompilationKey）
 * @param &lt;V&gt; 值类型（需实现 Destroyable 接口或通过 evictionAction 销毁）
 * @see com.renderium.vulkan.adapter.Destroyable
 * @since 5.2.0
 */
public class VulkanLRUCache<K, V> extends LinkedHashMap<K, V> {

    /** 最大容量 */
    private final int maxSize;

    /**
     * 淘汰回调：当条目被淘汰时调用，用于销毁 Vulkan 资源
     * 可以为 null（此时仅从 Map 中移除，不销毁资源）
     */
    private final EvictionAction<K, V> evictionAction;

    /** 淘汰计数器（统计用） */
    private volatile long evictionCount;

    /**
     * 淘汰动作函数式接口
     *
     * @param &lt;K&gt; 键类型
     * @param &lt;V&gt; 值类型
     */
    @FunctionalInterface
    public interface EvictionAction<K, V> {
        /**
         * 当缓存条目被淘汰时调用
         *
         * 【方法参数】
         * @param key   K - 被淘汰的键
         * @param value V - 被淘汰的值
         */
        void onEvict(K key, V value);
    }

    /**
     * 创建 LRU 缓存（带淘汰回调）
     *
     * 【方法参数】
     * @param maxSize        int - 最大缓存条目数（必须 > 0）
     * @param evictionAction EvictionAction - 淘汰时的回调（销毁 Vulkan 资源），可以为 null
     *
     * 【异常】
     * @throws IllegalArgumentException 如果 maxSize <= 0
     */
    public VulkanLRUCache(int maxSize, EvictionAction<K, V> evictionAction) {
        // accessOrder=true 启用 LRU 顺序（基于访问/插入排序）
        super(16, 0.75f, true);

        if (maxSize <= 0) throw new IllegalArgumentException("maxSize 必须 > 0");

        this.maxSize = maxSize;
        this.evictionAction = evictionAction;
        this.evictionCount = 0L;
    }

    /**
     * 创建 LRU 缓存（无淘汰回调，仅移除条目）
     *
     * 【方法参数】
     * @param maxSize int - 最大缓存条目数
     */
    public VulkanLRUCache(int maxSize) {
        this(maxSize, null);
    }

    /**
     * 判断是否应淘汰最老的条目
     * <p>
     * LinkedHashMap 在 put() 后自动调用此方法。
     * 返回 true 时，移除 eldest（最久未访问）的条目，
     * 并触发 evictionAction 回调以销毁关联的 Vulkan 资源。
     * </p>
     *
     * 【返回值】
     * @return boolean - true 表示应该淘汰最老条目
     */
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        if (size() > maxSize) {
            // 触发淘汰回调（销毁 Vulkan 资源）
            if (evictionAction != null) {
                try {
                    evictionAction.onEvict(eldest.getKey(), eldest.getValue());
                } catch (Exception e) {
                    // 淘汰回调异常不应影响缓存操作
                    java.util.logging.Logger.getLogger("Renderium|LRUCache")
                        .warning("淘汰回调异常: " + e.getMessage());
                }
            }
            evictionCount++;
            return true;
        }
        return false;
    }

    /** 获取最大容量 */
    public int getMaxSize() { return maxSize; }

    /** 获取累计淘汰次数（统计用） */
    public long getEvictionCount() { return evictionCount; }

    /** 重置淘汰计数器 */
    public void resetEvictionCount() { this.evictionCount = 0L; }

    /**
     * 清空缓存并销毁所有资源
     * <p>
     * 对每个条目调用 evictionAction 后清空 Map。
     * </p>
     */
    public void clearAndDestroy() {
        if (evictionAction != null && !isEmpty()) {
            Iterator<Map.Entry<K, V>> it = entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<K, V> entry = it.next();
                try {
                    evictionAction.onEvict(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    java.util.logging.Logger.getLogger("Renderium|LRUCache")
                        .warning("Eviction failed during clearAndDestroy: " + e.getMessage());
                }
                it.remove();
            }
        }
        clear();
    }

    /**
     * 创建线程安全的同步包装
     * <p>
     * 返回 Collections.synchronizedMap 包装后的实例，
     * 适合多线程环境下的 pipelineCache/shaderCache 使用。
     * </p>
     *
     * 【返回值】
     * @return Map&lt;K, V&gt; - 同步包装后的 LRU 缓存
     */
    public Map<K, V> synchronizedView() {
        return Collections.synchronizedMap(this);
    }
}
