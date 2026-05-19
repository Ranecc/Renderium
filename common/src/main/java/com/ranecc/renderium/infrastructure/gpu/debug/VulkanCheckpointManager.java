// Renderium - Vulkan Checkpoint 调试管理器
// 包装 s7 的 CheckpointExtension，在关键渲染 Pass 处插入 GPU 调试标记

package com.ranecc.renderium.infrastructure.gpu.debug;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import com.ranecc.renderium.infrastructure.gpu.VulkanOperationGuard;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.util.List;
import java.util.logging.Logger;

/**
 * Vulkan Checkpoint 调试管理器
 * <p>
 * 包装 s7 的 {@link CheckpointExtension}，提供 GPU 命令调试检查点功能。
 * 在关键渲染 Pass（HiZ 构建、LOD 剔除、超分辨率等）处插入调试标记，
 * 在 GPU 崩溃或设备丢失时可通过 {@link #retrieveCheckpoints(boolean)} 获取最后执行的命令位置。
 * </p>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * if (VulkanCheckpointManager.isSupported()) {
 *     VulkanCheckpointManager.get().recordCheckpoint("Renderium:HiZCompute");
 * }
 * }</pre>
 *
 * <h3>支持的 GPU：</h3>
 * <ul>
 *   <li>NVIDIA — {@code NvidiaCheckpointExtension} (VK_NV_device_diagnostic_checkpoints)</li>
 *   <li>AMD — {@code AmdCheckpointExtension} (using VK_AMD_buffer_marker)</li>
 *   <li>其他 — {@code NoopCheckpointExtension} (空操作，安全降级)</li>
 * </ul>
 *
 * @see CheckpointExtension
 * @see com.mojang.blaze3d.GpuDeviceLossException
 * @since 6.0.0
 */
public final class VulkanCheckpointManager {

    private static final Logger LOGGER = Logger.getLogger(VulkanCheckpointManager.class.getName());

    private static volatile VulkanCheckpointManager instance;

    private final CheckpointExtension extension;
    private final CheckpointExtension.CheckpointStorage storage;
    private final VkDevice vkDevice;
    private final boolean noop;

    private VulkanCheckpointManager(CheckpointExtension extension,
                                     CheckpointExtension.CheckpointStorage storage,
                                     VkDevice vkDevice,
                                     boolean noop) {
        this.extension = extension;
        this.storage = storage;
        this.vkDevice = vkDevice;
        this.noop = noop;
    }

    /**
     * 获取全局单例
     *
     * @return VulkanCheckpointManager 实例，未初始化返回 null
     */
    public static VulkanCheckpointManager get() {
        return instance;
    }

    /**
     * Checkpoint 调试是否可用
     *
     * @return true 表示已初始化且非空操作（NVIDIA/AMD 实际实现）
     */
    public static boolean isSupported() {
        return instance != null && !instance.noop;
    }

    /**
     * 初始化 Checkpoint 管理器
     * <p>
     * 从 s7 VulkanDevice 获取 CheckpointExtension，为计算队列创建存储。
     * 失败时不抛出异常，{@link #isSupported()} 返回 false。
     *
     * @param device s7 VulkanDevice 实例
     */
    public static void initialize(VulkanDevice device) {
        if (instance != null) return;

        try {
            CheckpointExtension ext = device.checkpointExtension();
            if (ext == null) {
                LOGGER.fine("VulkanCheckpointManager: 设备不支持 CheckpointExtension");
                return;
            }

            CheckpointExtension.CheckpointStorage st = ext.createStorage(device, device.computeQueue(), 2);
            boolean isNoop = ext.getClass().getName().contains("NoopCheckpointExtension");

            instance = new VulkanCheckpointManager(ext, st, device.vkDevice(), isNoop);

            LOGGER.info(String.format(
                    "VulkanCheckpointManager: 已初始化 [type=%s, queue=compute]",
                    isNoop ? "noop" : ext.getClass().getSimpleName()
            ));
        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
            LOGGER.fine("VulkanCheckpointManager 初始化失败: " + e.getMessage());
        }
    }

    /**
     * 在指定的命令缓冲区上记录检查点
     * <p>
     * 此方法应插入到关键渲染 Pass 的 Command Buffer 录制期间。
     * 非阻塞，零开销（空操作实现时无任何操作）。
     *
     * @param commandBuffer VkCommandBuffer 句柄 (long)
     * @param label         检查点标签（如 "Renderium:HiZBuild"）
     */
    public void recordCheckpoint(long commandBuffer, String label) {
        if (noop) return;
        try {
            storage.recordCheckpoint(
                    new VkCommandBuffer(commandBuffer, vkDevice),
                    CheckpointExtension.CheckpointType.BEGIN_RENDER_PASS,
                    () -> label
            );
        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
            LOGGER.finest("recordCheckpoint 失败: " + e.getMessage());
        }
    }

    /**
     * 获取当前所有队列的检查点状态
     * <p>
     * 在捕获到 {@link com.mojang.blaze3d.GpuDeviceLossException} 后调用，
     * 用于定位 GPU 崩溃时的最后执行位置。
     *
     * @param isDeviceLost 是否因设备丢失触发
     * @return 检查点列表（可能为空）
     */
    public List<CheckpointExtension.QueueCheckpoints> retrieveCheckpoints(boolean isDeviceLost) {
        if (noop) return List.of();
        try {
            return extension.retrieveCheckpoints(isDeviceLost);
        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
            LOGGER.warning("retrieveCheckpoints 失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 轮转检查点帧缓存
     * <p>
     * 每帧结束后调用，使检查点存储进入下一帧。
     */
    public void rotate() {
        if (noop) return;
        try {
            storage.rotate();
        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
            LOGGER.finest("rotate 失败: " + e.getMessage());
        }
    }
}
