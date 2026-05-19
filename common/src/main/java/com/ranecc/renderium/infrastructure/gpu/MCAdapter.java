package com.ranecc.renderium.infrastructure.gpu;

import java.util.logging.Logger;

/**
 * Minecraft 适配器 — 统一从 Minecraft 数据类型提取渲染所需状态。
 *
 * <p>集中管理所有与 Minecraft 交互的反射调用，消除 D 各处的重复适配逻辑。
 *
 * <h3>能力</h3>
 * <ul>
 *   <li>从 {@code Camera} 对象提取位置/朝向</li>
 *   <li>SectionPos / ChunkPos 坐标编码/解码</li>
 *   <li>Frustum 裁剪接口</li>
 * </ul>
 */
public final class MCAdapter {

    private static final Logger LOGGER = Logger.getLogger("Renderium|MCAdapter");

    /** SectionPos.blockToSectionCoord = x >> 4 */
    public static final int SECTION_BITS = 4;
    public static final int SECTION_SIZE = 16;

    private MCAdapter() {}

    /**
     * 相机状态（不可变记录）。
     */
    public record CameraState(double x, double y, double z, float fov) {}

    /**
     * 从 Minecraft Camera 对象提取相机状态。
     * <p>兼容 MC 26.2 的 record API 和旧版字段访问。
     *
     * @param camera Minecraft Camera 对象
     * @return 相机状态，如果提取失败返回 (0,0,0,70)
     */
    public static CameraState extractCamera(Object camera) {
        if (camera == null) return new CameraState(0, 0, 0, 70f);
        try {
            // MC 26.2+ record API: Camera.position().x()/y()/z()
            Object pos = camera.getClass().getMethod("position").invoke(camera);
            double x = (double) pos.getClass().getMethod("x").invoke(pos);
            double y = (double) pos.getClass().getMethod("y").invoke(pos);
            double z = (double) pos.getClass().getMethod("z").invoke(pos);
            float fov = 70f;
            try {
                fov = (float) camera.getClass().getMethod("getFov").invoke(camera);
            } catch (NoSuchMethodException e) {
                // 旧版: 反射字段
                fov = camera.getClass().getField("fov").getFloat(camera);
            }
            return new CameraState(x, y, z, fov);
        } catch (Exception e) {
            try {
                // 旧版: Camera.x/y/z 字段
                double x = camera.getClass().getField("x").getDouble(camera);
                double y = camera.getClass().getField("y").getDouble(camera);
                double z = camera.getClass().getField("z").getDouble(camera);
                return new CameraState(x, y, z, 70f);
            } catch (Exception e2) {
                LOGGER.fine("extractCamera 失败: " + e2.getMessage());
                return new CameraState(0, 0, 0, 70f);
            }
        }
    }

    // ==================== SectionPos 坐标系统 ====================

    /** SectionPos.blockToSectionCoord: x >> 4 */
    public static int blockToSection(int blockCoord) {
        return blockCoord >> SECTION_BITS;
    }

    /** SectionPos.sectionToBlockCoord: x << 4 */
    public static int sectionToBlock(int sectionCoord) {
        return sectionCoord << SECTION_BITS;
    }

    /**
     * SectionPos.asLong: 打包 X(22bit)+Y(20bit)+Z(22bit)
     */
    public static long packSectionKey(int sectionX, int sectionY, int sectionZ) {
        long node = 0L;
        node |= ((long) sectionX & 4194303L) << 42;
        node |= ((long) sectionY & 1048575L) << 0;
        node |= ((long) sectionZ & 4194303L) << 20;
        return node;
    }

    public static int unpackSectionX(long key) { return (int) (key << 0 >> 42); }
    public static int unpackSectionY(long key) { return (int) (key << 44 >> 44); }
    public static int unpackSectionZ(long key) { return (int) (key << 22 >> 42); }

    /** ChunkPos.pack: X(低32位) + Z(高32位) */
    public static long packChunkPos(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z) << 32);
    }

    public static int unpackChunkX(long key) { return (int) (key & 0xFFFFFFFFL); }
    public static int unpackChunkZ(long key) { return (int) (key >> 32); }

    // ==================== Frustum 辅助 ====================

    /**
     * 从 Minecraft Frustum 对象测试 AABB 可见性。
     * <p>兼容 MC 26.2 的 FrustumIntersection / JOML 方式。
     *
     * @param frustum Minecraft Frustum 对象
     * @param minX    包围盒最小 X
     * @param minY    包围盒最小 Y
     * @param minZ    包围盒最小 Z
     * @param maxX    包围盒最大 X
     * @param maxY    包围盒最大 Y
     * @param maxZ    包围盒最大 Z
     * @return true 如果包围盒可能可见
     */
    public static boolean frustumTest(Object frustum, double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        if (frustum == null) return true;
        try {
            // MC 26.2: Frustum.cubeInFrustum(double x, double y, double z, double w, double h, double d)
            int result = (int) frustum.getClass()
                .getMethod("cubeInFrustum", double.class, double.class, double.class,
                           double.class, double.class, double.class)
                .invoke(frustum, minX, minY, minZ, maxX, maxY, maxZ);
            return result < 0;
        } catch (NoSuchMethodException e) {
            try {
                // JOML: FrustumIntersection.intersectAab
                return (boolean) frustum.getClass()
                    .getMethod("isVisible", double.class, double.class, double.class,
                               double.class, double.class, double.class)
                    .invoke(frustum, minX, minY, minZ, maxX, maxY, maxZ);
            } catch (Exception e2) {
                return true; // 保守：可见
            }
        } catch (Exception e) {
            return true;
        }
    }
}
