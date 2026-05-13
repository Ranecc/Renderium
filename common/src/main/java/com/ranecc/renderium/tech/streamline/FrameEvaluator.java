// Renderium - Frame Evaluator
// Streamline 帧评估器，处理帧标记、资源标记和特性评估

package com.ranecc.renderium.tech.streamline;

import com.ranecc.renderium.None;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 帧评估器
 * <p>
 * 封装 Streamline 帧评估流程：
 * <ol>
 *   <li>获取帧标记（slGetNewFrameToken）</li>
 *   <li>标记帧资源（slSetTagForFrame）</li>
 *   <li>设置常量（slSetConstants）</li>
 *   <li>评估特性（slEvaluateFeature）</li>
 * </ol>
 * <p>
 * 每帧调用流程：
 * <pre>
 * FrameEvaluator evaluator = new FrameEvaluator(context, bridge);
 * evaluator.beginFrame(deltaTime, frameIndex);
 * evaluator.tagResources(resources);
 * evaluator.setConstants(constants);
 * evaluator.evaluateFeature(Feature.DLSS, cmdBuffer);
 * evaluator.endFrame();
 * </pre>
 *
 * @see SLFFMBindings#slGetNewFrameToken
 * @see SLFFMBindings#slSetTagForFrame
 * @see SLFFMBindings#slSetConstants
 * @see SLFFMBindings#slEvaluateFeature
 */
public final class FrameEvaluator {

    private static final Logger LOGGER = Logger.getLogger(FrameEvaluator.class.getName());

    /**
     * sl::ViewportHandle 结构布局
     * <pre>
     * struct ViewportHandle {
     *     StructureType sType;  // 4 bytes
     *     uint32_t index;       // 4 bytes
     * };
     * </pre>
     */
    private static final long VIEWPORT_HANDLE_SIZE = 8L;

    /**
     * sl::Constants 结构布局
     * <p>
     * 基于 sl_struct.h:
     * <pre>
     * struct Constants {
     *     StructureType sType;                    // 4 bytes
     *     const BaseStructure* next;              // 8 bytes
     *     float jitterOffset[2];                  // 8 bytes (x, y)
     *     float motionVectorScale[2];             // 8 bytes (x, y)
     *     float cameraMotion[3][4];               // 48 bytes (3x4 矩阵)
     *     float cameraViewToClip[3][4];           // 48 bytes
     *     float clipToCameraView[3][4];           // 48 bytes
     *     float prevCameraViewToClip[3][4];       // 48 bytes
     *     float prevClipToCameraView[3][4];       // 48 bytes
     *     float cameraViewToPrevCameraView[3][4]; // 48 bytes
     *     float prevCameraViewToCameraView[3][4]; // 48 bytes
     *     uint32_t reset;                         // 4 bytes
     *     float nearClipValue;                    // 4 bytes
     *     float farClipValue;                     // 4 bytes
     *     float cameraFov;                        // 4 bytes
     *     float cameraAspect;                     // 4 bytes
     *     float cameraNear;                       // 4 bytes
     *     float cameraFar;                        // 4 bytes
     *     CameraMatrixMode cameraMatrixMode;      // 4 bytes
     *     DepthInverted depthInverted;            // 4 bytes
     *     MotionVectorPrecision motionVectorPrecision; // 4 bytes
     *     MotionVectors3D motionVectors3D;        // 4 bytes
     * };
     * </pre>
     */
    private static final long CONSTANTS_SIZE = 416L;

    private final SLContext context;
    private final VulkanStreamlineBridge bridge;

    /** Shared arena for per-frame allocations (lives with this evaluator) */
    private final Arena sharedArena = Arena.ofShared();

    private MemorySegment currentFrameToken;
    private MemorySegment viewportHandle;
    private boolean frameActive = false;

    /**
     * 创建帧评估器（仅指定上下文，Bridge 可为 null）
     *
     * @param context Streamline 上下文
     */
    public FrameEvaluator(SLContext context) {
        this(context, null);
    }

    /**
     * 创建帧评估器
     *
     * @param context Streamline 上下文
     * @param bridge  Vulkan-SL 桥接（可以为 null，表示不使用 Bridge）
     */
    public FrameEvaluator(SLContext context, VulkanStreamlineBridge bridge) {
        this.context = context;
        this.bridge = bridge;
    }

