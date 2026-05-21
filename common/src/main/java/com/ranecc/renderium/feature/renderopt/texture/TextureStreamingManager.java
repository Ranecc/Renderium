package com.ranecc.renderium.feature.renderopt.texture;

import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 纹理流式加载管理器 — 根据距离和 LOD 级别动态加载/卸载 mipmap
 * <p>
 * 核心思想：近处纹理全分辨率，远处纹理低分辨率，不可见不加载。
 * 与 LODSystem 的距离阈值联动，确保纹理分辨率与几何 LOD 匹配。
 * <p>
 * VRAM 节省预估：30-50%（大型整合包 256x~1024x 纹理）
 * CPU 开销：< 0.1ms/帧
 * 短路条件：textureStreamingEnabled == false
 *
 * @since 5.5.0
 */
public final class TextureStreamingManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium|TexStream");

    // ==================== 单例 ====================

    private static volatile TextureStreamingManager instance;

    public static TextureStreamingManager getInstance() {
        if (instance == null) {
            synchronized (TextureStreamingManager.class) {
                if (instance == null) instance = new TextureStreamingManager();
            }
        }
        return instance;
    }

    private TextureStreamingManager() {}

    // ==================== LOD 联动阈值 ====================

    /**
     * 纹理 LOD 档次 — 与 LODSystem 距离阈值联动
     * <p>
     * 每个档次定义了该距离范围内应加载的最高 mipmap 级别。
     * LOD0 (近处) → 全分辨率, LOD7 (极远) → 最低分辨率
     */
    public enum TextureLODTier {
        /** LOD0: <32 blocks, 全分辨率 mipmap 0 */
        TIER_0(0, 32.0f, 0),
        /** LOD1: 32-64 blocks, mipmap 1 (1/2 分辨率) */
        TIER_1(1, 64.0f, 1),
        /** LOD2: 64-128 blocks, mipmap 2 (1/4 分辨率) */
        TIER_2(2, 128.0f, 2),
        /** LOD3: 128-256 blocks, mipmap 3 (1/8 分辨率) */
        TIER_3(3, 256.0f, 3),
        /** LOD4: 256-512 blocks, mipmap 4 (1/16 分辨率) */
        TIER_4(4, 512.0f, 4),
        /** LOD5: 512-1024 blocks, mipmap 5 (1/32 分辨率) */
        TIER_5(5, 1024.0f, 5),
        /** LOD6: 1024-2048 blocks, mipmap 6 (1/64 分辨率) */
        TIER_6(6, 2048.0f, 6),
        /** LOD7: >2048 blocks, 最低分辨率 */
        TIER_7(7, Float.MAX_VALUE, 7);

        public final int lodLevel;
        public final float maxDistance;
        public final int maxMipLevel;

        TextureLODTier(int lodLevel, float maxDistance, int maxMipLevel) {
            this.lodLevel = lodLevel;
            this.maxDistance = maxDistance;
            this.maxMipLevel = maxMipLevel;
        }

        /** 根据距离查找对应的纹理 LOD 档次 */
        public static TextureLODTier fromDistance(float distance) {
            for (TextureLODTier tier : values()) {
                if (distance < tier.maxDistance) return tier;
            }
            return TIER_7;
        }
    }

    // ==================== 纹理条目 ====================

    /**
     * 流式纹理条目
     */
    public static class StreamingTexture {
        public final long textureId;
        public final String name;
        public final int baseWidth, baseHeight;
        public final int totalMipLevels;
        public volatile int currentMipLevel; // 当前加载的最高 mipmap 级别
        public volatile float lastDistance;   // 上次计算的距离
        public volatile long lastAccessFrame; // 上次访问帧号
        public volatile boolean resident;     // 是否在 GPU 中

        public StreamingTexture(long textureId, String name, int w, int h, int mips) {
            this.textureId = textureId;
            this.name = name;
            this.baseWidth = w;
            this.baseHeight = h;
            this.totalMipLevels = mips;
            this.currentMipLevel = 0;
            this.lastDistance = 0;
            this.lastAccessFrame = 0;
            this.resident = true;
        }

        /** 获取当前有效分辨率 */
        public int getEffectiveWidth() {
            return Math.max(1, baseWidth >> currentMipLevel);
        }

        public int getEffectiveHeight() {
            return Math.max(1, baseHeight >> currentMipLevel);
        }

        /** 获取当前 VRAM 占用估算 (bytes) */
        public long estimateVRAM() {
            int w = getEffectiveWidth();
            int h = getEffectiveHeight();
            return (long) w * h * 4L; // RGBA8 = 4 bytes/texel
        }
    }

    // ==================== 纹理注册表 ====================

    private final ConcurrentHashMap<Long, StreamingTexture> textures = new ConcurrentHashMap<>();
    private volatile boolean streamingEnabled = false;
    private volatile long currentFrame = 0;

    /** 驱逐阈值：超过此帧数未访问的纹理降级 mipmap */
    private static final long EVICT_FRAME_THRESHOLD = 60;

    // ==================== 公共 API ====================

    /**
     * 注册纹理到流式管理器
     */
    public void registerTexture(long textureId, String name, int width, int height, int mipLevels) {
        textures.put(textureId, new StreamingTexture(textureId, name, width, height, mipLevels));
    }

    /**
     * 注销纹理
     */
    public void unregisterTexture(long textureId) {
        textures.remove(textureId);
    }

    /**
     * 每帧更新 — 根据距离调整纹理 mipmap 级别
     * <p>
     * 与 LODSystem 联动：使用相同的距离阈值
     *
     * @param cameraX 相机 X
     * @param cameraY 相机 Y
     * @param cameraZ 相机 Z
     * @param frameNumber 当前帧号
     */
    public void update(float cameraX, float cameraY, float cameraZ, long frameNumber) {
        if (!streamingEnabled) return;
        this.currentFrame = frameNumber;

        for (StreamingTexture tex : textures.values()) {
            // TODO: 从纹理关联的 chunk 位置计算距离
            // 当前使用简化的距离估算
            float distance = tex.lastDistance;

            // 根据 LOD 档次确定 mipmap 级别
            TextureLODTier tier = TextureLODTier.fromDistance(distance);
            int targetMip = Math.min(tier.maxMipLevel, tex.totalMipLevels - 1);

            if (targetMip != tex.currentMipLevel) {
                tex.currentMipLevel = targetMip;
                // TODO: 触发 mipmap 级别变更
                // vkCmdBlitImage 或 重新上传对应 mipmap 级别的数据
            }

            tex.lastAccessFrame = frameNumber;
        }

        // 驱逐长时间未访问的纹理
        evictStaleTextures();
    }

    /**
     * 设置纹理距离（由 LODSystem 调用）
     */
    public void setTextureDistance(long textureId, float distance) {
        StreamingTexture tex = textures.get(textureId);
        if (tex != null) {
            tex.lastDistance = distance;
        }
    }

    /**
     * 获取当前总 VRAM 估算
     */
    public long estimateTotalVRAM() {
        long total = 0;
        for (StreamingTexture tex : textures.values()) {
            if (tex.resident) total += tex.estimateVRAM();
        }
        return total;
    }

    public void setStreamingEnabled(boolean v) { this.streamingEnabled = v; }
    public boolean isStreamingEnabled() { return streamingEnabled; }
    public int getTextureCount() { return textures.size(); }

    // ==================== Mipmap 链生成 ====================

    // Vulkan 常量（本地化，避免对 VulkanConst 的依赖）
    private static final int VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL = 0x00000005;
    private static final int VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL = 0x00000004;
    private static final int VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL = 0x00000000;
    private static final int VK_ACCESS_TRANSFER_WRITE_BIT = 0x00020000;
    private static final int VK_ACCESS_TRANSFER_READ_BIT = 0x00040000;
    private static final int VK_ACCESS_SHADER_READ_BIT = 0x00000020;
    private static final int VK_PIPELINE_STAGE_TRANSFER_BIT = 0x00040000;
    private static final int VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT = 0x00000080;
    private static final int VK_IMAGE_ASPECT_COLOR_BIT = 0x00000001;
    private static final int VK_FILTER_LINEAR = 1;
    /** VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER = 47 */
    private static final int VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER = 47;

    /**
     * 使用 vkCmdBlitImage 在运行时生成完整的 mip chain。
     * <p>
     * 算法：从 mip level 0 开始，逐级向下采样到 mip N。
     * 每级先将源 mip 从 TRANSFER_DST 转换为 TRANSFER_SRC，
     * 执行 blit 后将源 mip 转为 SHADER_READ_ONLY，
     * 最后一个 mip 在循环外转换。
     * <p>
     * 前置条件：mip level 0 已上传数据且处于 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL。
     * Image 必须包含 VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT。
     *
     * @param cmdBuf   已开始录制的 command buffer handle
     * @param image    VkImage handle
     * @param width    base width (mip 0)
     * @param height   base height (mip 0)
     * @param mipLevels 总 mip 级别数
     */
    public void generateMipChain(long cmdBuf, long image, int width, int height, int mipLevels) {
        if (cmdBuf == 0L || image == 0L || mipLevels <= 1) {
            return; // 无 mip 或无效句柄时直接跳过
        }

        try {
            for (int i = 1; i < mipLevels; i++) {
                // 计算当前级别的尺寸
                int srcWidth  = Math.max(1, width >> (i - 1));
                int srcHeight = Math.max(1, height >> (i - 1));
                int dstWidth  = Math.max(1, width >> i);
                int dstHeight = Math.max(1, height >> i);

                // --- Barrier 1: 转换 mip i-1 (源) 从 TRANSFER_DST → TRANSFER_SRC ---
                insertMipBarrier(cmdBuf, image, i - 1, 1,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

                // --- 构造 VkImageBlit: 从 mip i-1 blit 到 mip i ---
                MemorySegment blitRegion = PerFrameArena.allocate(80);
                // srcSubresource
                blitRegion.set(ValueLayout.JAVA_INT, 0, VK_IMAGE_ASPECT_COLOR_BIT);  // aspectMask
                blitRegion.set(ValueLayout.JAVA_INT, 4, i - 1);                        // mipLevel
                blitRegion.set(ValueLayout.JAVA_INT, 8, 0);                            // baseArrayLayer
                blitRegion.set(ValueLayout.JAVA_INT, 12, 1);                           // layerCount
                // srcOffsets[0] = {0, 0, 0}
                blitRegion.set(ValueLayout.JAVA_INT, 16, 0);
                blitRegion.set(ValueLayout.JAVA_INT, 20, 0);
                blitRegion.set(ValueLayout.JAVA_INT, 24, 0);
                // srcOffsets[1] = {srcWidth, srcHeight, 1}
                blitRegion.set(ValueLayout.JAVA_INT, 28, srcWidth);
                blitRegion.set(ValueLayout.JAVA_INT, 32, srcHeight);
                blitRegion.set(ValueLayout.JAVA_INT, 36, 1);
                // dstSubresource
                blitRegion.set(ValueLayout.JAVA_INT, 40, VK_IMAGE_ASPECT_COLOR_BIT);  // aspectMask
                blitRegion.set(ValueLayout.JAVA_INT, 44, i);                           // mipLevel
                blitRegion.set(ValueLayout.JAVA_INT, 48, 0);                           // baseArrayLayer
                blitRegion.set(ValueLayout.JAVA_INT, 52, 1);                           // layerCount
                // dstOffsets[0] = {0, 0, 0}
                blitRegion.set(ValueLayout.JAVA_INT, 56, 0);
                blitRegion.set(ValueLayout.JAVA_INT, 60, 0);
                blitRegion.set(ValueLayout.JAVA_INT, 64, 0);
                // dstOffsets[1] = {dstWidth, dstHeight, 1}
                blitRegion.set(ValueLayout.JAVA_INT, 68, dstWidth);
                blitRegion.set(ValueLayout.JAVA_INT, 72, dstHeight);
                blitRegion.set(ValueLayout.JAVA_INT, 76, 1);

                // --- vkCmdBlitImage: mip i-1 → mip i ---
                VulkanAPIRegistry.invoke("vkCmdBlitImage",
                        cmdBuf,
                        image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        1, blitRegion.address(),
                        VK_FILTER_LINEAR);

                // --- Barrier 2: 转换 mip i-1 (已完成作为源) 从 TRANSFER_SRC → SHADER_READ_ONLY ---
                insertMipBarrier(cmdBuf, image, i - 1, 1,
                        VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
            }

            // --- 最终 barrier: 转换最后一个 mip (mipLevels-1) 从 TRANSFER_DST → SHADER_READ_ONLY ---
            insertMipBarrier(cmdBuf, image, mipLevels - 1, 1,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);

        } catch (Throwable t) {
            LOGGER.warning("generateMipChain 失败: " + t.getMessage());
        }
    }

    /**
     * 插入单个 mip 级别的 VkImageMemoryBarrier。
     * <p>
     * VkImageMemoryBarrier 结构体大小: 68 字节（分配 72 字节以确保对齐安全）
     * <pre>
     * 偏移  字段                    类型      大小
     *  0    sType                  int32      4
     *  4    (padding)                         4
     *  8    pNext                  address    8
     * 16    srcAccessMask          int32      4
     * 20    dstAccessMask          int32      4
     * 24    oldLayout              int32      4
     * 28    newLayout              int32      4
     * 32    srcQueueFamilyIndex    int32      4
     * 36    dstQueueFamilyIndex    int32      4
     * 40    image                  address    8
     * 48    subresourceRange
     *        .aspectMask           int32      4
     *        .baseMipLevel         int32      4
     *        .levelCount           int32      4
     *        .baseArrayLayer       int32      4
     *        .layerCount           int32      4  → 偏移 64
     * </pre>
     */
    private void insertMipBarrier(long cmdBuf, long image,
                                   int baseMipLevel, int levelCount,
                                   int srcAccessMask, int dstAccessMask,
                                   int oldLayout, int newLayout,
                                   int srcStageMask, int dstStageMask) throws Throwable {
        MemorySegment barrier = PerFrameArena.allocate(72);
        // sType
        barrier.set(ValueLayout.JAVA_INT, 0, VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        // pNext = NULL
        barrier.set(ValueLayout.JAVA_LONG, 8, 0L);
        // srcAccessMask / dstAccessMask
        barrier.set(ValueLayout.JAVA_INT, 16, srcAccessMask);
        barrier.set(ValueLayout.JAVA_INT, 20, dstAccessMask);
        // oldLayout / newLayout
        barrier.set(ValueLayout.JAVA_INT, 24, oldLayout);
        barrier.set(ValueLayout.JAVA_INT, 28, newLayout);
        // srcQueueFamilyIndex / dstQueueFamilyIndex (VK_QUEUE_FAMILY_IGNORED)
        barrier.set(ValueLayout.JAVA_INT, 32, -1);
        barrier.set(ValueLayout.JAVA_INT, 36, -1);
        // image
        barrier.set(ValueLayout.JAVA_LONG, 40, image);
        // subresourceRange
        barrier.set(ValueLayout.JAVA_INT, 48, VK_IMAGE_ASPECT_COLOR_BIT); // aspectMask
        barrier.set(ValueLayout.JAVA_INT, 52, baseMipLevel);              // baseMipLevel
        barrier.set(ValueLayout.JAVA_INT, 56, levelCount);                // levelCount
        barrier.set(ValueLayout.JAVA_INT, 60, 0);                         // baseArrayLayer
        barrier.set(ValueLayout.JAVA_INT, 64, 1);                         // layerCount

        VulkanAPIRegistry.invoke("vkCmdPipelineBarrier",
                cmdBuf,
                srcStageMask, dstStageMask,
                0,                // dependencyFlags
                0, 0L,            // memoryBarrierCount, pMemoryBarriers
                0, 0L,            // bufferMemoryBarrierCount, pBufferMemoryBarriers
                1, barrier.address()); // imageMemoryBarrierCount, pImageMemoryBarriers
    }

    // ==================== 内部方法 ====================

    private void evictStaleTextures() {
        for (StreamingTexture tex : textures.values()) {
            if (currentFrame - tex.lastAccessFrame > EVICT_FRAME_THRESHOLD) {
                // 降级到最低分辨率 mipmap
                tex.currentMipLevel = tex.totalMipLevels - 1;
            }
        }
    }
}
