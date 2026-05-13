// Renderium - DLSS Frame Generation Adapter
// NVIDIA DLSS 4.5 6X Multi Frame Generation 适配器

package com.ranecc.renderium.tech.framegen;

import com.ranecc.renderium.None;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

/**
 * DLSS Frame Generation 适配器
 * <p>
 * 支持 DLSS 4.5 的 Multi Frame Generation：
 * <ul>
 *   <li>2X: RTX 40 系列+</li>
 *   <li>4X: RTX 40 系列+</li>
 *   <li>6X: RTX 50 系列（Blackwell 架构）</li>
 *   <li>Dynamic: RTX 50 系列</li>
 * </ul>
 * <p>
 * DLSS FG 需要：
 * <ul>
 *   <li>无 HUD 的颜色纹理</li>
 *   <li>深度纹理</li>
 *   <li>运动矢量纹理</li>
 *   <li>当前帧和前一帧的相机矩阵</li>
 * </ul>
 *
 * @see FrameGenerator
 * @see DLSSManager
 */
public final class DLSSFGAdapter implements FrameGenerator {

    private static final Logger LOGGER = Logger.getLogger(DLSSFGAdapter.class.getName());

    private final SLContext slContext;
    private final VulkanStreamlineBridge bridge;
    private final FrameEvaluator frameEvaluator;

    private boolean supported = false;
    private boolean enabled = false;
    private FrameGenMode mode = FrameGenMode.OFF;

    /**
     * DLSS-G 帧生成数量映射
     * 对应 sl_dlss_g.h 中的 numFramesToGenerate
     */
    private int numFramesToGenerate = 1;

    public DLSSFGAdapter(SLContext slContext, VulkanStreamlineBridge bridge,
                          FrameEvaluator frameEvaluator) {
        this.slContext = slContext;
        this.bridge = bridge;
        this.frameEvaluator = frameEvaluator;
        this.supported = checkSupport();
    }

    @Override
    public String getName() {
        return "DLSS Frame Generation";
    }

    @Override
    public boolean isSupported() {
        return supported;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public FrameGenMode getMode() {
        return mode;
    }

    @Override
    public void enable(FrameGenMode mode) {
        if (!supported) {
            LOGGER.warning("DLSS FG not supported");
            return;
        }

        this.mode = mode;
        this.enabled = true;

        // 设置帧生成数量
        numFramesToGenerate = switch (mode) {
            case OFF -> 0;
            case FIXED_2X -> 1;
            case FIXED_4X -> 3;
            case FIXED_6X -> 5;
            case DYNAMIC -> 0; // 动态模式由 Streamline 自动管理
            case DLSS -> 1;    // DLSS 模式默认 2X
            case FSR -> 1;     // FSR 模式默认 2X
        };

        // 设置 DLSS-G 选项
        setDLSSGOptions();

        LOGGER.info("DLSS FG enabled: mode=" + mode + ", numFrames=" + numFramesToGenerate);
    }

    @Override
    public void disable() {
        this.enabled = false;
        this.mode = FrameGenMode.OFF;
        this.numFramesToGenerate = 0;
        LOGGER.info("DLSS FG disabled");
    }

    @Override
    public void generateFrame(FrameGenData currentFrame, FrameGenData previousFrame,
                               long cmdBuffer) {
        if (!enabled || !supported) return;

        // DLSS FG 通过 Streamline 统一帧评估流程
        if (!frameEvaluator.beginFrame(currentFrame.cameraData().deltaTime(), 0)) {
            return;
        }

        // 标记 FG 所需资源（使用 VulkanStreamlineBridge 真实标记）
        tagResourcesForFrameGeneration(currentFrame);

        // 评估 DLSS-G
        frameEvaluator.evaluateFeature(SLFFMBindings.FEATURE_DLSS_G, cmdBuffer);

        frameEvaluator.endFrame();
    }

    /**
     * 标记帧生成所需的资源（真实实现）
     * <p>
     * 通过 VulkanStreamlineBridge 将纹理句柄标记给 Streamline SDK，
     * 使 DLSS-G 知道输入数据的位置。
     *
     * @param frame 当前帧数据
     */
    private void tagResourcesForFrameGeneration(FrameGenData frame) {
        if (bridge != null && bridge.isAvailable()) {
            VulkanStreamlineBridge.ResourceTagData[] resources = {
                new VulkanStreamlineBridge.ResourceTagData(
                    SLFFMBindings.BUFFER_TYPE_HUDLESS_COLOR, frame.colorImageView(),
                    frame.textureWidth(), frame.textureHeight()),
                new VulkanStreamlineBridge.ResourceTagData(
                    SLFFMBindings.BUFFER_TYPE_DEPTH, frame.depthImageView(),
                    frame.textureWidth(), frame.textureHeight()),
                new VulkanStreamlineBridge.ResourceTagData(
                    SLFFMBindings.BUFFER_TYPE_MOTION_VECTORS, frame.motionVectorImageView(),
                    frame.textureWidth(), frame.textureHeight())
            };

            if (!bridge.tagResources(resources)) {
                LOGGER.warning("VulkanStreamlineBridge.tagResources failed, falling back to FrameEvaluator");
                frameEvaluator.tagResources(resources);
            }
        } else {
            LOGGER.fine("VulkanStreamlineBridge not available, using FrameEvaluator stub");
            frameEvaluator.tagResources(new Object[0]);
        }
    }

    @Override
    public void shutdown() {
        disable();
    }

    /**
     * 设置 DLSS-G 选项
     */
    private void setDLSSGOptions() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment viewport = arena.allocate(8);
            viewport.set(ValueLayout.JAVA_INT, 0, 0x19); // sType
            viewport.set(ValueLayout.JAVA_INT, 4, 0);    // index

            MemorySegment options = arena.allocate(16);
            options.set(ValueLayout.JAVA_INT, 0, 0x1C); // sType = SL_STRUCT_TYPE_DLSS_G_OPTIONS
            options.set(ValueLayout.JAVA_INT, 8, mode == FrameGenMode.OFF
                ? SLFFMBindings.DLSSG_MODE_OFF
                : SLFFMBindings.DLSSG_MODE_ON);
            options.set(ValueLayout.JAVA_INT, 12, numFramesToGenerate);

            int result = SLFFMBindings.slDLSSGSetOptions(viewport, options);
            if (!SLFFMBindings.isOk(result)) {
                LOGGER.warning("slDLSSGSetOptions failed: " + SLFFMBindings.getResultDescription(result));
            }
        } catch (Exception e) {
            LOGGER.warning("setDLSSGOptions error: " + e.getMessage());
        }
    }

    /**
     * 检查 DLSS FG 支持
     */
    private boolean checkSupport() {
        if (slContext == null || !slContext.isInitialized()) return false;

        boolean fgSupported = slContext.isFeatureSupported(SLContext.Feature.DLSS_G);
        if (!fgSupported) {
            LOGGER.info("DLSS-G not supported");
            return false;
        }

        // 解析 DLSS-G 函数
        boolean resolved = slContext.resolveDLSSGFunctions();
        if (!resolved) {
            LOGGER.warning("DLSS-G functions not resolved");
            return false;
        }

        LOGGER.info("DLSS FG supported");
        return true;
    }
}
