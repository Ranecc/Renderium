package com.ranecc.renderium.feature.renderopt.texture;

import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

/**
 * 各向异性过滤优化管理器
 * <p>
 * 根据距离和 LOD 档次动态调整各向异性过滤级别：
 * - 近处：16x 各向异性（最高质量）
 * - 中距离：8x 各向异性
 * - 远距离：4x 各向异性
 * - 极远：双线性（节省 GPU 带宽）
 * <p>
 * GPU 开销：0-5%（硬件实现，几乎零额外开销）
 * 短路条件：anisotropicLevel == 1
 *
 * @since 5.5.0
 */
public final class AnisotropicFilterManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium|Aniso");

    private static volatile AnisotropicFilterManager instance;

    public static AnisotropicFilterManager getInstance() {
        if (instance == null) {
            synchronized (AnisotropicFilterManager.class) {
                if (instance == null) instance = new AnisotropicFilterManager();
            }
        }
        return instance;
    }

    private AnisotropicFilterManager() {}

    // ==================== 各向异性级别 ====================

    /** 最大各向异性级别（由 GPU 查询决定） */
    private volatile int maxAnisotropy = 16;

    /** 当前全局各向异性级别 */
    private volatile int globalLevel = 16;

    /** 是否启用动态调整 */
    private volatile boolean dynamicAdjustment = false;

    // ==================== LOD 联动各向异性级别 ====================

    /**
     * 根据 LOD 档次确定各向异性级别
     * <p>
     * 近处需要高质量过滤（斜角观察时纹理模糊严重），
     * 远处纹理已经很小，各向异性收益递减。
     */
    public int getAnisoLevelForLOD(TextureStreamingManager.TextureLODTier tier) {
        if (globalLevel <= 1) return 1;

        return switch (tier) {
            case TIER_0, TIER_1 -> Math.min(16, maxAnisotropy);  // 近处：最高质量
            case TIER_2, TIER_3 -> Math.min(8, maxAnisotropy);   // 中距离
            case TIER_4, TIER_5 -> Math.min(4, maxAnisotropy);   // 远距离
            default -> 1;                                          // 极远：双线性
        };
    }

    /**
     * 创建 Vulkan Sampler 的各向异性参数
     * <p>
     * TODO: 集成到 VulkanSamplerFactory
     * <p>
     * VkSamplerCreateInfo:
     * - anisotropyEnable = level > 1
     * - maxAnisotropy = level
     */
    public long createAnisoSampler(int level) {
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return 0L;
        try {
            MemorySegment ci = PerFrameArena.allocate(96L);
            ci.set(ValueLayout.JAVA_LONG, 0, 21L);  // sType = SAMPLER_CREATE_INFO
            ci.set(ValueLayout.JAVA_LONG, 8, 0L);   // pNext
            ci.set(ValueLayout.JAVA_INT, 16, 0);    // flags
            ci.set(ValueLayout.JAVA_INT, 20, 1);    // magFilter = VK_FILTER_LINEAR (1)
            ci.set(ValueLayout.JAVA_INT, 24, 1);    // minFilter = VK_FILTER_LINEAR (1)
            ci.set(ValueLayout.JAVA_INT, 28, 1);    // mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR (1)
            ci.set(ValueLayout.JAVA_INT, 32, 0);    // addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT (0)
            ci.set(ValueLayout.JAVA_INT, 36, 0);    // addressModeV
            ci.set(ValueLayout.JAVA_INT, 40, 0);    // addressModeW
            ci.set(ValueLayout.JAVA_FLOAT, 44, 0.0f); // mipLodBias
            ci.set(ValueLayout.JAVA_INT, 48, level > 1 ? 1 : 0);  // anisotropyEnable
            ci.set(ValueLayout.JAVA_FLOAT, 52, (float) Math.max(1, level)); // maxAnisotropy
            ci.set(ValueLayout.JAVA_INT, 56, 0);    // compareEnable
            ci.set(ValueLayout.JAVA_INT, 60, 0);    // compareOp
            ci.set(ValueLayout.JAVA_FLOAT, 64, 0.0f); // minLod
            ci.set(ValueLayout.JAVA_FLOAT, 68, 16.0f); // maxLod
            ci.set(ValueLayout.JAVA_INT, 72, 0);    // borderColor
            ci.set(ValueLayout.JAVA_INT, 76, 0);    // unnormalizedCoordinates
            MemorySegment out = PerFrameArena.allocateLongs(1);
            int rc = (int) VulkanAPIRegistry.invoke("vkCreateSampler", device, ci.address(), 0L, out.address());
            return rc == 0 ? out.get(ValueLayout.JAVA_LONG, 0) : 0L;
        } catch (Throwable t) {
            LOGGER.fine("vkCreateSampler 失败: " + t.getMessage());
            return 0L;
        }
    }

    // ==================== 公共 API ====================

    public void setMaxAnisotropy(int v) { this.maxAnisotropy = Math.max(1, Math.min(16, v)); }
    public int getMaxAnisotropy() { return maxAnisotropy; }

    public void setGlobalLevel(int v) { this.globalLevel = Math.max(1, Math.min(maxAnisotropy, v)); }
    public int getGlobalLevel() { return globalLevel; }

    public void setDynamicAdjustment(boolean v) { this.dynamicAdjustment = v; }
    public boolean isDynamicAdjustment() { return dynamicAdjustment; }
}
