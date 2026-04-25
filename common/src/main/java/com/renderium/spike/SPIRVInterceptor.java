// Renderium - SPIR-V 字节流拦截器
// 用于拦截 Minecraft 官方 Vulkan 后端的 SPIR-V 字节流
// 来源文档: spirv-interception.md
// 策略ID: SPIKE1 (SPIR-V Interception Strategy #1)
// 预期收益: 实现光影热插拔、着色器缓存、动态重编译

package com.renderium.spike;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * SPIR-V 字节流拦截器 🔍
 *
 * <p>拦截 Minecraft 官方 Vulkan 后端的 SPIR-V 字节流，
 * 实现光影热插拔、着色器缓存、动态重编译等功能。
 *
 * <h2>拦截原理：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    官方 SPIR-V 创建流程                      │
 * │  vkCreateShaderModule() → SPIR-V 字节流传入                │
 * │  → Vulkan 验证并创建 Shader Module                         │
 * └─────────────────────────────────────────────────────────────┘
 *                              ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    Renderium 拦截流程                       │
 * │  Mixin 注入 vkCreateShaderModule()                         │
 * │  → 拦截 SPIR-V 字节流                                      │
 * │  → 保存到缓存池（供光影工作台使用）                        │
 * │  → 继续执行官方流程                                        │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>核心拦截点：</h3>
 * <ul>
 *   <li><b>SPIR-V 字节流</b>: 官方编译后的着色器二进制码</li>
 *   <li><b>Shader Hash</b>: SPIR-V 的唯一标识</li>
 *   <li><b>Shader Metadata</b>: 着色器元数据（类型、标签等）</li>
 * </ul>
 *
 * <h3>拦截后能力：</h3>
 * <ul>
 *   <li><b>光影热插拔</b>: 实时替换 SPIR-V 字节流</li>
 *   <li><b>着色器缓存</b>: 本地缓存 SPIR-V 以加速加载</li>
 *   <li><b>动态重编译</b>: 根据配置动态重编译着色器</li>
 *   <li><b>性能分析</b>: 统计着色器使用频率和性能</li>
 * </ul>
 *
 * <h3>参考文档：</h3>
 * <ul>
 *   <li>spike-spirv-interception.md §1.0（SPIR-V 拦截策略）</li>
 *   <li>shader-workbench-integration.md §2.0（着色器工作台集成）</li>
 *   <li>dynamic-shader-compilation.md §3.0（动态编译策略）</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see PassRouter
 * @see OfficialVulkanHijacker
 */
public class SPIRVInterceptor {

    private static final Logger LOGGER = Logger.getLogger(SPIRVInterceptor.class.getName());

    // ==================== SPIR-V 缓存池 ====================

    /** SPIR-V 字节流缓存（Shader Hash → SPIR-V Bytes） */
    private final Map<Integer, byte[]> spirvCache = new ConcurrentHashMap<>();

    /** 缓存命中统计 */
    private final AtomicLong cacheHits = new AtomicLong(0L);

    /** 缓存未命中统计 */
    private final AtomicLong cacheMisses = new AtomicLong(0L);

    // ==================== 单例模式 ====================

    /** 单例实例（volatile 保证可见性） */
    private static volatile SPIRVInterceptor instance;

    /**
     * 私有构造函数（防止外部实例化）
     */
    private SPIRVInterceptor() {}

    /**
     * 获取单例实例
     *
     * @return SPIRVInterceptor 实例
     */
    public static SPIRVInterceptor getInstance() {
        if (instance == null) {
            synchronized (SPIRVInterceptor.class) {
                if (instance == null) {
                    instance = new SPIRVInterceptor();
                }
            }
        }
        return instance;
    }

    // ==================== SPIR-V 拦截接口 ====================

    /**
     * 拦截 SPIR-V 字节流
     *
     * @param shaderHash SPIR-V 的哈希值（唯一标识）
     * @param spirvBytes SPIR-V 字节流
     */
    public void interceptShader(int shaderHash, byte[] spirvBytes) {
        // 避免重复缓存
        if (!spirvCache.containsKey(shaderHash)) {
            spirvCache.put(shaderHash, spirvBytes.clone());
            cacheMisses.incrementAndGet();
            LOGGER.fine("SPIR-V 拦截: hash=0x" + Integer.toHexString(shaderHash) + 
                       ", size=" + spirvBytes.length + " bytes");
        } else {
            cacheHits.incrementAndGet();
        }
    }

    /**
     * 获取已缓存的 SPIR-V 字节流
     *
     * @param shaderHash SPIR-V 的哈希值
     * @return SPIR-V 字节流（如果存在），否则返回 null
     */
    public byte[] getCachedShader(int shaderHash) {
        byte[] cached = spirvCache.get(shaderHash);
        if (cached != null) {
            cacheHits.incrementAndGet();
        } else {
            cacheMisses.incrementAndGet();
        }
        return cached;
    }

    /**
     * 检查 SPIR-V 是否已缓存
     *
     * @param shaderHash SPIR-V 的哈希值
     * @return true 如果已缓存
     */
    public boolean isShaderCached(int shaderHash) {
        return spirvCache.containsKey(shaderHash);
    }

    // ==================== 缓存管理 ====================

    /**
     * 清空 SPIR-V 缓存
     */
    public void clearCache() {
        spirvCache.clear();
        cacheHits.set(0L);
        cacheMisses.set(0L);
        LOGGER.info("SPIR-V 缓存已清空");
    }

    /**
     * 获取缓存统计信息
     *
     * @return 缓存统计信息字符串
     */
    public String getCacheStats() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100.0 : 0.0;
        
        return String.format("SPIR-V Cache Stats: %d hits, %d misses, %.2f%% hit rate", 
                           hits, misses, hitRate);
    }

    /**
     * 获取缓存大小
     *
     * @return 已缓存的 SPIR-V 数量
     */
    public int getCacheSize() {
        return spirvCache.size();
    }

    // ==================== 生命周期管理 ====================

    /**
     * 关闭拦截器
     */
    public void shutdown() {
        clearCache();
        LOGGER.info("SPIR-V 拦截器已关闭");
    }
}