    /**
     * 开始新帧
     * <p>
     * 获取帧标记，准备资源标记。
     *
     * @param deltaTime  帧间隔时间（秒）
     * @param frameIndex 帧索引
     * @return 是否成功
     */
    public boolean beginFrame(float deltaTime, int frameIndex) {
        if (!context.isInitialized()) {
            LOGGER.severe("Streamline not initialized");
            return false;
        }

        try (Arena arena = Arena.ofConfined()) {
            // 分配帧标记输出指针
            MemorySegment tokenPtr = arena.allocate(ValueLayout.ADDRESS);

            // 分配帧索引（可选）
            MemorySegment frameIndexPtr = arena.allocate(ValueLayout.JAVA_INT);
            frameIndexPtr.set(ValueLayout.JAVA_INT, 0, frameIndex);

            // 获取帧标记
            int result = SLFFMBindings.slGetNewFrameToken(tokenPtr, frameIndexPtr);
            if (!SLFFMBindings.isOk(result)) {
                LOGGER.warning("slGetNewFrameToken failed: " + SLFFMBindings.getResultDescription(result));
                return false;
            }

            // 保存帧标记（使用共享Arena避免每帧泄漏）
            currentFrameToken = sharedArena.allocate(ValueLayout.ADDRESS);
            currentFrameToken.copyFrom(tokenPtr);

            // 创建视口句柄（默认视口 0）
            viewportHandle = sharedArena.allocate(VIEWPORT_HANDLE_SIZE);
            viewportHandle.set(ValueLayout.JAVA_INT, 0, 0x19); // SL_STRUCT_TYPE_VIEWPORT_HANDLE
            viewportHandle.set(ValueLayout.JAVA_INT, 4, 0);    // index = 0

            frameActive = true;
            return true;
        } catch (SLException e) {
            LOGGER.severe("beginFrame error: " + e.getMessage());
            return false;
        }
    }

    /**
     * 标记帧资源
     * <p>
     * 将 Vulkan 纹理资源标记为 Streamline 可识别的格式。
     * <p>
     * 当前实现：存根实现，记录资源数量但不执行实际标记操作。
     * 待新的 Streamline C++ Bridge 实现完成后将恢复完整的资源标记功能，
     * 包括通过 VulkanStreamlineBridge.ResourceTagData 和 ResourceTagBatch
     * 调用 SLFFMBindings.slSetTagForFrame 进行标记。
     *
     * @param resources 资源映射（BufferType → VkImageView handle）
     * @return 是否成功
     */
    public boolean tagResources(Map<Integer, Long> resources) {
        if (!frameActive) {
            LOGGER.severe("No active frame - call beginFrame first");
            return false;
        }

        if (resources == null || resources.isEmpty()) {
            LOGGER.fine("tagResources (Map): 空资源列表，跳过标记");
            return true;
        }

        LOGGER.fine(String.format(
                "tagResources (Map) called with %d resources - 存根实现",
                resources.size()));
        return true;
    }

    /**
     * 标记帧资源（带尺寸信息）
     * <p>
     * 使用 ResourceTagData 数组进行更精细的资源标记控制。
     * <p>
     * 当前实现：存根实现，记录资源数量但不执行实际标记操作。
     * 待新的 Streamline C++ Bridge 实现完成后将恢复完整功能，
     * 通过 ResourceTagBatch 批量调用 slSetTagForFrame 提升性能。
     *
     * @param resources 资源标签数据数组（当前为 Object[] 类型以支持模块化解耦）
     *                  未来将恢复为 VulkanStreamlineBridge.ResourceTagData[] 强类型
     * @return 始终返回 true（当前为存根实现）
     */
    public boolean tagResources(Object[] resources) {
        if (!frameActive) {
            LOGGER.severe("No active frame - call beginFrame first");
            return false;
        }

        if (resources == null || resources.length == 0) {
            LOGGER.fine("tagResources (ResourceTagData[]): 空资源数组，跳过标记");
            return true;
        }

        LOGGER.fine(String.format(
                "tagResources (ResourceTagData[]) called with %d resources - 存根实现",
                resources.length));
        return true;
    }

