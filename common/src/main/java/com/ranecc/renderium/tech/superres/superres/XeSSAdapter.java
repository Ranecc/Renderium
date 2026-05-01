// Renderium - XeSS Adapter
// Intel XeSS 超分辨率适配器（通过 Streamline SDK）

package com.ranecc.renderium.tech.superres.superres;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

/**
 * Intel XeSS 超分辨率适配器
 * <p>
 * 通过 NVIDIA Streamline SDK 的统一接口集成 Intel XeSS。
 * XeSS 使用 DP4a 着色器在非 Intel GPU 上也能运行。
 * <p>
 * 硬件要求：
 * <ul>
 *   <li>Intel Arc GPU（原生 XMX 加速）</li>
 *   <li>其他 GPU（DP4a 兼容模式，质量略低）</li>
 * </ul>
 * <p>
 * 注意：XeSS 通过 Streamline SDK 的 sl.xess.dll 插件提供，
 * 需要 sl.xess.dll 存在于插件路径中。
 *
 * @see SuperResolutionAdapter
 */
public final class XeSSAdapter implements SuperResolutionAdapter {

    private static final Logger LOGGER = Logger.getLogger(XeSSAdapter.class.getName());

    /**
     * XeSS 特性 ID（Streamline 内部）
     * 注意：Streamline 2.10.3 中 XeSS 通过 DirectSR 插件提供
     */
    private static final int FEATURE_XESS = 100;

    private final SLContext slContext;
    private final VulkanStreamlineBridge bridge;
    private final FrameEvaluator frameEvaluator;

    private boolean available = false;
    private boolean enabled = false;
    private Quality quality = Quality.BALANCED;

    private int renderWidth = 0;
    private int renderHeight = 0;
    private int displayWidth = 0;
    private int displayHeight = 0;

    public XeSSAdapter(SLContext slContext, VulkanStreamlineBridge bridge,
                        FrameEvaluator frameEvaluator) {
        this.slContext = slContext;
        this.bridge = bridge;
        this.frameEvaluator = frameEvaluator;
        this.available = checkAvailability();
    }

    @Override
    public String getName() {
        return "Intel XeSS";
    }

    @Override
    public Technology getTechnology() {
        return Technology.XESS;
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
            LOGGER.warning("XeSS not available");
            return;
        }
        this.quality = quality;
        this.enabled = true;
        LOGGER.info("XeSS enabled with quality: " + quality);
    }

    @Override
    public void disable() {
        this.enabled = false;
        LOGGER.info("XeSS disabled");
    }

    @Override
    public Quality getQuality() {
        return quality;
    }

    @Override
    public void setQuality(Quality quality) {
        this.quality = quality;
    }

    @Override
    public Resolution getRecommendedResolution(int displayWidth, int displayHeight, Quality quality) {
        float scale = quality.xessScale;
        int width = ((int) (displayWidth * scale) / 2) * 2;
        int height = ((int) (displayHeight * scale) / 2) * 2;
        return new Resolution(width, height);
    }

    @Override
    public void evaluate(FrameData frameData) {
        if (!enabled || !available) return;

        // XeSS 通过 Streamline 统一帧评估流程
        if (!frameEvaluator.beginFrame(frameData.cameraData().deltaTime(), 0)) {
            return;
        }

        // 标记 XeSS 所需资源（使用 VulkanStreamlineBridge 真实标记）
        tagResourcesForSuperResolution(frameData);
        // XeSS 通过 Streamline 的 DirectSR 特性接口评估（修复：原来错误地使用 FEATURE_DLSS）
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
                    SLFFMBindings.BUFFER_TYPE_DEPTH, frameData.depthImageView(),
                    frameData.textureWidth(), frameData.textureHeight()),
                new VulkanStreamlineBridge.ResourceTagData(
                    SLFFMBindings.BUFFER_TYPE_SCALING_OUTPUT_COLOR, frameData.outputImageView(),
                    frameData.displayWidth(), frameData.displayHeight())
            };

            if (!bridge.tagResources(resources)) {
                LOGGER.warning("VulkanStreamlineBridge.tagResources failed for XeSS");
                frameEvaluator.tagResources(resources);
            }
        } else {
            LOGGER.fine("VulkanStreamlineBridge not available for XeSS, using stub");
            frameEvaluator.tagResources(new Object[0]);
        }
    }

    @Override
    public void shutdown() {
        disable();
    }

    private boolean checkAvailability() {
        if (slContext == null || !slContext.isInitialized()) return false;

        // 检查 Streamline 是否支持 XeSS/DirectSR
        int result = SLFFMBindings.slIsFeatureSupported(
            SLFFMBindings.FEATURE_DIRECT_SR, MemorySegment.NULL);
        if (SLFFMBindings.isOk(result)) {
            LOGGER.info("XeSS available via Streamline DirectSR plugin");
            return true;
        }

        LOGGER.info("XeSS not available");
        return false;
    }
}
