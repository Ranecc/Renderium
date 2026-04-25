// Renderium v6 Phase 1.1 - 数据桥接层
// MockMinecraft - 测试环境用的模拟 Minecraft 实例

package com.renderium.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * MockMinecraft - 测试环境模拟 Minecraft 实例 🎮
 *
 * <p>在没有真实 Minecraft 运行时的情况下提供模拟数据，
 * 用于单元测试、集成测试和开发调试。
 *
 * <h2>核心功能：</h2>
 * <ul>
 *   <li><b>模拟相机</b>：提供可配置的位置、旋转和 FOV</li>
 *   <li><b>模拟视锥体</b>：生成标准的 6 平面视锥体参数</li>
 *   <li><b>模拟区块</b>：生成指定数量的测试用 ChunkSection 数据</li>
 *   <li><b>模拟渲染状态</b>：FBO、分辨率、帧计数器等</li>
 * </ul>
 *
 * <h2>使用场景：</h2>
 * <ol>
 *   <li><b>单元测试</b>：为 RealDataProvider 提供可预测的测试数据</li>
 *   <li><b>开发调试</b>：在 IDE 中独立运行和调试数据桥接逻辑</li>
 *   <li><b>CI/CD 流水线</b>：无需真实 Minecraft 环境即可运行测试</li>
 *   <li><b>性能基准测试</b>：生成大规模数据集进行性能分析</li>
 * </ol>
 *
 * <h2>预置场景：</h2>
 * <ul>
 *   <li>{@link #createDefaultScene()} - 默认测试场景（标准配置）</li>
 *   <li>{@link #createStressTestScene()} - 压力测试场景（大量区块）</li>
 *   <li>{@link #createEmptyScene()} - 空场景（无数据）</li>
 * </ul>
 *
 * <h3>线程安全：</h3>
 * <p>此类不是线程安全的。如果需要在多线程环境中使用，
 * 应该每个线程创建独立的实例，或者在外部添加同步机制。
 *
 * <h3>资源管理：</h3>
 * <p>实现了 {@link AutoCloseable} 接口，建议使用 try-with-resources 模式：
 * <pre>
 * try (MockMinecraft mock = MockMinecraft.createDefaultScene()) {
 *     RealDataProvider provider = new RealDataProvider();
 *     provider.initialize(mock.getMinecraftInstance());
 *     // ... 测试代码
 * }
 * </pre>
 *
 * @author Renderium Team
 * @version 6.0.0 (Phase 1.1)
 * @since 6.0.0
 * @see RealDataProvider
 */
public class MockMinecraft implements AutoCloseable {

    // ==================== 模拟的 Minecraft 组件 ====================

    /** 模拟的 Minecraft 实例（Object 类型，与真实环境一致） */
    private final Object minecraftInstance;

    /** 模拟的 Camera 对象 */
    private MockCamera mockCamera;

    /** 模拟的 Frustum 对象 */
    private MockFrustum mockFrustum;

    /** 模拟的 LevelRenderer 对象 */
    private MockLevelRenderer mockLevelRenderer;

    /** 模拟的 ClientLevel 对象 */
    private MockClientLevel mockClientLevel;

    /** 模拟的 SectionRenderDispatcher 对象 */
    private MockSectionRenderDispatcher mockSectionDispatcher;

    /** 模拟的 RenderSystem 对象 */
    private MockRenderSystem mockRenderSystem;

    // ==================== 配置参数 ====================

    /** 随机数生成器（用于生成伪随机测试数据） */
    private final Random random;

    /** 是否已关闭 */
    private volatile boolean closed = false;

    /**
     * 私有构造函数
     *
     * <p>通过工厂方法创建实例以确保正确的初始化流程。
     *
     * @param seed 随机种子（用于可重复的测试）
     */
    private MockMinecraft(long seed) {
        this.minecraftInstance = new Object();  // 模拟实例
        this.random = new Random(seed);
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建默认测试场景
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 参数：
     *   - 无
     *
     * 返回值：
     *   - MockMinecraft 实例，包含以下预设数据：
     *     * 相机位置：(100, 64, -200)
     *     相机朝向：pitch=0°, yaw=45°
     *     FOV: 70°
     *     区块数量：27 (3×3×3)
     *     渲染距离：32 chunks
     *     分辨率：1920×1080
     *
     * 使用示例：
     *   try (MockMinecraft mock = MockMinecraft.createDefaultScene()) {
     *       Vec3 pos = mock.getMockCamera().getPosition();
     *       // pos ≈ (100, 64, -200)
     *   }
     * </pre>
     *
     * @return 默认配置的 MockMinecraft 实例
     */
    public static MockMinecraft createDefaultScene() {
        MockMinecraft mock = new MockMinecraft(42L);  // 固定种子确保可重复性

        // 初始化模拟相机（默认位置）
        mock.mockCamera = new MockCamera(
                100.0f, 64.0f, -200.0f,  // x, y, z
                0.0f, 45.0f,              // pitch, yaw (度数)
                70.0f                     // FOV
        );

        // 初始化模拟视锥体（从相机参数自动计算）
        mock.mockFrustum = new MockFrustum(mock.mockCamera, 1920, 1080, 0.1f, 1000.0f);

        // 初始化模拟 ClientLevel（包含 27 个区块）
        mock.mockClientLevel = new MockClientLevel(3, 3, 3, mock.random);

        // 初始化模拟 SectionRenderDispatcher
        mock.mockSectionDispatcher = new MockSectionRenderDispatcher(
                mock.mockClientLevel.getSections()
        );

        // 初始化模拟 LevelRenderer
        mock.mockLevelRenderer = new MockLevelRenderer(
                mock.mockCamera,
                mock.mockFrustum,
                mock.mockSectionDispatcher,
                32  // 渲染距离 (chunks)
        );

        // 初始化模拟 RenderSystem
        mock.mockRenderSystem = new MockRenderSystem(1920, 1080);

        return mock;
    }

    /**
     * 创建压力测试场景（大量区块）
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 返回值：
     *   - MockMinecraft 实例，包含以下压力测试数据：
     *     * 区块数量：3375 (15×15×15)
     *     渲染距离：64 chunks
     *     分辨率：3840×2160 (4K)
     *
     * 适用场景：
     *   - 性能基准测试
     *   - 内存压力测试
     *   - 大规模数据处理验证
     * </pre>
     *
     * @return 压力测试配置的 MockMinecraft 实例
     */
    public static MockMinecraft createStressTestScene() {
        MockMinecraft mock = new MockMinecraft(12345L);  // 不同种子

        // 使用更极端的相机参数
        mock.mockCamera = new MockCamera(
                0.0f, 128.0f, 0.0f,
                -10.0f, 90.0f,
                90.0f  // 更宽 FOV
        );

        mock.mockFrustum = new MockFrustum(mock.mockCamera, 3840, 2160, 0.1f, 2000.0f);

        // 生成大量区块 (15×15×15 = 3375)
        mock.mockClientLevel = new MockClientLevel(15, 15, 15, mock.random);

        mock.mockSectionDispatcher = new MockSectionRenderDispatcher(
                mock.mockClientLevel.getSections()
        );

        mock.mockLevelRenderer = new MockLevelRenderer(
                mock.mockCamera,
                mock.mockFrustum,
                mock.mockSectionDispatcher,
                64  // 更大渲染距离
        );

        mock.mockRenderSystem = new MockRenderSystem(3840, 2160);

        return mock;
    }

    /**
     * 创建空场景（无数据）
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 返回值：
     *   - MockMinecraft 实例，所有组件均为空或零值
     *
     * 适用场景：
     *   - 边界条件测试
     *   - 空指针安全性验证
     *   - 默认值行为测试
     * </pre>
     *
     * @return 空配置的 MockMinecraft 实例
     */
    public static MockMinecraft createEmptyScene() {
        MockMinecraft mock = new MockMinecraft(0L);

        // 所有组件使用最小/零值
        mock.mockCamera = new MockCamera(0, 0, 0, 0, 0, 70);
        mock.mockFrustum = new MockFrustum(mock.mockCamera, 800, 600, 0.1f, 100);
        mock.mockClientLevel = new MockClientLevel(0, 0, 0, mock.random);
        mock.mockSectionDispatcher = new MockSectionRenderDispatcher(new ArrayList<>());
        mock.mockLevelRenderer = new MockLevelRenderer(mock.mockCamera, mock.mockFrustum,
                mock.mockSectionDispatcher, 0);
        mock.mockRenderSystem = new MockRenderSystem(800, 600);

        return mock;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取模拟的 Minecraft 实例
     *
     * <p>返回一个通用的 Object 实例，
     * 与 {@link RealDataProvider#initialize(Object)} 的参数类型兼容。
     *
     * @return Minecraft 实例（实际为 Object）
     * @throws IllegalStateException 如果已关闭
     */
    public Object getMinecraftInstance() {
        ensureNotClosed();
        return minecraftInstance;
    }

    /**
     * 获取模拟的 Camera 对象
     *
     * <h3>返回值说明：</h3>
     * <pre>
     * 类型：MockCamera
     * 包含数据：
     *   - position: Vec3(x, y, z) 世界坐标
     *   - rotation: (pitch, yaw) 弧度
     *   - fov: 视野角度（度数）
     *   - viewMatrix: 4x4 视图矩阵
     *   - projectionMatrix: 4x4 投影矩阵
     * </pre>
     *
     * @return MockCamera 实例
     * @throws IllegalStateException 如果已关闭
     */
    public MockCamera getMockCamera() {
        ensureNotClosed();
        return mockCamera;
    }

    /**
     * 获取模拟的 Frustum 对象
     *
     * <h3>返回值说明：</h3>
     * <pre>
     * 类型：MockFrustum
     * 包含数据：
     *   - planes[24]: 6 平面 × 4 参数
     *   - 可见性测试方法
     * </pre>
     *
     * @return MockFrustum 实例
     * @throws IllegalStateException 如果已关闭
     */
    public MockFrustum getMockFrustum() {
        ensureNotClosed();
        return mockFrustum;
    }

    /**
     * 获取模拟的 LevelRenderer 对象
     *
     * @return MockLevelRenderer 实例
     * @throws IllegalStateException 如果已关闭
     */
    public MockLevelRenderer getMockLevelRenderer() {
        ensureNotClosed();
        return mockLevelRenderer;
    }

    /**
     * 获取模拟的 ClientLevel 对象
     *
     * @return MockClientLevel 实例
     * @throws IllegalStateException 如果已关闭
     */
    public MockClientLevel getMockClientLevel() {
        ensureNotClosed();
        return mockClientLevel;
    }

    /**
     * 获取模拟的 SectionRenderDispatcher 对象
     *
     * @return MockSectionRenderDispatcher 实例
     * @throws IllegalStateException 如果已关闭
     */
    public MockSectionRenderDispatcher getMockSectionDispatcher() {
        ensureNotClosed();
        return mockSectionDispatcher;
    }

    /**
     * 获取模拟的 RenderSystem 对象
     *
     * @return MockRenderSystem 实例
     * @throws IllegalStateException 如果已关闭
     */
    public MockRenderSystem getMockRenderSystem() {
        ensureNotClosed();
        return mockRenderSystem;
    }

    /**
     * 生成指定数量的模拟区块
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - count: 要生成的区块数量（必须 >= 0）
     *
     * 返回值：
     *   - List&lt;MockChunkSection&gt;: 模拟区块列表
     *     每个区块包含：
     *       * position: [x, y, z] 区块坐标
     *       * distanceToCamera: 到相机的距离
     *       * boundingRadius: 包围球半径（固定 13.86 = 8√3）
     *       * blockStates: 16×16×16 方块状态数组
     *
     * 异常：
     *   - IllegalArgumentException: count < 0
     *
     * 使用示例：
     *   List&lt;MockChunkSection&gt; chunks = mock.generateMockChunks(100);
     *   // chunks.size() == 100
     * </pre>
     *
     * @param count 区块数量
     * @return 模拟区块列表
     * @throws IllegalArgumentException 如果数量为负数
     * @throws IllegalStateException 如果已关闭
     */
    public List<MockChunkSection> generateMockChunks(int count) {
        ensureNotClosed();

        if (count < 0) {
            throw new IllegalArgumentException("区块数量不能为负数: " + count);
        }

        List<MockChunkSection> chunks = new ArrayList<>(count);
        Vec3 cameraPos = mockCamera.getPosition();

        for (int i = 0; i < count; i++) {
            // 在相机周围随机分布区块
            int chunkX = (int) (cameraPos.x / 16.0f) + random.nextInt(21) - 10;  // ±10 chunks
            int chunkY = (int) (cameraPos.y / 16.0f) + random.nextInt(9) - 4;    // ±4 chunks
            int chunkZ = (int) (cameraPos.z / 16.0f) + random.nextInt(21) - 10;

            MockChunkSection section = new MockChunkSection(chunkX, chunkY, chunkZ, cameraPos, random);
            chunks.add(section);
        }

        return chunks;
    }

    // ==================== 资源管理 ====================

    /**
     * 关闭 MockMinecraft 并释放资源
     *
     * <p>关闭后调用任何 getter 方法将抛出 IllegalStateException。
     */
    @Override
    public void close() {
        this.closed = true;
    }

    /**
     * 检查是否已关闭
     *
     * @return true 如果已关闭
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 确保实例未关闭
     *
     * @throws IllegalStateException 如果已关闭
     */
    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("MockMinecraft 已关闭，无法访问");
        }
    }

    // ==================== 内部模拟类 ====================

    /**
     * 模拟的三维向量类
     * <p>简化版 Vec3 实现，仅用于测试环境
     */
    public static class Vec3 {
        public final float x, y, z;

        public Vec3(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /**
         * 计算到另一个向量的距离
         *
         * @param other 另一个向量
         * @return 欧几里得距离
         */
        public float distanceTo(Vec3 other) {
            float dx = x - other.x;
            float dy = y - other.y;
            float dz = z - other.z;
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public String toString() {
            return String.format("(%.2f, %.2f, %.2f)", x, y, z);
        }
    }

    /**
     * 模拟的相机类
     */
    public static class MockCamera {
        private final Vec3 position;
        private final float pitch;  // 弧度
        private final float yaw;    // 弧度
        private final float fov;    // 度数
        private final float[] viewMatrix;
        private final float[] projectionMatrix;

        /**
         * 创建模拟相机
         *
         * @param x, y, z      世界坐标
         * @param pitch, yaw   旋转角度（度数）
         * @param fov          视野角度（度数）
         */
        public MockCamera(float x, float y, float z, float pitch, float yaw, float fov) {
            this.position = new Vec3(x, y, z);
            this.pitch = (float) Math.toRadians(pitch);
            this.yaw = (float) Math.toRadians(yaw);
            this.fov = fov;
            this.viewMatrix = buildViewMatrix(x, y, z, this.pitch, this.yaw);
            this.projectionMatrix = buildProjectionMatrix(fov, 1920.0f / 1080.0f, 0.1f, 1000.0f);
        }

        public Vec3 getPosition() { return position; }
        public float getPitch() { return pitch; }
        public float getYaw() { return yaw; }
        public float getFov() { return fov; }
        public float[] getViewMatrix() { return viewMatrix.clone(); }
        public float[] getProjectionMatrix() { return projectionMatrix.clone(); }

        /**
         * 构建视图矩阵（简化版）
         */
        private float[] buildViewMatrix(float x, float y, float z, float pitch, float yaw) {
            float[] matrix = new float[16];
            // 单位矩阵基础
            matrix[0] = 1.0f; matrix[5] = 1.0f; matrix[10] = 1.0f; matrix[15] = 1.0f;

            // 应用旋转变换（简化实现）
            float cosPitch = (float) Math.cos(pitch);
            float sinPitch = (float) Math.sin(pitch);
            float cosYaw = (float) Math.cos(yaw);
            float sinYaw = (float) Math.sin(yaw);

            matrix[0] = cosYaw;
            matrix[1] = sinPitch * sinYaw;
            matrix[2] = -cosPitch * sinYaw;
            matrix[4] = 0.0f;
            matrix[5] = cosPitch;
            matrix[6] = sinPitch;
            matrix[8] = sinYaw;
            matrix[9] = -sinPitch * cosYaw;
            matrix[10] = cosPitch * cosYaw;

            // 应用平移变换
            matrix[12] = -(matrix[0] * x + matrix[4] * y + matrix[8] * z);
            matrix[13] = -(matrix[1] * x + matrix[5] * y + matrix[9] * z);
            matrix[14] = -(matrix[2] * x + matrix[6] * y + matrix[10] * z);

            return matrix;
        }

        /**
         * 构建投影矩阵（透视投影）
         */
        private float[] buildProjectionMatrix(float fov, float aspect, float near, float far) {
            float[] matrix = new float[16];
            float f = 1.0f / (float) Math.tan(Math.toRadians(fov) / 2.0f);

            matrix[0] = f / aspect;
            matrix[5] = f;
            matrix[10] = (far + near) / (near - far);
            matrix[11] = -1.0f;
            matrix[14] = (2.0f * far * near) / (near - far);

            return matrix;
        }
    }

    /**
     * 模拟的视锥体类
     */
    public static class MockFrustum {
        private final float[] planes;  // 24 元素：6 平面 × 4 参数

        /**
         * 从相机参数构建视锥体
         */
        public MockFrustum(MockCamera camera, int width, int height, float near, float far) {
            float aspect = (float) width / (float) height;
            float[] vpMatrix = multiplyMatrices(camera.getProjectionMatrix(), camera.getViewMatrix());
            this.planes = extractPlanes(vpMatrix);
        }

        public float[] getPlanes() { return planes.clone(); }

        /**
         * 从 VP 矩阵提取平面（复用 InterceptionCullingContext 的算法）
         */
        private float[] extractPlanes(float[] vp) {
            float[] p = new float[24];

            // 左平面
            p[0] = vp[3] + vp[0]; p[1] = vp[7] + vp[4];
            p[2] = vp[11] + vp[8]; p[3] = vp[15] + vp[12];
            // 右平面
            p[4] = vp[3] - vp[0]; p[5] = vp[7] - vp[4];
            p[6] = vp[11] - vp[8]; p[7] = vp[15] - vp[12];
            // 下平面
            p[8] = vp[3] + vp[1]; p[9] = vp[7] + vp[5];
            p[10] = vp[11] + vp[9]; p[11] = vp[15] + vp[13];
            // 上平面
            p[12] = vp[3] - vp[1]; p[13] = vp[7] - vp[5];
            p[14] = vp[11] - vp[9]; p[15] = vp[15] - vp[13];
            // 近平面
            p[16] = vp[3] + vp[2]; p[17] = vp[7] + vp[6];
            p[18] = vp[11] + vp[10]; p[19] = vp[15] + vp[14];
            // 远平面
            p[20] = vp[3] - vp[2]; p[21] = vp[7] - vp[6];
            p[22] = vp[11] - vp[10]; p[23] = vp[15] - vp[14];

            // 归一化
            for (int i = 0; i < 6; i++) {
                int base = i * 4;
                float len = (float) Math.sqrt(p[base]*p[base] + p[base+1]*p[base+1] + p[base+2]*p[base+2]);
                if (len > 1e-6f) {
                    float inv = 1.0f / len;
                    p[base] *= inv; p[base+1] *= inv; p[base+2] *= inv; p[base+3] *= inv;
                }
            }

            return p;
        }

        private float[] multiplyMatrices(float[] left, float[] right) {
            float[] result = new float[16];
            for (int col = 0; col < 4; col++)
                for (int row = 0; row < 4; row++) {
                    float sum = 0;
                    for (int k = 0; k < 4; k++)
                        sum += left[k*4+row] * right[col*4+k];
                    result[col*4+row] = sum;
                }
            return result;
        }
    }

    /**
     * 模拟的区块段（ChunkSection）
     */
    public static class MockChunkSection {
        private final int chunkX, chunkY, chunkZ;
        private final Vec3 centerPosition;
        private final float distanceToCamera;
        private final float boundingRadius;
        private final int[] blockStates;  // 4096 个方块 (16^3)

        /**
         * 创建模拟区块段
         *
         * @param cx, cy, cz  区块坐标
         * @param cameraPos   相机位置（用于计算距离）
         * @param rand        随机数生成器
         */
        public MockChunkSection(int cx, int cy, int cz, Vec3 cameraPos, Random rand) {
            this.chunkX = cx;
            this.chunkY = cy;
            this.chunkZ = cz;

            // 计算区块中心世界坐标（每个区块 16×16×16 格）
            this.centerPosition = new Vec3(
                    cx * 16.0f + 8.0f,
                    cy * 16.0f + 8.0f,
                    cz * 16.0f + 8.0f
            );

            // 计算到相机的距离
            this.distanceToCamera = centerPosition.distanceTo(cameraPos);

            // 区块包围球半径（对角线一半 = 8√3 ≈ 13.856）
            this.boundingRadius = 13.85640646f;

            // 生成方块状态（简化版：随机填充 0-255）
            this.blockStates = new int[4096];  // 16*16*16
            for (int i = 0; i < blockStates.length; i++) {
                blockStates[i] = rand.nextInt(256);  // 模拟方块 ID
            }
        }

        public int getChunkX() { return chunkX; }
        public int getChunkY() { return chunkY; }
        public int getChunkZ() { return chunkZ; }
        public Vec3 getCenterPosition() { return centerPosition; }
        public float getDistanceToCamera() { return distanceToCamera; }
        public float getBoundingRadius() { return boundingRadius; }
        public int[] getBlockStates() { return blockStates.clone(); }

        /**
         * 导出为 LOD 计算所需的格式 [index, distance, radius]
         *
         * @return 3 元素 float 数组
         */
        public float[] toSectionData(int index) {
            return new float[]{index, distanceToCamera, boundingRadius};
        }

        /**
         * 导出位置数据 [x, y, z]
         *
         * @return 3 元素 float 数组
         */
        public float[] toPositionData() {
            return new float[]{centerPosition.x, centerPosition.y, centerPosition.z};
        }
    }

    /**
     * 模拟的客户端世界
     */
    public static class MockClientLevel {
        private final List<MockChunkSection> sections;

        public MockClientLevel(int sizeX, int sizeY, int sizeZ, Random rand) {
            sections = new ArrayList<>(sizeX * sizeY * sizeZ);

            // 默认相机位置（用于计算距离）
            Vec3 defaultCamera = new Vec3(100.0f, 64.0f, -200.0f);

            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    for (int z = 0; z < sizeZ; z++) {
                        sections.add(new MockChunkSection(x, y, z, defaultCamera, rand));
                    }
                }
            }
        }

        public List<MockChunkSection> getSections() { return new ArrayList<>(sections); }
    }

    /**
     * 模拟的区块渲染分发器
     */
    public static class MockSectionRenderDispatcher {
        private final List<MockChunkSection> sections;

        public MockSectionRenderDispatcher(List<MockChunkSection> sections) {
            this.sections = new ArrayList<>(sections);
        }

        public List<MockChunkSection> getVisibleSections() { return new ArrayList<>(sections); }
        public int getSectionCount() { return sections.size(); }
    }

    /**
     * 模拟的主渲染器
     */
    public static class MockLevelRenderer {
        private final MockCamera camera;
        private final MockFrustum frustum;
        private final MockSectionRenderDispatcher dispatcher;
        private final int renderDistance;

        public MockLevelRenderer(MockCamera camera, MockFrustum frustum,
                                 MockSectionRenderDispatcher dispatcher, int renderDistance) {
            this.camera = camera;
            this.frustum = frustum;
            this.dispatcher = dispatcher;
            this.renderDistance = renderDistance;
        }

        public MockCamera getCamera() { return camera; }
        public MockFrustum getFrustum() { return frustum; }
        public MockSectionRenderDispatcher getDispatcher() { return dispatcher; }
        public int getRenderDistance() { return renderDistance; }
    }

    /**
     * 模拟的渲染系统
     */
    public static class MockRenderSystem {
        private final int width;
        private final int height;
        private int frameIndex;

        public MockRenderSystem(int width, int height) {
            this.width = width;
            this.height = height;
            this.frameIndex = 0;
        }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int[] getResolution() { return new int[]{width, height}; }
        public int getCurrentFBOId() { return 1; }  // 模拟 FBO
        public long getFrameIndex() { return frameIndex; }
        public void advanceFrame() { frameIndex++; }
    }
}
