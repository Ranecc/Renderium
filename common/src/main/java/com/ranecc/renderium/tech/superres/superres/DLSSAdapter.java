// Renderium - DLSS Adapter
// NVIDIA DLSS 4.5 超分辨率适配器

package com.ranecc.renderium.tech.superres.superres;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;

import java.util.logging.Logger;

/**
 * DLSS 超分辨率适配器
 * <p>
 * 将 DLSSManager 封装为 SuperResolutionAdapter 接口。
 * 支持 DLSS 4.5 第二代 Transformer 模型。
 * <p>
 * 硬件要求：
 * <ul>
 *   <li>DLSS 4: RTX 20 系列及以上</li>
 *   <li>DLSS 4.5 Transformer: RTX 40 系列及以上</li>
 * </ul>
 *
 * @see SuperResolutionAdapter
 * @see DLSSManager
 */
public final class DLSSAdapter implements SuperResolutionAdapter {

    private static final Logger LOGGER = Logger.getLogger(DLSSAdapter.class.getName());

    private final DLSSManager dlssManager;
    private boolean enabled = false;
    private Quality quality = Quality.BALANCED;

    public DLSSAdapter(DLSSManager dlssManager) {
        this.dlssManager = dlssManager;
    }

    @Override
    public String getName() {
        return "DLSS 4.5";
    }

    @Override
    public Technology getTechnology() {
        return Technology.DLSS;
    }

    @Override
    public boolean isAvailable() {
        return dlssManager.isAvailable();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void enable(Quality quality) {
        if (!isAvailable()) {
            LOGGER.warning("DLSS not available");
            return;
        }

        this.quality = quality;
        dlssManager.enable(DLSSManager.DLSSMode.SUPER_RESOLUTION, toDLSSQuality(quality));
        enabled = true;
    }

    @Override
    public void disable() {
        dlssManager.disable();
        enabled = false;
    }

    @Override
    public Quality getQuality() {
        return quality;
    }

    @Override
    public void setQuality(Quality quality) {
        this.quality = quality;
        if (enabled) {
            dlssManager.enable(DLSSManager.DLSSMode.SUPER_RESOLUTION, toDLSSQuality(quality));
        }
    }

    @Override
    public Resolution getRecommendedResolution(int displayWidth, int displayHeight, Quality quality) {
        DLSSManager.Resolution res = dlssManager.getRecommendedRenderResolution(
            displayWidth, displayHeight, toDLSSQuality(quality));
        return new Resolution(res.width(), res.height());
    }

    @Override
    public void evaluate(FrameData frameData) {
        dlssManager.evaluate(
            frameData.commandBuffer(),
            frameData.colorImageView(),
            frameData.depthImageView(),
            frameData.motionVectorImageView(),
            frameData.outputImageView(),
            frameData.textureWidth(),
            frameData.textureHeight(),
            frameData.cameraData()
        );
    }

    @Override
    public void updateResolution(int renderWidth, int renderHeight,
                                  int displayWidth, int displayHeight) {
        dlssManager.updateResolution(renderWidth, renderHeight, displayWidth, displayHeight);
    }

    @Override
    public void shutdown() {
        dlssManager.shutdown();
    }

    /**
     * 将统一 Quality 转换为 DLSSQuality
     */
    private DLSSManager.DLSSQuality toDLSSQuality(Quality quality) {
        return switch (quality) {
            case ULTRA_QUALITY -> DLSSManager.DLSSQuality.ULTRA_QUALITY;
            case QUALITY -> DLSSManager.DLSSQuality.QUALITY;
            case BALANCED -> DLSSManager.DLSSQuality.BALANCED;
            case PERFORMANCE -> DLSSManager.DLSSQuality.PERFORMANCE;
            case ULTRA_PERFORMANCE -> DLSSManager.DLSSQuality.ULTRA_PERFORMANCE;
            case NATIVE -> DLSSManager.DLSSQuality.DLAA;
        };
    }
}
