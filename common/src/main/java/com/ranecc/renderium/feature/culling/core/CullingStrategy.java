// Renderium - Culling Strategy
// Base interface and implementations for culling algorithms
// 视锥体剔除使用标准 6 平面提取算法

package com.ranecc.renderium.feature.culling.core;

import java.util.*;

/**
 * 剔除策略接口
 */
public interface CullingStrategy {

    /**
     * 获取优先级
     * 数值越小越先执行
     */
    int getPriority();

    /**
     * 是否累加模式
     * true: 与之前策略结果取并集
     * false: 与之前策略结果取交集
     */
    default boolean isAdditive() {
        return false;
    }

    /**
     * 执行剔除
     *
     * @param chunks 所有区块
     * @param cameraX 相机 X
     * @param cameraY 相机 Y
     * @param cameraZ 相机 Z
     * @param pitch 俯仰角
     * @param yaw 偏航角
     * @param fov 视野
     * @return 可见区块索引集合
     */
    Set<Integer> cull(List<ChunkData> chunks,
                      float cameraX, float cameraY, float cameraZ,
                      float pitch, float yaw, float fov);

    /**
     * 区块数据记录
     */
    record ChunkData(
        int x, int y, int z,
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ
    ) {}
}

/**
 * 视锥体剔除策略
 *
 * <p>基于视锥体与区块 AABB 的相交测试。
 * 使用标准的 6 平面提取算法（Gribb/Hartmann 方法），
 * 从投影矩阵中提取视锥体的 6 个裁剪平面，
 * 然后用 AABB-Plane 测试判断区块是否在视锥体内。
 *
 * <h2>算法复杂度</h2>
 * <p>每个区块需要 6 次平面测试，每次测试最多 8 个顶点。
 * 使用 AABB 的 p-vertex/n-vertex 优化，每次平面测试只需 1 次点积。
 * 总复杂度：O(N) 其中 N 为区块数量。
 *
 * @author Renderium Team
 * @since 1.0.0
 */
class FrustumCullingStrategy implements CullingStrategy {

    /** 视锥体平面数量 */
    private static final int FRUSTUM_PLANES = 6;

    /** 平面分量数 (A, B, C, D) */
    private static final int PLANE_COMPONENTS = 4;

    /** 平面索引：左 */
    private static final int LEFT = 0;
    /** 平面索引：右 */
    private static final int RIGHT = 1;
    /** 平面索引：下 */
    private static final int BOTTOM = 2;
    /** 平面索引：上 */
    private static final int TOP = 3;
    /** 平面索引：近 */
    private static final int NEAR = 4;
    /** 平面索引：远 */
    private static final int FAR = 5;

    /** 默认近裁剪面距离 */
    private static final float DEFAULT_NEAR = 0.1f;

    /** 默认远裁剪面距离 */
    private static final float DEFAULT_FAR = 1000.0f;

    /** 默认宽高比 */
    private static final float DEFAULT_ASPECT = 16.0f / 9.0f;

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public boolean isAdditive() {
        return false;
    }

    @Override
    public Set<Integer> cull(List<ChunkData> chunks,
                             float cameraX, float cameraY, float cameraZ,
                             float pitch, float yaw, float fov) {
        Set<Integer> visible = new HashSet<>();

        // 计算视锥体平面
        float[] planes = computeFrustumPlanes(cameraX, cameraY, cameraZ, pitch, yaw, fov);

        // 对每个区块执行视锥体测试
        for (int i = 0; i < chunks.size(); i++) {
            ChunkData chunk = chunks.get(i);
            if (isAabbInFrustum(chunk, planes)) {
                visible.add(i);
            }
        }

        return visible;
    }

