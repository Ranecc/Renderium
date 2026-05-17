// Renderium - Blaze3D Shader 转译模块
// SPIR-V 编译结果缓存 - L1 内存 + L2 磁盘双层缓存

package com.ranecc.renderium.feature.blaze3d.shader;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * SPIR-V 编译结果缓存。
 *
 * <h2>缓存层级：</h2>
 * <pre>
 * L1: 内存缓存 (ConcurrentHashMap) — 微秒级访问
 * L2: 磁盘缓存 (文件系统)         — 毫秒级访问
 * </pre>
 *
 * <h2>缓存键计算：</h2>
 * <pre>
 * SHA-256(源文件路径 + shader类型 + defines + 展开后内容).substring(0,20)
 * </pre>
 *
 * @since 3.0.0
 */
public final class ShaderCache {

    private static final Logger LOGGER = Logger.getLogger("Renderium-ShaderCache");

    /** 缓存根目录 */
    private final Path cacheDir;

    /** L1 内存缓存 (线程安全) */
    private final ConcurrentHashMap<String, CacheEntry> memoryCache = new ConcurrentHashMap<>();

    /** 最大内存缓存条目数 */
    private static final int MAX_MEMORY_ENTRIES = 256;

    /**
     * 缓存条目 (包含数据和元信息)
     *
     * @param data SPIR-V 二进制数据
     * @param timestamp 创建时间戳 (毫秒)
     * @param sourcePath 源文件路径 (用于调试)
     */
    private record CacheEntry(byte[] data, long timestamp, Path sourcePath) {}

    /**
     * 构造缓存
     *
     * @param cacheDir 缓存根目录 (不存在则自动创建)
     */
    public ShaderCache(Path cacheDir) {
        this.cacheDir = cacheDir.toAbsolutePath();
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建缓存目录: " + cacheDir, e);
        }
        LOGGER.fine("Shader 缓存目录: " + this.cacheDir);
    }

    /**
     * 获取缓存的 SPIR-V。
     *
     * <h3>查找顺序：</h3>
     * <ol>
     *   <li>L1 内存缓存 → 命中返回副本</li>
     *   <li>L2 磁盘缓存 → 命中则升级到 L1 并返回</li>
     *   <li>未命中 → 返回 null</li>
     * </ol>
     *
     * @param key 缓存键 (SHA-256 前 20 字符 Base64)
     * @return SPIR-V 数据，未命中返回 null
     */
    public byte[] get(String key) {
        // L1: 内存缓存
        CacheEntry entry = memoryCache.get(key);
        if (entry != null) {
            return entry.data().clone();
        }

        // L2: 磁盘缓存
        Path file = getCacheFile(key);
        if (Files.exists(file)) {
            try {
                byte[] data = Files.readAllBytes(file);

                // 升级到 L1 内存缓存
                putToMemory(key, data);

                return data.clone();
            } catch (IOException e) {
                LOGGER.warning("读取磁盘缓存失败: " + key + " → " + e.getMessage());
            }
        }

        return null;
    }

    /**
     * 存储编译结果到缓存。
     *
     * <p>同时写入 L1 内存和 L2 磁盘，L2 写入异步执行不阻塞调用方。</p>
     *
     * @param key   缓存键
     * @param spirv SPIR-V 二进制数据
     */
    public void put(String key, byte[] spirv) {
        // 写入 L1 内存
        putToMemory(key, spirv);

        // 异步写入 L2 磁盘
        CompletableFuture.runAsync(() -> {
            try {
                Path file = getCacheFile(key);
                Files.createDirectories(file.getParent());
                Files.write(file, spirv);
            } catch (IOException e) {
                LOGGER.warning("写入磁盘缓存失败: " + key + " → " + e.getMessage());
            }
        });
    }

    /**
     * 检查缓存中是否存在指定 key
     */
    public boolean containsKey(String key) {
        if (memoryCache.containsKey(key)) return true;
        return Files.exists(getCacheFile(key));
    }

    /**
     * 清除所有缓存 (内存 + 磁盘)
     */
    public void clear() {
        memoryCache.clear();
        clearDiskCache();
    }

    /**
     * 清理过期的缓存条目
     *
     * @param maxAgeMs 最大存活时间 (毫秒)，超过此时间的条目被删除
     */
    public void pruneOldEntries(long maxAgeMs) {
        long threshold = System.currentTimeMillis() - maxAgeMs;

        // 清理 L1 内存
        memoryCache.entrySet().removeIf(e ->
                e.getValue().timestamp() < threshold);

        // 清理 L2 磁盘
        try {
            long finalThreshold = threshold;
            Files.walk(cacheDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".spv"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() < finalThreshold;
                        } catch (IOException ex) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) {
                            LOGGER.fine("Failed to delete cache file: " + e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.fine("Failed to create/delete cache file: " + e);
        }
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        int diskEntries = countDiskCache();
        long diskSize = totalDiskSize();

        return new CacheStats(
                memoryCache.size(),
                diskEntries,
                diskSize,
                cacheDir.toString()
        );
    }

    // ==================== 内部实现 ====================

    /** 存入 L1 内存缓存 (带容量限制和 LRU 淘汰) */
    private void putToMemory(String key, byte[] data) {
        // 容量超限时淘汰最老的 25%
        if (memoryCache.size() >= MAX_MEMORY_ENTRIES) {
            pruneMemoryLRU(MAX_MEMORY_ENTRIES / 4);
        }
        memoryCache.put(key, new CacheEntry(data.clone(), System.currentTimeMillis(), null));
    }

    /** LRU 淘汰 (按时间戳排序移除最老条目) */
    private void pruneMemoryLRU(int count) {
        memoryCache.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().timestamp()))
                .limit(count)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(memoryCache::remove);
    }

    /** 获取缓存文件的完整路径 (使用子目录防止单目录文件过多) */
    private Path getCacheFile(String key) {
        String subDir = key.substring(0, Math.min(2, key.length()));
        Path subPath = cacheDir.resolve(subDir);
        try { Files.createDirectories(subPath); } catch (IOException e) {
            LOGGER.fine("Failed to create cache directory: " + e);
        }
        return subPath.resolve(key + ".spv");
    }

    /** 统计磁盘缓存文件数 */
    private int countDiskCache() {
        try {
            return (int) Files.walk(cacheDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".spv"))
                    .count();
        } catch (IOException e) { return 0; }
    }

    /** 统计磁盘缓存总大小 */
    private long totalDiskSize() {
        try {
            return Files.walk(cacheDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".spv"))
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0; }
                    })
                    .sum();
        } catch (IOException e) { return 0; }
    }

    /** 清理磁盘缓存 */
    private void clearDiskCache() {
        try {
            Files.walk(cacheDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".spv"))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) {
                            LOGGER.fine("Failed to delete cache file: " + e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.fine("Failed to clear disk cache: " + e);
        }
    }

    /**
     * 缓存统计
     *
     * @param memoryEntries 内存缓存条目数
     * @param diskEntries    磁盘缓存条目数
     * @param diskSizeBytes  磁盘缓存总大小 (字节)
     * @param cacheDir       缓存目录路径
     */
    public record CacheStats(int memoryEntries, int diskEntries,
                             long diskSizeBytes, String cacheDir) {}
}
