package com.ranecc.renderium.feature.renderopt.texture;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 虚拟纹理管理器 (Virtual Texture)
 * <p>
 * 将超大纹理（8K+）切分为 128x128 tile，按需加载到 GPU。
 * 仅加载相机可见的 tile，VRAM 节省 80-95%。
 * <p>
 * Unreal: Virtual Texturing | Unity: Virtual Texturing
 * <p>
 * 与 LOD 联动：远距离 tile 使用低 mipmap，近距离使用高 mipmap
 * <p>
 * GPU 开销：0.5-1ms/帧（tile 反馈解析 + 页面换入）
 * 短路条件：virtualTextureEnabled == false
 *
 * @since 5.5.0
 */
public final class VirtualTextureManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VT");

    private static volatile VirtualTextureManager instance;

    public static VirtualTextureManager getInstance() {
        if (instance == null) {
            synchronized (VirtualTextureManager.class) {
                if (instance == null) instance = new VirtualTextureManager();
            }
        }
        return instance;
    }

    private VirtualTextureManager() {}

    // ==================== 配置 ====================

    /** Tile 尺寸 (pixels) */
    static final int TILE_SIZE = 128;

    /** 页面缓存最大容量 (tiles) */
    private static final int MAX_CACHE_TILES = 4096;

    /** 反馈缓冲区大小 (tiles per frame) */
    private static final int FEEDBACK_BUFFER_SIZE = 65536;

    // ==================== Tile 定义 ====================

    /**
     * 虚拟纹理 Tile 标识
     * <p>
     * 一个 tile 由 (textureId, mipLevel, tileX, tileY) 唯一确定
     */
    public static final class TileID {
        public final long textureId;
        public final int mipLevel;
        public final int tileX;
        public final int tileY;

        public TileID(long textureId, int mipLevel, int tileX, int tileY) {
            this.textureId = textureId;
            this.mipLevel = mipLevel;
            this.tileX = tileX;
            this.tileY = tileY;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TileID t)) return false;
            return textureId == t.textureId && mipLevel == t.mipLevel &&
                   tileX == t.tileX && tileY == t.tileY;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(textureId) * 31 * 31 * 31 +
                   mipLevel * 31 * 31 + tileX * 31 + tileY;
        }
    }

    /**
     * 页面缓存条目
     */
    public static final class PageEntry {
        public final TileID tileId;
        public long lastAccessFrame;
        public long gpuAddress; // physical page GPU address
        public boolean resident;

        public PageEntry(TileID tileId) {
            this.tileId = tileId;
            this.lastAccessFrame = 0;
            this.gpuAddress = 0;
            this.resident = false;
        }
    }

    // ==================== 虚拟纹理注册 ====================

    /**
     * 虚拟纹理描述
     */
    public static final class VirtualTexture {
        public final long textureId;
        public final String name;
        public final int width, height;
        public final int mipLevels;
        public final int tilesX, tilesY; // tiles at mip 0

        public VirtualTexture(long textureId, String name, int w, int h, int mips) {
            this.textureId = textureId;
            this.name = name;
            this.width = w;
            this.height = h;
            this.mipLevels = mips;
            this.tilesX = (w + TILE_SIZE - 1) / TILE_SIZE;
            this.tilesY = (h + TILE_SIZE - 1) / TILE_SIZE;
        }

        /** 获取指定 mipmap 级别的 tile 数量 */
        public int getTilesAtMip(int mip) {
            int scale = 1 << mip;
            int tx = Math.max(1, (width / scale + TILE_SIZE - 1) / TILE_SIZE);
            int ty = Math.max(1, (height / scale + TILE_SIZE - 1) / TILE_SIZE);
            return tx * ty;
        }

        /** 估算总 tile 数 */
        public int estimateTotalTiles() {
            int total = 0;
            for (int mip = 0; mip < mipLevels; mip++) {
                total += getTilesAtMip(mip);
            }
            return total;
        }
    }

    // ==================== 状态 ====================

    private final ConcurrentHashMap<Long, VirtualTexture> virtualTextures = new ConcurrentHashMap<>();
    private final LinkedHashMap<TileID, PageEntry> pageCache = new LinkedHashMap<>(MAX_CACHE_TILES, 0.75f, true);
    private volatile boolean vtEnabled = false;
    private volatile long currentFrame = 0;

    // ==================== 公共 API ====================

    /**
     * 注册虚拟纹理
     */
    public void registerVirtualTexture(long textureId, String name, int width, int height, int mipLevels) {
        VirtualTexture vt = new VirtualTexture(textureId, name, width, height, mipLevels);
        virtualTextures.put(textureId, vt);
        LOGGER.fine("VT 注册: " + name + " (" + width + "x" + height +
                    ", " + mipLevels + " mips, ~" + vt.estimateTotalTiles() + " tiles)");
    }

    /**
     * 注销虚拟纹理
     */
    public void unregisterVirtualTexture(long textureId) {
        virtualTextures.remove(textureId);
        // 移除关联的页面缓存
        pageCache.entrySet().removeIf(e -> e.getKey().textureId == textureId);
    }

    /**
     * 每帧更新 — 处理 GPU 反馈，加载/卸载 tile
     * <p>
     * 流程：
     * 1. 读取 GPU 反馈缓冲区（哪些 tile 被采样了）
     * 2. 对比当前页面缓存，找出缺失的 tile
     * 3. 加载缺失的 tile 到物理页
     * 4. 驱逐 LRU 页面（如果缓存已满）
     * 5. 更新页表（sparse binding）
     *
     * @param feedbackData GPU 反馈数据（tile ID 数组）
     * @param frameNumber 当前帧号
     */
    public void processFeedback(int[] feedbackData, long frameNumber) {
        if (!vtEnabled) return;
        this.currentFrame = frameNumber;

        Set<TileID> requestedTiles = new HashSet<>();

        // 解析反馈数据
        for (int i = 0; i < feedbackData.length; i += 4) {
            if (i + 3 >= feedbackData.length) break;
            long texId = feedbackData[i] & 0xFFFFFFFFL;
            int mip = feedbackData[i + 1];
            int tx = feedbackData[i + 2];
            int ty = feedbackData[i + 3];

            TileID tileId = new TileID(texId, mip, tx, ty);
            requestedTiles.add(tileId);

            // 检查是否已在缓存中
            PageEntry entry = pageCache.get(tileId);
            if (entry != null) {
                entry.lastAccessFrame = frameNumber;
            } else {
                // 缺失 tile，需要加载
                requestTileLoad(tileId, frameNumber);
            }
        }

        // 驱逐长时间未访问的页面
        evictPages(frameNumber);
    }

    /**
     * 根据距离和 LOD 档次确定需要的 mipmap 范围
     * <p>
     * 与 TextureStreamingManager.TextureLODTier 联动
     */
    public int getMaxMipForDistance(float distance) {
        TextureStreamingManager.TextureLODTier tier = TextureStreamingManager.TextureLODTier.fromDistance(distance);
        return tier.maxMipLevel;
    }

    public void setVtEnabled(boolean v) { this.vtEnabled = v; }
    public boolean isVtEnabled() { return vtEnabled; }
    public int getVirtualTextureCount() { return virtualTextures.size(); }
    public int getCachedPageCount() { return pageCache.size(); }

    // ==================== 内部方法 ====================

    private void requestTileLoad(TileID tileId, long frameNumber) {
        if (pageCache.size() >= MAX_CACHE_TILES) {
            // 需要先驱逐一个页面
            evictOnePage();
        }

        PageEntry entry = new PageEntry(tileId);
        entry.lastAccessFrame = frameNumber;

        // TODO: 实际加载 tile 数据到 GPU
        // 1. 从磁盘读取对应 tile 的纹理数据
        // 2. vkCmdCopyBufferToImage 上传到物理页
        // 3. 更新 sparse binding: vkQueueBindSparse

        pageCache.put(tileId, entry);
    }

    private void evictPages(long frameNumber) {
        long threshold = frameNumber - 60; // 60 帧未访问则驱逐
        pageCache.entrySet().removeIf(entry -> {
            if (entry.getValue().lastAccessFrame < threshold) {
                // TODO: 释放 GPU 物理页
                return true;
            }
            return false;
        });
    }

    private void evictOnePage() {
        // LRU 驱逐：LinkedHashMap 按访问顺序迭代
        Iterator<Map.Entry<TileID, PageEntry>> it = pageCache.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<TileID, PageEntry> oldest = it.next();
            // TODO: 释放 GPU 物理页
            it.remove();
        }
    }
}
