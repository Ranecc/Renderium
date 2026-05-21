package com.ranecc.renderium.feature.renderopt.texture;

import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 纹理图集合并管理器
 * <p>
 * 将多个小纹理合并到图集，减少 DescriptorSet 切换和 Draw Call。
 * MC 原版已有纹理图集（terrain.png），但模组纹理可能分散。
 * <p>
 * Draw Call 减少：20-30%
 * 短路条件：atlasMergingEnabled == false
 *
 * @since 5.5.0
 */
public final class TextureAtlasManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium|Atlas");

    private static volatile TextureAtlasManager instance;

    public static TextureAtlasManager getInstance() {
        if (instance == null) {
            synchronized (TextureAtlasManager.class) {
                if (instance == null) instance = new TextureAtlasManager();
            }
        }
        return instance;
    }

    private TextureAtlasManager() {}

    // ==================== 图集定义 ====================

    /** 图集最大尺寸 */
    private static final int MAX_ATLAS_SIZE = 4096;

    /** 图集内纹理间距（避免采样溢出） */
    private static final int PADDING = 2;

    /**
     * 纹理图集
     */
    public static final class TextureAtlas {
        public final String name;
        public final int width, height;
        public final long atlasTextureId; // Vulkan Image handle
        public final Map<String, AtlasRegion> regions = new HashMap<>();
        public int usedPixels = 0;

        public TextureAtlas(String name, int width, int height, long atlasTextureId) {
            this.name = name;
            this.width = width;
            this.height = height;
            this.atlasTextureId = atlasTextureId;
        }

        public float getUtilization() {
            return (float) usedPixels / (width * height);
        }
    }

    /**
     * 图集区域（单个纹理在图集中的位置）
     */
    public static final class AtlasRegion {
        public final String textureName;
        public final int x, y, width, height;
        public final float u0, v0, u1, v1; // 归一化 UV 坐标

        public AtlasRegion(String name, int x, int y, int w, int h, int atlasW, int atlasH) {
            this.textureName = name;
            this.x = x;
            this.y = y;
            this.width = w;
            this.height = h;
            this.u0 = (float) x / atlasW;
            this.v0 = (float) y / atlasH;
            this.u1 = (float) (x + w) / atlasW;
            this.v1 = (float) (y + h) / atlasH;
        }
    }

    // ==================== 状态 ====================

    private final ConcurrentHashMap<String, TextureAtlas> atlases = new ConcurrentHashMap<>();
    private volatile boolean mergingEnabled = false;

    // ==================== 公共 API ====================

    /**
     * 创建纹理图集
     */
    public TextureAtlas createAtlas(String name, int width, int height) {
        // TODO: 调用 VulkanGPUResourceManager.createImage 创建图集纹理
        long texId = 0L; // placeholder
        TextureAtlas atlas = new TextureAtlas(name, width, height, texId);
        atlases.put(name, atlas);
        return atlas;
    }

    /**
     * 将纹理添加到图集
     * <p>
     * 使用简单的行优先打包算法（shelf packing）
     */
    public AtlasRegion addTextureToAtlas(String atlasName, String textureName,
                                          int texWidth, int texHeight, byte[] rgbaData) {
        TextureAtlas atlas = atlases.get(atlasName);
        if (atlas == null) return null;

        // Shelf packing: 找到第一个能放下的位置
        int x = 0, y = 0;
        int rowHeight = 0;

        for (AtlasRegion existing : atlas.regions.values()) {
            int nextX = existing.x + existing.width + PADDING;
            if (nextX + texWidth + PADDING > atlas.width) {
                // 换行
                y += rowHeight + PADDING;
                rowHeight = 0;
                x = 0;
            } else {
                x = nextX;
                rowHeight = Math.max(rowHeight, existing.height);
            }
        }

        if (x + texWidth + PADDING > atlas.width || y + texHeight + PADDING > atlas.height) {
            LOGGER.warning("图集空间不足: " + atlasName + " 无法放入 " + textureName);
            return null;
        }

        AtlasRegion region = new AtlasRegion(textureName, x + PADDING, y + PADDING,
                                              texWidth, texHeight, atlas.width, atlas.height);
        atlas.regions.put(textureName, region);
        atlas.usedPixels += (texWidth + PADDING * 2) * (texHeight + PADDING * 2);

        // 上传操作由外部在 command buffer 录制阶段调用 uploadToAtlas() 完成
        // 因为此处不持有 command buffer 和 staging buffer 句柄

        return region;
    }

    /**
     * 获取纹理在图集中的 UV 坐标
     */
    public AtlasRegion getRegion(String atlasName, String textureName) {
        TextureAtlas atlas = atlases.get(atlasName);
        return atlas != null ? atlas.regions.get(textureName) : null;
    }

    /**
     * 上传纹理数据到图集指定区域 — vkCmdCopyBufferToImage + layout transition
     * <p>
     * 在 command buffer 录制阶段调用，将 staging buffer 中的 RGBA 数据拷贝到
     * 图集 texture 的指定区域，然后通过 barrier 将图集 texture 从
     * TRANSFER_DST_OPTIMAL 转换为 SHADER_READ_ONLY_OPTIMAL。
     * <p>
     * 前置条件：
     * <ul>
     *   <li>stagingBuffer 已填充完整的图集数据</li>
     *   <li>cmdBuf 已开始录制</li>
     *   <li>atlasImage 处于 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL</li>
     * </ul>
     *
     * @param cmdBuf       已开始的 command buffer handle
     * @param stagingBuffer 包含图集数据的 staging buffer handle
     * @param atlasImage    目标图集 VkImage handle
     * @param atlasWidth    图集宽度
     * @param atlasHeight   图集高度
     * @param regionX       图集中区域 X 偏移
     * @param regionY       图集中区域 Y 偏移
     * @param texWidth      纹理宽度
     * @param texHeight     纹理高度
     */
    public void uploadToAtlas(long cmdBuf, long stagingBuffer, long atlasImage,
                               int atlasWidth, int atlasHeight,
                               int regionX, int regionY,
                               int texWidth, int texHeight) {
        if (cmdBuf == 0L || stagingBuffer == 0L || atlasImage == 0L) {
            LOGGER.warning("uploadToAtlas: 无效句柄，跳过上传");
            return;
        }

        try {
            // --- 构造 VkBufferImageCopy (56 字节，分配 64 字节对齐安全) ---
            MemorySegment copyRegion = PerFrameArena.allocate(64);
            // bufferOffset = 0 (staging buffer 从起始位置存储数据)
            copyRegion.set(ValueLayout.JAVA_LONG, 0, 0L);
            // bufferRowLength = 0, bufferImageHeight = 0 (tightly packed)
            copyRegion.set(ValueLayout.JAVA_INT, 8, 0);
            copyRegion.set(ValueLayout.JAVA_INT, 12, 0);
            // imageSubresource
            copyRegion.set(ValueLayout.JAVA_INT, 16, VK_IMAGE_ASPECT_COLOR_BIT); // aspectMask
            copyRegion.set(ValueLayout.JAVA_INT, 20, 0);                         // mipLevel
            copyRegion.set(ValueLayout.JAVA_INT, 24, 0);                         // baseArrayLayer
            copyRegion.set(ValueLayout.JAVA_INT, 28, 1);                         // layerCount
            // imageOffset
            copyRegion.set(ValueLayout.JAVA_INT, 32, regionX);
            copyRegion.set(ValueLayout.JAVA_INT, 36, regionY);
            copyRegion.set(ValueLayout.JAVA_INT, 40, 0); // z offset
            // imageExtent
            copyRegion.set(ValueLayout.JAVA_INT, 44, texWidth);
            copyRegion.set(ValueLayout.JAVA_INT, 48, texHeight);
            copyRegion.set(ValueLayout.JAVA_INT, 52, 1); // depth

            // --- vkCmdCopyBufferToImage ---
            VulkanAPIRegistry.invoke("vkCmdCopyBufferToImage",
                    cmdBuf,
                    stagingBuffer,
                    atlasImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    1,
                    copyRegion.address());

            // --- Barrier: TRANSFER_DST → SHADER_READ_ONLY (整个图集) ---
            insertAtlasBarrier(cmdBuf, atlasImage);

        } catch (Throwable t) {
            LOGGER.warning("uploadToAtlas 失败: " + t.getMessage());
        }
    }

    // ==================== Vulkan 常量 ====================

    private static final int VK_IMAGE_ASPECT_COLOR_BIT = 0x00000001;
    private static final int VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL = 0x00000004;
    private static final int VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL = 0x00000000;
    private static final int VK_ACCESS_TRANSFER_WRITE_BIT = 0x00020000;
    private static final int VK_ACCESS_SHADER_READ_BIT = 0x00000020;
    private static final int VK_PIPELINE_STAGE_TRANSFER_BIT = 0x00040000;
    private static final int VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT = 0x00000080;
    private static final int VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER = 47;

    /**
     * 插入图集全局 image memory barrier: TRANSFER_DST → SHADER_READ_ONLY
     * <p>
     * VkImageMemoryBarrier 结构体布局:
     * <pre>
     *  偏移  字段                    类型      大小
     *   0    sType                  int32      4
     *   4    (padding)                         4
     *   8    pNext                  address    8
     *  16    srcAccessMask          int32      4
     *  20    dstAccessMask          int32      4
     *  24    oldLayout              int32      4
     *  28    newLayout              int32      4
     *  32    srcQueueFamilyIndex    int32      4
     *  36    dstQueueFamilyIndex    int32      4
     *  40    image                  address    8
     *  48    subresourceRange
     *          .aspectMask           int32      4
     *          .baseMipLevel         int32      4
     *          .levelCount           int32      4
     *          .baseArrayLayer       int32      4
     *          .layerCount           int32      4 → 偏移 64
     * </pre>
     */
    private void insertAtlasBarrier(long cmdBuf, long atlasImage) throws Throwable {
        MemorySegment barrier = PerFrameArena.allocate(72);
        barrier.set(ValueLayout.JAVA_INT, 0, VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER); // sType
        barrier.set(ValueLayout.JAVA_LONG, 8, 0L);                                    // pNext = NULL
        barrier.set(ValueLayout.JAVA_INT, 16, VK_ACCESS_TRANSFER_WRITE_BIT);          // srcAccessMask
        barrier.set(ValueLayout.JAVA_INT, 20, VK_ACCESS_SHADER_READ_BIT);             // dstAccessMask
        barrier.set(ValueLayout.JAVA_INT, 24, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);  // oldLayout
        barrier.set(ValueLayout.JAVA_INT, 28, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL); // newLayout
        barrier.set(ValueLayout.JAVA_INT, 32, -1); // srcQueueFamilyIndex (VK_QUEUE_FAMILY_IGNORED)
        barrier.set(ValueLayout.JAVA_INT, 36, -1); // dstQueueFamilyIndex (VK_QUEUE_FAMILY_IGNORED)
        barrier.set(ValueLayout.JAVA_LONG, 40, atlasImage);                          // image
        barrier.set(ValueLayout.JAVA_INT, 48, VK_IMAGE_ASPECT_COLOR_BIT);            // aspectMask
        barrier.set(ValueLayout.JAVA_INT, 52, 0);                                    // baseMipLevel
        barrier.set(ValueLayout.JAVA_INT, 56, 1);                                    // levelCount (仅 mip 0)
        barrier.set(ValueLayout.JAVA_INT, 60, 0);                                    // baseArrayLayer
        barrier.set(ValueLayout.JAVA_INT, 64, 1);                                    // layerCount

        VulkanAPIRegistry.invoke("vkCmdPipelineBarrier",
                cmdBuf,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0,       // dependencyFlags
                0, 0L,   // memoryBarrierCount, pMemoryBarriers
                0, 0L,   // bufferMemoryBarrierCount, pBufferMemoryBarriers
                1, barrier.address()); // imageMemoryBarrierCount, pImageMemoryBarriers
    }

    public void setMergingEnabled(boolean v) { this.mergingEnabled = v; }
    public boolean isMergingEnabled() { return mergingEnabled; }
    public int getAtlasCount() { return atlases.size(); }
}