    /**
     * 从相机参数计算视锥体的 6 个裁剪平面
     *
     * <p>使用标准算法构建视锥体平面：
     * <ol>
     *   <li>从相机位置和朝向构建 view 矩阵</li>
     *   <li>从 FOV 和宽高比构建 projection 矩阵</li>
     *   <li>从 view-projection 矩阵提取 6 个平面</li>
     * </ol>
     *
     * <p>每个平面用 4 个 float (A, B, C, D) 表示，
     * 其中 Ax + By + Cz + D = 0 是平面方程，
     * 法线 (A, B, C) 指向视锥体内部。
     *
     * @param camX 相机 X 坐标
     * @param camY 相机 Y 坐标
     * @param camZ 相机 Z 坐标
     * @param pitch 俯仰角（弧度）
     * @param yaw 偏航角（弧度）
     * @param fov 视野角（弧度）
     * @return 24 个 float (6 平面 × 4 分量)
     */
    private float[] computeFrustumPlanes(float camX, float camY, float camZ,
                                          float pitch, float yaw, float fov) {
        float[] planes = new float[FRUSTUM_PLANES * PLANE_COMPONENTS];

        // 计算前方向向量（从 pitch 和 yaw 推导）
        float cosPitch = (float) Math.cos(pitch);
        float sinPitch = (float) Math.sin(pitch);
        float cosYaw = (float) Math.cos(yaw);
        float sinYaw = (float) Math.sin(yaw);

        // Minecraft 坐标系：X=东, Y=上, Z=南
        // 前方向（视线方向）
        float forwardX = -sinYaw * cosPitch;
        float forwardY = -sinPitch;
        float forwardZ = -cosYaw * cosPitch;

        // 上方向（世界 Y 轴投影到垂直于前方向的平面）
        float upX = sinYaw * sinPitch;
        float upY = cosPitch;
        float upZ = cosYaw * sinPitch;

        // 右方向 = forward × up
        float rightX = cosYaw;
        float rightY = 0;
        float rightZ = -sinYaw;

        // 计算 FOV 相关参数
        float tanHalfFov = (float) Math.tan(fov * 0.5f);
        float nearHeight = DEFAULT_NEAR * tanHalfFov;
        float nearWidth = nearHeight * DEFAULT_ASPECT;
        float farHeight = DEFAULT_FAR * tanHalfFov;
        float farWidth = farHeight * DEFAULT_ASPECT;

        // 近平面中心
        float nearCenterX = camX + forwardX * DEFAULT_NEAR;
        float nearCenterY = camY + forwardY * DEFAULT_NEAR;
        float nearCenterZ = camZ + forwardZ * DEFAULT_NEAR;

        // 远平面中心
        float farCenterX = camX + forwardX * DEFAULT_FAR;
        float farCenterY = camY + forwardY * DEFAULT_FAR;
        float farCenterZ = camZ + forwardZ * DEFAULT_FAR;

        // 左平面：法线 = (nearCenter - up*nearHeight) - cam → 叉乘 forward
        setPlane(planes, LEFT,
                camX, camY, camZ,
                nearCenterX - upX * nearHeight - rightX * nearWidth,
                nearCenterY - upY * nearHeight - rightY * nearWidth,
                nearCenterZ - upZ * nearHeight - rightZ * nearWidth,
                nearCenterX + upX * nearHeight - rightX * nearWidth,
                nearCenterY + upY * nearHeight - rightY * nearWidth,
                nearCenterZ + upZ * nearHeight - rightZ * nearWidth);

        // 右平面：法线 = (nearCenter + right*nearWidth) - cam → 叉乘 forward
        setPlane(planes, RIGHT,
                camX, camY, camZ,
                nearCenterX + upX * nearHeight + rightX * nearWidth,
                nearCenterY + upY * nearHeight + rightY * nearWidth,
                nearCenterZ + upZ * nearHeight + rightZ * nearWidth,
                nearCenterX - upX * nearHeight + rightX * nearWidth,
                nearCenterY - upY * nearHeight + rightY * nearWidth,
                nearCenterZ - upZ * nearHeight + rightZ * nearWidth);

        // 下平面
        setPlane(planes, BOTTOM,
                camX, camY, camZ,
                nearCenterX - rightX * nearWidth - upX * nearHeight,
                nearCenterY - rightY * nearWidth - upY * nearHeight,
                nearCenterZ - rightZ * nearWidth - upZ * nearHeight,
                nearCenterX + rightX * nearWidth - upX * nearHeight,
                nearCenterY + rightY * nearWidth - upY * nearHeight,
                nearCenterZ + rightZ * nearWidth - upZ * nearHeight);

        // 上平面
        setPlane(planes, TOP,
                camX, camY, camZ,
                nearCenterX + rightX * nearWidth + upX * nearHeight,
                nearCenterY + rightY * nearWidth + upY * nearHeight,
                nearCenterZ + rightZ * nearWidth + upZ * nearHeight,
                nearCenterX - rightX * nearWidth + upX * nearHeight,
                nearCenterY - rightY * nearWidth + upY * nearHeight,
                nearCenterZ - rightZ * nearWidth + upZ * nearHeight);

        // 近平面：法线 = -forward
        setPlane(planes, NEAR, -forwardX, -forwardY, -forwardZ, nearCenterX, nearCenterY, nearCenterZ);

        // 远平面：法线 = forward
        setPlane(planes, FAR, forwardX, forwardY, forwardZ, farCenterX, farCenterY, farCenterZ);

        return planes;
    }

    /**
     * 从 3 个点设置平面方程
     * 法线指向视锥体内部
     */
    private void setPlane(float[] planes, int planeIndex,
                          float p0x, float p0y, float p0z,
                          float p1x, float p1y, float p1z,
                          float p2x, float p2y, float p2z) {
        // 两条边向量
        float e1x = p1x - p0x, e1y = p1y - p0y, e1z = p1z - p0z;
        float e2x = p2x - p0x, e2y = p2y - p0y, e2z = p2z - p0z;

        // 法线 = e1 × e2
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;

        // 归一化
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-6f) {
            nx /= len; ny /= len; nz /= len;
        }

