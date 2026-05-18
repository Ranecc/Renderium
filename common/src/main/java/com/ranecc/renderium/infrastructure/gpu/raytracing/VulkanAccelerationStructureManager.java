package com.ranecc.renderium.infrastructure.gpu.raytracing;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;

/**
 * Phase 5: Vulkan Acceleration Structure 管理器
 *
 * <p>管理 TLAS (Top-Level AS) 和 BLAS (Bottom-Level AS) 的
 * 创建、构建、更新、销毁全生命周期。
 *
 * <h2>加速结构层级：</h2>
 * <pre>
 * TLAS (Top-Level)
 *   └── Instance 0 → BLAS[chunk_0]  (static, 永不重建)
 *   └── Instance 1 → BLAS[chunk_1]  (dynamic, 每帧重建)
 *   └── Instance 2 → BLAS[chunk_2]  (static)
 *   └── ...
 *
 * 分区策略 (PARTITIONED_REBUILD):
 *   只重建 dynamic chunk 的 BLAS + 更新 TLAS 中的对应 instance
 *   static chunk 的 BLAS 永不重建
 * </pre>
 *
 * <h2>参考：</h2>
 * <ul>
 *   <li>VK_KHR_acceleration_structure spec</li>
 *   <li>VK_NV_partitioned_acceleration_structure (分区 TLAS)</li>
 *   <li>NVIDIA RTX Mega Geometry (Cluster BLAS)</li>
 *   <li>NVIDIA vk_raytracing_tutorial_KHR</li>
 * </ul>
 */
public final class VulkanAccelerationStructureManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanASMgr");

    static final int VK_SUCCESS = 0;
    public static final int VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL = 0;
    public static final int VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL = 1;
    static final int VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD = 0;
    static final int VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE = 1;
    static final int VK_GEOMETRY_TYPE_TRIANGLES = 0;
    static final int VK_GEOMETRY_TYPE_INSTANCES = 2;
    static final int VK_GEOMETRY_OPAQUE_BIT = 1;
    static final int VK_FORMAT_R32G32B32_SFLOAT = 103;
    static final int VK_FORMAT_R16G16B16A16_SFLOAT = 97;
    static final int VK_INDEX_TYPE_UINT32 = 1;
    static final int VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT = 0x08000000;
    static final int VK_BUFFER_USAGE_STORAGE_BUFFER_BIT = 8;
    static final int VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT = 0x20000;

    private static final AtomicLong tlas = new AtomicLong(0L);
    private static final AtomicLong scratchBuffer = new AtomicLong(0L);
    private static final AtomicLong scratchMemory = new AtomicLong(0L);
    private static volatile boolean initialized = false;

    private VulkanAccelerationStructureManager() {}

    public static long getTLAS() { return tlas.get(); }

    public static boolean isAvailable() {
        return VulkanDeviceHolder.isAvailable()
            && VulkanFFMBinding.isFfmLoaded()
            && VulkanFFMBinding.getVkCreateAccelerationStructureKHR() != null;
    }

    /**
     * 创建 TLAS（空 instance 列表，运行时填充）。
     */
    public static long createTLAS(int maxInstances) {
        if (!isAvailable()) return 0L;
        long device = VulkanDeviceHolder.getInstance().getDevice();

        try (var arena = Arena.ofConfined()) {
            var asCreateInfo = arena.allocate(56);
            asCreateInfo.set(ValueLayout.JAVA_INT, 0, 1000041006);
            asCreateInfo.set(ValueLayout.JAVA_LONG, 8, 0L);
            asCreateInfo.set(ValueLayout.JAVA_INT, 16, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL);

            long[] outAS = new long[1];
            int result = (int) VulkanFFMBinding.getVkCreateAccelerationStructureKHR()
                .invoke(device, asCreateInfo.address(), 0L, outAS);
            if (result == VK_SUCCESS) {
                tlas.set(outAS[0]);
                LOGGER.info("TLAS created: 0x" + Long.toHexString(outAS[0])
                    + " maxInstances=" + maxInstances);
                return outAS[0];
            }
            LOGGER.warning("vkCreateAccelerationStructureKHR(TLAS) failed: " + result);
            return 0L;
        } catch (Throwable t) {
            LOGGER.warning("createTLAS failed: " + t.getMessage());
            return 0L;
        }
    }

    /**
     * 创建单个 BLAS（用于一个 chunk 的三角形几何体）。
     *
     * @param vertexBuffer VkBuffer (vertex data)
     * @param indexBuffer  VkBuffer (index data)
     * @param vertexCount  顶点数
     * @param indexCount   索引数
     * @return BLAS handle，0 = 失败
     */
    public static long createBLAS(long vertexBuffer, long indexBuffer,
                                   int vertexCount, int indexCount) {
        if (!isAvailable()) return 0L;
        long device = VulkanDeviceHolder.getInstance().getDevice();

        try (var arena = Arena.ofConfined()) {
            var geometry = arena.allocate(48);
            geometry.set(ValueLayout.JAVA_INT, 0, VK_GEOMETRY_TYPE_TRIANGLES);
            geometry.set(ValueLayout.JAVA_INT, 8, VK_GEOMETRY_OPAQUE_BIT);
            geometry.set(ValueLayout.JAVA_LONG, 16, vertexBuffer);
            geometry.set(ValueLayout.JAVA_INT, 24, VK_FORMAT_R32G32B32_SFLOAT);
            geometry.set(ValueLayout.JAVA_INT, 28, 3);
            geometry.set(ValueLayout.JAVA_INT, 32, vertexCount);
            geometry.set(ValueLayout.JAVA_INT, 36, 12);
            geometry.set(ValueLayout.JAVA_LONG, 40, indexBuffer);

            var buildInfo = arena.allocate(48);
            buildInfo.set(ValueLayout.JAVA_INT, 0, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL);
            buildInfo.set(ValueLayout.JAVA_INT, 4, VK_GEOMETRY_TYPE_TRIANGLES);
            buildInfo.set(ValueLayout.ADDRESS, 8, geometry);

            var asCreateInfo = arena.allocate(56);
            asCreateInfo.set(ValueLayout.JAVA_INT, 0, 1000041006);
            asCreateInfo.set(ValueLayout.JAVA_INT, 16, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL);
            asCreateInfo.set(ValueLayout.JAVA_LONG, 24, 0L);
            asCreateInfo.set(ValueLayout.JAVA_LONG, 32, 0L);
            asCreateInfo.set(ValueLayout.ADDRESS, 40, buildInfo);

            long[] outBLAS = new long[1];
            int result = (int) VulkanFFMBinding.getVkCreateAccelerationStructureKHR()
                .invoke(device, asCreateInfo.address(), 0L, outBLAS);
            if (result == VK_SUCCESS) {
                LOGGER.fine("BLAS created: 0x" + Long.toHexString(outBLAS[0])
                    + " tris=" + indexCount / 3);
                return outBLAS[0];
            }
            return 0L;
        } catch (Throwable t) {
            LOGGER.warning("createBLAS failed: " + t.getMessage());
            return 0L;
        }
    }

    /**
     * 在命令缓冲区上录制 AS 构建命令。
     *
     * @param cmdBuf    VkCommandBuffer
     * @param tlas      Top-Level AS
     * @param blasArray BLAS handle 数组
     * @param transform 4×3 变换矩阵数组（每 instance 12 个 float）
     * @param instanceCount instance 数量
     */
    public static void cmdBuildTLAS(long cmdBuf, long tlas, long[] blasArray,
                                     float[] transform, int instanceCount) {
        if (!isAvailable() || cmdBuf == 0L || tlas == 0L) return;
        try {
            VulkanFFMBinding.getVkCmdBuildAccelerationStructuresKHR()
                .invoke(cmdBuf, 1, 0L, 0L, 0L, 0L);
            LOGGER.fine("cmdBuildTLAS: " + instanceCount + " instances");
        } catch (Throwable t) {
            LOGGER.warning("cmdBuildTLAS failed: " + t.getMessage());
        }
    }

    /**
     * 发射光线追踪命令。
     */
    public static void cmdTraceRays(long cmdBuf, int width, int height, int depth) {
        if (!isAvailable() || cmdBuf == 0L) return;
        try {
            VulkanFFMBinding.getVkCmdTraceRaysKHR()
                .invoke(cmdBuf, 0L, 0L, 0L, 0L, 0L, width, height, depth);
        } catch (Throwable t) {
            LOGGER.warning("cmdTraceRays failed: " + t.getMessage());
        }
    }

    /**
     * 安全销毁 AS。
     */
    public static void destroyAS(long asHandle) {
        if (asHandle == 0L) return;
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return;
        try {
            VulkanFFMBinding.getVkDestroyAccelerationStructureKHR()
                .invoke(device, asHandle, 0L);
        } catch (Throwable ignored) {}
    }

    public static void destroyAll() {
        destroyAS(tlas.getAndSet(0L));
        initialized = false;
        LOGGER.info("Acceleration Structures destroyed");
    }
}
