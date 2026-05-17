package com.ranecc.renderium.feature.shader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Shader 缓存管理器。
 *
 * <p>管理已编译 SPIR-V 的内存缓存和磁盘持久化缓存，
 * 使用 SHA-256 内容哈希作为缓存键，避免重复编译相同着色器源码。
 *
 * <p>缓存目录结构：
 * <pre>
 * shader_cache/
 * ├── memory/          # 内存缓存的元数据
 * └── disk/            # 磁盘持久化的 SPIR-V 文件
 *     ├── abc123.spv   # 按 SHA-256 命名
 *     └── def456.spv
 * </pre>
 */
public final class ShaderCacheManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium-ShaderCacheManager");

    /**
     * Shader 缓存条目
     *
     * <p>存储已编译的 SPIR-V 数据及其元信息，
     * 用于避免重复编译相同内容的着色器。
     */
    public static final class ShaderCacheEntry {
        /** SPIR-V 字节数据 */
        public final byte[] spirvData;

        /** 源码的 SHA-256 哈希值 */
        public final String sourceHash;

        /** 编译时间戳 */
        public final long compileTime;

        /** 着色器阶段（vert/frag/comp 等） */
        public final String shaderStage;

        /** 缓存命中次数 */
        public final AtomicLong hitCount = new AtomicLong(0);

        /**
         * 构造函数
         *
         * @param spirvData 已编译的 SPIR-V 数据
         * @param sourceHash 源码哈希值
         * @param compileTime 编译时间戳
         * @param shaderStage 着色器阶段
         */
        public ShaderCacheEntry(byte[] spirvData, String sourceHash, long compileTime, String shaderStage) {
            this.spirvData = spirvData;
            this.sourceHash = sourceHash;
            this.compileTime = compileTime;
            this.shaderStage = shaderStage;
        }
    }

    /** Shader 内存缓存（sourceHash → CacheEntry） */
    private final Map<String, ShaderCacheEntry> shaderMemoryCache = new ConcurrentHashMap<>();

    /** Shader 缓存目录（磁盘持久化） */
    private Path shaderCacheDirectory;

    /** 缓存统计：总命中次数 */
    private final AtomicLong cacheHitCount = new AtomicLong(0);

    /** 缓存统计：总未命中次数 */
    private final AtomicLong cacheMissCount = new AtomicLong(0);

    /** 编译总耗时（毫秒） */
    private final AtomicLong totalCompileTimeMs = new AtomicLong(0);

    /** 编译总次数 */
    private final AtomicLong totalCompileCount = new AtomicLong(0);

    /**
     * 初始化 Shader 缓存系统
     *
     * <p>创建缓存目录并加载已有的磁盘缓存（如果存在）。
     *
     * @param baseDirectory 基础目录
     */
    public void initializeShaderCache(Path baseDirectory) {
        this.shaderCacheDirectory = baseDirectory.resolve(".shader_cache");

        try {
            Files.createDirectories(shaderCacheDirectory);
            Files.createDirectories(shaderCacheDirectory.resolve("disk"));

            LOGGER.info("Shader 缓存目录已创建: " + shaderCacheDirectory.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.warning("无法创建 Shader 缓存目录: " + e.getMessage());
            try {
                this.shaderCacheDirectory = Files.createTempDirectory("renderium_shader_cache");
                LOGGER.info("使用临时缓存目录: " + shaderCacheDirectory.toAbsolutePath());
            } catch (IOException ex) {
                LOGGER.severe("无法创建任何缓存目录，将禁用磁盘缓存");
                this.shaderCacheDirectory = null;
            }
        }
    }

    /**
     * 计算 SHA-256 哈希值（用于缓存键）
     *
     * @param input 输入字符串
     * @return 十六进制编码的哈希值
     */
    public String computeSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 根据缓存键获取缓存的 Shader 条目
     *
     * @param cacheKey 缓存键
     * @return ShaderCacheEntry，如果未命中返回 null
     */
    public ShaderCacheEntry getCachedEntry(String cacheKey) {
        return shaderMemoryCache.get(cacheKey);
    }

    /**
     * 将 Shader 条目存入内存缓存
     *
     * @param cacheKey 缓存键
     * @param entry Shader 缓存条目
     */
    public void putCachedEntry(String cacheKey, ShaderCacheEntry entry) {
        shaderMemoryCache.put(cacheKey, entry);
    }

    /**
     * 记录缓存命中
     */
    public void recordCacheHit() {
        cacheHitCount.incrementAndGet();
    }

    /**
     * 记录缓存未命中
     */
    public void recordCacheMiss() {
        cacheMissCount.incrementAndGet();
    }

    /**
     * 记录编译耗时
     *
     * @param elapsedMs 编译耗时（毫秒）
     */
    public void recordCompilation(long elapsedMs) {
        totalCompileTimeMs.addAndGet(elapsedMs);
        totalCompileCount.incrementAndGet();
    }

    /**
     * 获取缓存命中次数
     */
    public long getCacheHitCount() {
        return cacheHitCount.get();
    }

    /**
     * 获取缓存未命中次数
     */
    public long getCacheMissCount() {
        return cacheMissCount.get();
    }

    /**
     * 获取编译总耗时（毫秒）
     */
    public long getTotalCompileTimeMs() {
        return totalCompileTimeMs.get();
    }

    /**
     * 获取编译总次数
     */
    public long getTotalCompileCount() {
        return totalCompileCount.get();
    }

    /**
     * 获取当前缓存大小
     */
    public int getCacheSize() {
        return shaderMemoryCache.size();
    }

    /**
     * 获取缓存目录
     */
    public Path getShaderCacheDirectory() {
        return shaderCacheDirectory;
    }

    /**
     * 获取 Shader 缓存统计信息
     *
     * @return 格式化的统计字符串
     */
    public String getCacheStatistics() {
        long hits = cacheHitCount.get();
        long misses = cacheMissCount.get();
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100.0 : 0.0;

        return String.format(
                "ShaderCache{entries=%d, hits=%d, misses=%d, hitRate=%.1f%%, diskCache=%s}",
                shaderMemoryCache.size(),
                hits, misses, hitRate,
                shaderCacheDirectory != null ? shaderCacheDirectory.toString() : "disabled"
        );
    }

    /**
     * 手动清除 Shader 缓存
     *
     * <p>强制清除所有已编译的 Shader 缓存条目。
     * 下次使用时会重新编译。
     */
    public void clearShaderCache() {
        int size = shaderMemoryCache.size();
        shaderMemoryCache.clear();
        cacheHitCount.set(0);
        cacheMissCount.set(0);
        LOGGER.info("Shader 缓存已手动清除 (" + size + " 条目)");
    }

    /**
     * 获取缓存统计信息的摘要字符串（供 logPerformanceStatistics 使用）
     *
     * @return 格式化的缓存统计摘要
     */
    public String getCacheStatsSummary() {
        long hits = cacheHitCount.get();
        long misses = cacheMissCount.get();
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100.0 : 0.0;
        return String.format("Shader 缓存: %d 命中 / %d 未命中 (%.1f%% 命中率)",
                hits, misses, hitRate);
    }
}