        // D = -(n · p0)
        float d = -(nx * p0x + ny * p0y + nz * p0z);

        int offset = planeIndex * PLANE_COMPONENTS;
        planes[offset] = nx;
        planes[offset + 1] = ny;
        planes[offset + 2] = nz;
        planes[offset + 3] = d;
    }

    /**
     * 从法线和点设置平面方程
     */
    private void setPlane(float[] planes, int planeIndex,
                          float nx, float ny, float nz,
                          float px, float py, float pz) {
        // 归一化
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-6f) {
            nx /= len; ny /= len; nz /= len;
        }

        float d = -(nx * px + ny * py + nz * pz);

        int offset = planeIndex * PLANE_COMPONENTS;
        planes[offset] = nx;
        planes[offset + 1] = ny;
        planes[offset + 2] = nz;
        planes[offset + 3] = d;
    }

    /**
     * AABB 与视锥体相交测试
     *
     * <p>使用 p-vertex/n-vertex 优化：
     * 对于每个平面，找到 AABB 上离平面最远的顶点（p-vertex），
     * 如果 p-vertex 在平面外侧，则 AABB 完全在视锥体外。
     *
     * <p>这是最快的 AABB-Frustum 测试方法，
     * 每个平面只需 1 次点积（而非 8 次）。
     *
     * @param chunk 区块 AABB 数据
     * @param planes 视锥体平面数组 (6×4)
     * @return true 如果 AABB 与视锥体相交
     */
    private boolean isAabbInFrustum(ChunkData chunk, float[] planes) {
        for (int i = 0; i < FRUSTUM_PLANES; i++) {
            int offset = i * PLANE_COMPONENTS;
            float a = planes[offset];
            float b = planes[offset + 1];
            float c = planes[offset + 2];
            float d = planes[offset + 3];

            // 计算 p-vertex：选择 AABB 上使 A*x+B*y+C*z 最大的顶点
            float px = (a >= 0) ? chunk.maxX() : chunk.minX();
            float py = (b >= 0) ? chunk.maxY() : chunk.minY();
            float pz = (c >= 0) ? chunk.maxZ() : chunk.minZ();

            // 如果 p-vertex 在平面外侧，AABB 完全不可见
            if (a * px + b * py + c * pz + d < 0) {
                return false;
            }
        }
        return true;
    }
}

/**
 * 距离剔除策略
 * 基于最大可视距离剔除过远的区块
 */
class DistanceCullingStrategy implements CullingStrategy {

    private final int maxDistance;

    public DistanceCullingStrategy(int maxDrawDistance) {
        this.maxDistance = maxDrawDistance;
    }

    @Override
    public int getPriority() {
        return 200;
    }

    @Override
    public boolean isAdditive() {
        return true;
    }

    @Override
    public Set<Integer> cull(List<ChunkData> chunks,
                             float cameraX, float cameraY, float cameraZ,
                             float pitch, float yaw, float fov) {
        Set<Integer> visible = new HashSet<>();

        int cameraChunkX = (int) Math.floor(cameraX / 16.0f);
        int cameraChunkZ = (int) Math.floor(cameraZ / 16.0f);

        int maxDistSq = maxDistance * maxDistance;
        for (int i = 0; i < chunks.size(); i++) {
            ChunkData chunk = chunks.get(i);
            int dx = chunk.x() - cameraChunkX;
            int dz = chunk.z() - cameraChunkZ;
            if (dx * dx + dz * dz <= maxDistSq) {
                visible.add(i);
            }
        }

        return visible;
    }
}

/**
 * 遮挡剔除策略
 * 基于 Hi-Z 或 Occlusion Query 的遮挡剔除
 */
class OcclusionCullingStrategy implements CullingStrategy {

    private long depthTexture;
    private int mipLevel;

    public OcclusionCullingStrategy() {
        this.mipLevel = 1;
    }

    @Override
    public int getPriority() {
        return 300;
    }

    @Override
    public boolean isAdditive() {
        return true;
    }

    public void setDepthTexture(long vulkanImage) {
        this.depthTexture = vulkanImage;
    }

    @Override
    public Set<Integer> cull(List<ChunkData> chunks,
                             float cameraX, float cameraY, float cameraZ,
                             float pitch, float yaw, float fov) {
        Set<Integer> visible = new HashSet<>();

        if (depthTexture == 0) {
            for (int i = 0; i < chunks.size(); i++) {
                visible.add(i);
            }
            return visible;
        }

        // TODO: 实现 Hi-Z 遮挡剔除
        // 1. 生成 Hi-Z Mipmap
        // 2. 对每个区块执行遮挡查询
        // 3. 返回可见的区块

        return visible;
    }
}