    /**
     * 设置常量
     * <p>
     * 设置相机矩阵、抖动偏移等常量数据。
     *
     * @param constantsData 常量数据
     * @return 是否成功
     */
    public boolean setConstants(ConstantsData constantsData) {
        if (!frameActive) {
            LOGGER.severe("No active frame - call beginFrame first");
            return false;
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment constants = constantsData.toMemorySegment(arena);

            int result = SLFFMBindings.slSetConstants(
                constants,
                currentFrameToken.get(ValueLayout.ADDRESS, 0),
                viewportHandle
            );

            if (!SLFFMBindings.isOk(result)) {
                LOGGER.warning("slSetConstants failed: " + SLFFMBindings.getResultDescription(result));
                return false;
            }

            return true;
        } catch (SLException e) {
            LOGGER.severe("setConstants error: " + e.getMessage());
            return false;
        }
    }

    /**
     * 评估特性
     * <p>
     * 执行 DLSS/DLSS-G 等特性的帧评估。
     *
     * @param featureId 特性 ID（如 SLFFMBindings.FEATURE_DLSS）
     * @param cmdBuffer VkCommandBuffer 句柄
     * @return 是否成功
     */
    public boolean evaluateFeature(int featureId, long cmdBuffer) {
        if (!frameActive) {
            LOGGER.severe("No active frame - call beginFrame first");
            return false;
        }

        try {
            MemorySegment cmdBufferPtr = cmdBuffer != 0
                ? MemorySegment.ofAddress(cmdBuffer)
                : MemorySegment.NULL;

            int result = SLFFMBindings.slEvaluateFeature(
                featureId,
                currentFrameToken.get(ValueLayout.ADDRESS, 0),
                MemorySegment.NULL, // inputs (通常为 null)
                0,                  // numInputs
                cmdBufferPtr
            );

            if (!SLFFMBindings.isOk(result)) {
                LOGGER.warning("slEvaluateFeature failed: " + SLFFMBindings.getResultDescription(result));
                return false;
            }

            return true;
        } catch (SLException e) {
            LOGGER.severe("evaluateFeature error: " + e.getMessage());
            return false;
        }
    }

    // ==================== BackendInterceptor 便捷方法 ====================

    /**
     * 设置输入颜色缓冲区（BackendInterceptor 便捷方法）
     *
     * @param colorBuffer 颜色缓冲 VkImageView 句柄
     */
    public void setInputColor(long colorBuffer) {
        // 将颜色缓冲保存到资源映射中，待 tagResources 时使用
        // 当前实现为存根，仅记录日志
        LOGGER.fine("setInputColor: colorBuffer=0x" + Long.toHexString(colorBuffer));
    }

    /**
     * 设置输入深度缓冲区（BackendInterceptor 便捷方法）
     *
     * @param depthBuffer 深度缓冲 VkImageView 句柄
     */
    public void setInputDepth(long depthBuffer) {
        // 将深度缓冲保存到资源映射中，待 tagResources 时使用
        // 当前实现为存根，仅记录日志
        LOGGER.fine("setInputDepth: depthBuffer=0x" + Long.toHexString(depthBuffer));
    }

    /**
     * 设置输出目标（BackendInterceptor 便捷方法）
     *
     * @param colorBuffer 输出目标 VkImageView 句柄
     */
    public void setOutputTarget(long colorBuffer) {
        // 设置输出目标
        // 当前实现为存根，仅记录日志
        LOGGER.fine("setOutputTarget: output=0x" + Long.toHexString(colorBuffer));
    }

    /**
     * 执行帧评估（BackendInterceptor 便捷方法）
     * <p>
     * 等价于调用 evaluateFeature(SL_FEATURE_DLSS_SUPER_SAMPLING, 0)
     *
     * @return 是否成功
     */
    public boolean evaluate() {
        if (!frameActive) {
            LOGGER.warning("evaluate() called but frame not active");
            return false;
        }
        // 使用 DLSS 超采样 Feature ID（默认值 1）
        return evaluateFeature(1, 0);
    }

    /**
     * 结束帧
     */
    public void endFrame() {
        frameActive = false;
        currentFrameToken = null;
        viewportHandle = null;
    }

    /**
     * 释放所有native资源（在不再使用此evaluator时调用）
     * <p>
     * 必须在FrameEvaluator被GC前调用，否则会导致native内存泄漏。
     * 典型用法：在Mod卸载或游戏退出时调用。
     */
    public void close() {
        endFrame();
        if (sharedArena != null) {
            sharedArena.close();
        }
    }

    /**
     * 帧是否活跃
     */
    public boolean isFrameActive() {
        return frameActive;
    }

