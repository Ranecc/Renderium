// Renderium - FSR Adapter
// AMD FSR 3 超分辨率适配器（通过 Streamline SDK）

package com.ranecc.renderium.tech.superres.superres;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;

import java.lang.foreign.MemorySegment;
import java.util.logging.Logger;

/**
 * AMD FSR 3 超分辨率适配器
 * <p>
 * 通过 NVIDIA Streamline SDK 的统一接口集成 AMD FSR 3。
 * FSR 3 是 AMD 的开源超分辨率技术，支持所有 GPU。
 * <p>
 * 硬件要求：
 * <ul>
 *   <li>AMD RDNA 2+（原生支持）</li>
 *   <li>NVIDIA GPU（兼容模式）</li>
 *   <li>Intel GPU（兼容模式）</li>
 * </ul>
 * <p>
 * FSR 3 特点：
 * <ul>
 *   <li>基于时序的升频</li>
 *   <li>需要运动矢量</li>
 *   <li>可调节锐化强度</li>
 *   <li>支持帧生成（FSR 3 FG）</li>
 * </ul>
 *
 * @see SuperResolutionAdapter
 */
public final class FSRAdapter implements SuperResolutionAdapter {

    private static final Logger LOGGER = Logger.getLogger(FSRAdapter.class.getName());

    private final SLContext slContext;
    private final VulkanStreamlineBridge bridge;
    private final FrameEvaluator frameEvaluator;

    private boolean available = false;
    private boolean enabled = false;
    private Quality quality = Quality.BALANCED;
    private float sharpness = 0.0f; // 0 = auto

    private int renderWidth = 0;
    private int renderHeight = 0;
    private int displayWidth = 0;
    private int displayHeight = 0;

    public FSRAdapter(SLContext slContext, VulkanStreamlineBridge bridge,
                       FrameEvaluator frameEvaluator) {
        this.slContext = slContext;
        this.bridge = bridge;
        this.frameEvaluator = frameEvaluator;
        this.available = checkAvailability();
    }

    @Override
    public String getName() {
        return "AMD FSR 3";
    }

    @Override
    public Technology getTechnology() {
        return Technology.FSR;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void enable(Quality quality) {
        if (!available) {
            LOGGER.warning("FSR not available");
            return;
        }
        this.quality = quality;
        this.enabled = true;
        LOGGER.info("FSR 3 enabled with quality: " + quality);
    }

    @Override
    public void disable() {
        this.enabled = false;
        LOGGER.info("FSR 3 disabled");
    }

    @Override
    public Quality getQuality() {
        return quality;
    }

    @Override
    public void setQuality(Quality quality) {
        this.quality = quality;
    }

    /**
     * 设置锐化强度
     *
     * @param sharpness 锐化值 [0.0, 1.0]，0.0 为自动
     */
    public void setSharpness(float sharpness) {
        this.sharpness = Math.max(0.0f, Math.min(1.0f, sharpness));
    }

    @Override
    public Resolution getRecommendedResolution(int displayWidth, int displayHeight, Quality quality) {
        // FSR 使用不同的缩放方式
        // FSR 的 scale 是 display/render 的比率
        float fsrScale = quality.fsrScale;
        int width = ((int) (displayWidth / fsrScale) / 2) * 2;
        int height = ((int) (displayHeight / fsrScale) / 2) * 2;
        return new Resolution(width, height);
    }

    @Override
    public void evaluate(FrameData frameData) {
        if (!enabled || !available) return;

        // FSR 通过 Streamline 统一帧评估流程
        if (!frameEvaluator.beginFrame(frameData.cameraData().deltaTime(), 0)) {
            return;
        }

        // 标记 FSR 所需资源（使用 VulkanStreamlineBridge 真实标记）
        tagResourcesForSuperResolution(frameData);
        // FSR 通过 Streamline 的 DirectSR 特性接口评估（修复：原来错误地使用 FEATURE_DLSS）
        frameEvaluator.evaluateFeature(SLFFMBindings.FEATURE_DIRECT_SR, frameData.commandBuffer());
        frameEvaluator.endFrame();
    }

    @Override
    public void updateResolution(int renderWidth, int renderHeight,
                                  int displayWidth, int displayHeight) {
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
    }

    /**
     * 标记超分辨率所需的资源（真实实现）
     *
     * @param frameData 帧数据
     */
    private void tagResourcesForSuperResolution(FrameData frameData) {
        if (bridge != null && bridge.isAvailable()) {
            VulkanStreamlineBridge.ResourceTagData[] resources = {
                new VulkanStreamlineBridge.ResourceTagData(
                    SLFFMBindings.BUFFER_TYPE_HUDLESS_COLOR, frameData.colorImageView(),
                    frameData.textureWidth(), frameData.textureHeight()),
                new VulkanStreamlineBridge.ResourceTagData(
                    SLFFMBindings.BUFFER_TYPE_MOTION_VECTORS, frameData.motionVectorImageView(),
                    frameData.textureWidth(), frameData.textureHeight()),
                new VulkanStreamlineBridge.ResourceTagData(
                    SLFFMBindings.BUFFER_TYPE_DEPTH, frameData.depthImageView(),
                    frameData.textureWidth(), frameData.textureHeight()),
                new VulkanStreamlineBridge.ResourceTagData(
                    SLFFMBindings.BUFFER_TYPE_SCALING_OUTPUT_COLOR, frameData.outputImageView(),
                    frameData.displayWidth(), frameData.displayHeight())
            };

            if (!bridge.tagResources(resources)) {
                LOGGER.warning("VulkanStreamlineBridge.tagResources failed for FSR");
                frameEvaluator.tagResources(resources);
            }
        } else {
            LOGGER.fine("VulkanStreamlineBridge not available for FSR, using stub");
            frameEvaluator.tagResources(new Object[0]);
        }
    }

    @Override
    public void shutdown() {
        disable();
    }

    private boolean checkAvailability() {
        if (slContext == null || !slContext.isInitialized()) return false;

        // FSR 通过 Streamline 的 DirectSR 插件提供
        int result = SLFFMBindings.slIsFeatureSupported(
            SLFFMBindings.FEATURE_DIRECT_SR, MemorySegment.NULL);
        if (SLFFMBindings.isOk(result)) {
            LOGGER.info("FSR available via Streamline DirectSR plugin");
            return true;
        }

        // FSR 也可能通过 NIS 插件提供（降级方案）
        result = SLFFMBindings.slIsFeatureSupported(
            SLFFMBindings.FEATURE_NIS, MemorySegment.NULL);
        if (SLFFMBindings.isOk(result)) {
            LOGGER.info("FSR available via NIS fallback");
            return true;
        }

        LOGGER.info("FSR not available");
        return false;
    }
}