    /**
     * Streamline 常量数据
     * <p>
     * 封装 sl::Constants 结构的所有字段。
     */
    public static final class ConstantsData {
        // 抖动偏移
        public float jitterOffsetX;
        public float jitterOffsetY;

        // 运动矢量缩放
        public float motionVectorScaleX = 1.0f;
        public float motionVectorScaleY = 1.0f;

        // 相机矩阵（3x4 行主序）
        public float[] cameraViewToClip = new float[12];
        public float[] clipToCameraView = new float[12];
        public float[] prevCameraViewToClip = new float[12];
        public float[] prevClipToCameraView = new float[12];

        // 重置标志（场景切换时设为 1）
        public int reset = 0;

        // 深度参数
        public float nearClipValue = 0.0f;
        public float farClipValue = 1.0f;

        // 相机参数
        public float cameraFov;
        public float cameraAspect;

        // 矩阵模式
        public int cameraMatrixMode = 0; // DEFAULT
        public int depthInverted = 0;    // NOT_INVERTED
        public int motionVectorPrecision = 0; // DEFAULT
        public int motionVectors3D = 0;  // NOT_3D

        /**
         * 转换为 MemorySegment
         *
         * @param arena 内存区域
         * @return sl::Constants 结构的 MemorySegment
         */
        public MemorySegment toMemorySegment(Arena arena) {
            MemorySegment seg = arena.allocate(CONSTANTS_SIZE);

            // sType = SL_STRUCT_TYPE_CONSTANTS
            seg.set(ValueLayout.JAVA_INT, 0, 0x02);

            // next = nullptr
            seg.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);

            // jitterOffset[2]
            seg.set(ValueLayout.JAVA_FLOAT, 16, jitterOffsetX);
            seg.set(ValueLayout.JAVA_FLOAT, 20, jitterOffsetY);

            // motionVectorScale[2]
            seg.set(ValueLayout.JAVA_FLOAT, 24, motionVectorScaleX);
            seg.set(ValueLayout.JAVA_FLOAT, 28, motionVectorScaleY);

            // cameraMotion[3][4] - offset 32, 48 bytes
            // 留空（Streamline 可自动计算）

            // cameraViewToClip[3][4] - offset 80
            copyMatrix3x4(seg, 80, cameraViewToClip);

            // clipToCameraView[3][4] - offset 128
            copyMatrix3x4(seg, 128, clipToCameraView);

            // prevCameraViewToClip[3][4] - offset 176
            copyMatrix3x4(seg, 176, prevCameraViewToClip);

            // prevClipToCameraView[3][4] - offset 224
            copyMatrix3x4(seg, 224, prevClipToCameraView);

            // cameraViewToPrevCameraView[3][4] - offset 272
            // prevCameraViewToCameraView[3][4] - offset 320
            // 留空（Streamline 可自动计算）

            // reset - offset 368
            seg.set(ValueLayout.JAVA_INT, 368, reset);

            // nearClipValue - offset 372
            seg.set(ValueLayout.JAVA_FLOAT, 372, nearClipValue);

            // farClipValue - offset 376
            seg.set(ValueLayout.JAVA_FLOAT, 376, farClipValue);

            // cameraFov - offset 380
            seg.set(ValueLayout.JAVA_FLOAT, 380, cameraFov);

            // cameraAspect - offset 384
            seg.set(ValueLayout.JAVA_FLOAT, 384, cameraAspect);

            // cameraNear - offset 388
            seg.set(ValueLayout.JAVA_FLOAT, 388, nearClipValue);

            // cameraFar - offset 392
            seg.set(ValueLayout.JAVA_FLOAT, 392, farClipValue);

            // cameraMatrixMode - offset 396
            seg.set(ValueLayout.JAVA_INT, 396, cameraMatrixMode);

            // depthInverted - offset 400
            seg.set(ValueLayout.JAVA_INT, 400, depthInverted);

            // motionVectorPrecision - offset 404
            seg.set(ValueLayout.JAVA_INT, 404, motionVectorPrecision);

            // motionVectors3D - offset 408
            seg.set(ValueLayout.JAVA_INT, 408, motionVectors3D);

            return seg;
        }

        private void copyMatrix3x4(MemorySegment seg, long offset, float[] matrix) {
            for (int i = 0; i < Math.min(matrix.length, 12); i++) {
                seg.set(ValueLayout.JAVA_FLOAT, offset + i * 4L, matrix[i]);
            }
        }
    }
}
