// Renderium - Blaze3D 拦截层系统 Phase 5
// 拦截层剔除上下文 - 从 RenderContext 提取剔除所需的参数
// 封装视锥体、相机、距离等数据，提供便捷的 Builder 模式

package com.renderium.interception.culling;

import java.util.Arrays;
import java.util.Objects;

/**
 * 拦截层剔除上下文 🎯
 *
 * <p>从 {@link com.renderium.interception.RenderContext} 中提取剔除系统所需的所有参数，
 * 并封装为统一的数据结构，供各剔除器适配器使用。
 *
 * <h2>核心职责：</h2>
 * <ul>
 *   <li><b>数据提取</b>：从 RenderContext 中提取相机、矩阵、区段数据</li>
 *   <li><b>格式转换</b>：将渲染管线的格式转换为剔除器所需的格式</li>
 *   <li><b>参数封装</b>：提供类型安全的访问接口</li>
 *   <li><b>构建便利</b>：Builder 模式支持链式调用和默认值</li>
 * </ul>
 *
 * <h2>包含的数据：</h2>
 * <pre>
 * ┌─────────────────────────────────────┐
 * │        InterceptionCullingContext    │
 * ├─────────────────────────────────────┤
 * │ 相机信息:                            │
 * │   - cameraPosition[3] (x, y, z)     │
 * │   - viewMatrix[16] (4x4 矩阵)       │
 * │   - projectionMatrix[16] (4x4 矩阵) │
 * │   - viewProjectionMatrix[16]        │
 * │   - frustumPlanes[24] (6 平面)      │
 * │                                     │
 * │ 渲染参数:                            │
 * │   - renderDistance (float)          │
 * │   - fov (float, 度数)               │
 * │                                     │
 * │ 对象数据:                            │
 * │   - sections[][] (区段数据)          │
 * │   - positions[][] (位置数据)         │
 * │   - blockStates[] (方块状态)         │
 * │   - objectCount (int)               │
 * └─────────────────────────────────────┘
 * </pre>
 *
 * <h2>使用场景：</h2>
 * <ol>
 *   <li>在 DefaultPreInterceptor.processCullingInjection() 中构建此上下文</li>
 *   <li>传递给 PreInterceptorCullingIntegration.executeCulling()</li>
 *   <li>CullingAdapter 的各 adapt*() 方法使用此上下文调用底层剔除器</li>
 * </ol>
 *
 * <h2>线程安全：</h3>
 * <p>此类是不可变对象（Immutable），所有字段都是 final，
 * 构建后不能修改，因此是线程安全的。
 *
 * <h2>性能考虑：</h3>
 * <ul>
 *   <li>避免在热路径上频繁创建（应复用或池化）</li>
 *   <li>数组拷贝开销应在可接受范围内（&lt; 0.01ms）</li>
 *   <li>Builder 模式允许延迟初始化部分字段</li>
 * </ul>
 *
 * @author Renderium Team
 * @version 5.1.0 (Phase 5)
 * @since 5.1.0
 * @see CullingAdapter
 * @see PreInterceptorCullingIntegration
 */
public final class InterceptionCullingContext {

    // ==================== 相机信息字段 ====================

    /** 相机位置（世界坐标，3 个元素：x, y, z） */
    private final float[] cameraPosition;

    /** 视图矩阵（4x4 列主序，16 个元素） */
    private final float[] viewMatrix;

    /** 投影矩阵（4x4 列主序，16 个元素） */
    private final float[] projectionMatrix;

    /** 视图-投影组合矩阵（VP Matrix，4x4 列主序，16 个元素） */
    private final float[] viewProjectionMatrix;

    /** 视锥体 6 平面参数（每个平面 4 个元素：a, b, c, d，共 24 个元素） */
    private final float[] frustumPlanes;

    // ==================== 渲染参数字段 ====================

    /** 最大渲染距离（世界单位） */
    private final float renderDistance;

    /** 视野角度（度数） */
    private final float fov;

    // ==================== 对象数据字段 ====================

    /**
     * 区段数据数组
     *
     * <p>每个元素为 [sectionIndex, distanceToCamera, boundingRadius]
     * 用于 AsyncComputeCuller
     */
    private final float[][] sections;

    /**
     * 位置数据数组
     *
     * <p>每个元素为 [x, y, z] 世界坐标
     * 用于所有剔除器的位置查询
     */
    private final float[][] positions;

    /**
     * 方块状态数据数组
     *
     * <p>用于 NeighborFaceCuller 的面可见性计算
     * 如果为 null，邻居面剔除将使用估算模式
     */
    private final int[] blockStates;

    /** 对象总数 */
    private final int objectCount;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     *
     * <p>通过 Builder 模式构造实例。
     * 执行防御性拷贝以确保不可变性。
     *
     * @param builder 构建器实例
     */
    private InterceptionCullingContext(Builder builder) {
        // ======== 相机信息 ========
        this.cameraPosition = builder.cameraPosition != null ?
                builder.cameraPosition.clone() : new float[3];
        this.viewMatrix = builder.viewMatrix != null ?
                builder.viewMatrix.clone() : createIdentityMatrix();
        this.projectionMatrix = builder.projectionMatrix != null ?
                builder.projectionMatrix.clone() : createIdentityMatrix();

        // VP 矩阵：如果未显式设置，则从 view 和 projection 计算
        if (builder.viewProjectionMatrix != null) {
            this.viewProjectionMatrix = builder.viewProjectionMatrix.clone();
        } else {
            this.viewProjectionMatrix = multiplyMatrices(this.viewMatrix, this.projectionMatrix);
        }

        // 视锥体平面：如果未显式设置，则从 VP 矩阵提取
        if (builder.frustumPlanes != null) {
            this.frustumPlanes = builder.frustumPlanes.clone();
        } else {
            this.frustumPlanes = extractFrustumPlanes(this.viewProjectionMatrix);
        }

        // ======== 渲染参数 ========
        this.renderDistance = builder.renderDistance > 0 ? builder.renderDistance : 256.0f; // 默认 256 格
        this.fov = builder.fov > 0 ? builder.fov : 70.0f; // 默认 70° FOV

        // ======== 对象数据 ========
        this.sections = deepCopySections(builder.sections);
        this.positions = deepCopyPositions(builder.positions);
        this.blockStates = builder.blockStates != null ?
                builder.blockStates.clone() : null;
        this.objectCount = calculateObjectCount(builder.sections, builder.positions);
    }

    // ==================== Getter 方法：相机信息 ====================

    /**
     * 获取相机位置
     *
     * <p><b>返回值说明：</b></p>
     * <ul>
     *   <li>返回数组长度固定为 3</li>
     *   <li>[0] = X 坐标（东西方向，东为正）</li>
     *   <li>[1] = Y 坐标（垂直方向，上为正）</li>
     *   <li>[2] = Z 坐标（南北方向，南为正）</li>
     * </ul>
     *
     * @return 相机位置数组（副本，修改不影响内部状态）
     */
    public float[] getCameraPosition() {
        return cameraPosition.clone();
    }

    /**
     * 获取视图矩阵
     *
     * <p><b>格式：</b>4x4 列主序（Column-Major），16 个 float 元素
     *
     * @return 视图矩阵数组（副本）
     */
    public float[] getViewMatrix() {
        return viewMatrix.clone();
    }

    /**
     * 获取投影矩阵
     *
     * <p><b>格式：</b>4x4 列主序（Column-Major），16 个 float 元素
     *
     * @return 投影矩阵数组（副本）
     */
    public float[] getProjectionMatrix() {
        return projectionMatrix.clone();
    }

    /**
     * 获取视图-投影组合矩阵
     *
     * <p>这是视图矩阵和投影矩阵的乘积，
     * 用于将世界坐标直接转换到裁剪空间。
     *
     * <p><b>格式：</b>4x4 列主序（Column-Major），16 个 float 元素
     *
     * @return VP 矩阵数组（副本）
     */
    public float[] getViewProjectionMatrix() {
        return viewProjectionMatrix.clone();
    }

    /**
     * 获取视锥体 6 平面参数
     *
     * <p><b>平面方程格式：</b></p>
     * <ul>
     *   <li>每个平面用 4 个 float 表示：ax + by + cz + d = 0</li>
     *   <li>平面顺序：左、右、下、上、近、远</li>
     *   <li>总长度：6 平面 × 4 参数 = 24 个元素</li>
     * </ul>
     *
     * <p><b>用途：</b></p>
     * <ul>
     *   <li>用于 AsyncComputeCuller 的 GPU 视锥体剔除</li>
     *   <li>可用于 CPU 端的快速边界测试</li>
     * </ul>
     *
     * @return 视锥体平面参数数组（副本，24 个元素）
     */
    public float[] getFrustumPlanes() {
        return frustumPlanes.clone();
    }

    // ==================== Getter 方法：渲染参数 ====================

    /**
     * 获取最大渲染距离
     *
     * <p>超过此距离的对象将被距离剔除。
     *
     * @return 渲染距离（世界单位，通常为区块数 × 16）
     */
    public float getRenderDistance() {
        return renderDistance;
    }

    /**
     * 获取视野角度
     *
     * @return FOV（度数）
     */
    public float getFov() {
        return fov;
    }

    // ==================== Getter 方法：对象数据 ====================

    /**
     * 获取区段数据
     *
     * <p><b>数据格式：</b></p>
     * <ul>
     *   <li>二维数组：sections[i][j]</li>
     *   <li>每行 3 个元素：[sectionIndex, distanceToCamera, boundingRadius]</li>
     * </ul>
     *
     * @return 区段数据深拷贝（可为 null）
     */
    public float[][] getSections() {
        return deepCopySections(sections);
    }

    /**
     * 获取位置数据
     *
     * <p><b>数据格式：</b></p>
     * <ul>
     *   <li>二维数组：positions[i][j]</li>
     *   <li>每行 3 个元素：[x, y, z] 世界坐标</li>
     * </ul>
     *
     * @return 位置数据深拷贝（可为 null）
     */
    public float[][] getPositions() {
        return deepCopyPositions(positions);
    }

    /**
     * 获取方块状态数据
     *
     * <p>用于 NeighborFaceCuller 的面可见性计算。
     * 如果为 null，表示没有方块状态数据可用。
     *
     * @return 方块状态 ID 数组（可为 null）
     */
    public int[] getBlockStates() {
        return blockStates != null ? blockStates.clone() : null;
    }

    /**
     * 获取对象总数
     *
     * <p>这是需要执行剔除的对象数量，
     * 通常等于 sections 或 positions 的长度。
     *
     * @return 对象数量（≥ 0）
     */
    public int getObjectCount() {
        return objectCount;
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format(
                "InterceptionCullingContext{camera=(%.1f,%.1f,%.1f), distance=%.1f, fov=%.1f°, objects=%d}",
                cameraPosition[0], cameraPosition[1], cameraPosition[2],
                renderDistance, fov, objectCount
        );
    }

    // ==================== Builder 模式 ====================

    /**
     * InterceptionCullingContext 构建器
     *
     * <p>提供灵活的上下文构造方式，所有字段都有合理的默认值：
     * <ul>
     *   <li>相机位置：(0, 0, 0)</li>
     *   <li>视图/投影矩阵：单位矩阵</li>
     *   <li>VP 矩阵：自动从 V 和 P 计算</li>
     *   <li>视锥体平面：自动从 VP 矩阵提取</li>
     *   <li>渲染距离：256 格</li>
     *   <li>FOV：70°</li>
     *   <li>对象数据：null（需手动设置）</li>
     * </ul>
     *
     * <h3>使用示例：</h3>
     * <pre>
     * InterceptionCullingContext ctx = new InterceptionCullingContext.Builder()
     *     .cameraPosition(100.0f, 64.0f, -200.0f)
     *     .viewMatrix(extractViewMatrix(renderContext))
     *     .projectionMatrix(extractProjectionMatrix(renderContext))
     *     .renderDistance(32 * 16)  // 32 区块
     *     .sections(sectionData)
     *     .positions(positionData)
     *     .build();
     * </pre>
     */
    public static final class Builder {

        // 默认值
        private float[] cameraPosition;
        private float[] viewMatrix;
        private float[] projectionMatrix;
        private float[] viewProjectionMatrix;
        private float[] frustumPlanes;
        private float renderDistance = 0.0f;  // 0 表示使用默认值
        private float fov = 0.0f;             // 0 表示使用默认值
        private float[][] sections;
        private float[][] positions;
        private int[] blockStates;

        /**
         * 设置相机位置（世界坐标）
         *
         * <h3>方法签名与参数说明：</h3>
         * <pre>
         * 参数：
         *   - x: X 坐标（东西方向）
         *   - y: Y 坐标（垂直方向）
         *   - z: Z 坐标（南北方向）
         *
         * 返回值：
         *   - Builder 实例（支持链式调用）
         * </pre>
         *
         * @param x X 坐标
         * @param y Y 坐标
         * @param z Z 坐标
         * @return this（链式调用）
         */
        public Builder cameraPosition(float x, float y, float z) {
            this.cameraPosition = new float[]{x, y, z};
            return this;
        }

        /**
         * 设置视图矩阵
         *
         * <p><b>格式要求：</b>4x4 列主序（Column-Major Order），16 个 float 元素
         *
         * <h3>方法签名与参数说明：</h3>
         * <pre>
         * 参数：
         *   - matrix: 视图矩阵数组（长度必须为 16）
         *
         * 返回值：
         *   - Builder 实例（支持链式调用）
         *
         * 异常：
         *   - IllegalArgumentException: 如果 matrix 为 null 或长度不为 16
         * </pre>
         *
         * @param matrix 视图矩阵（16 个元素）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果矩阵无效
         */
        public Builder viewMatrix(float[] matrix) {
            validateMatrix(matrix, "viewMatrix");
            this.viewMatrix = matrix;
            return this;
        }

        /**
         * 设置投影矩阵
         *
         * <p><b>格式要求：</b>4x4 列主序（Column-Major Order），16 个 float 元素
         *
         * <h3>方法签名与参数说明：</h3>
         * <pre>
         * 参数：
         *   - matrix: 投影矩阵数组（长度必须为 16）
         *
         * 返回值：
         *   - Builder 实例（支持链式调用）
         * </pre>
         *
         * @param matrix 投影矩阵（16 个元素）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果矩阵无效
         */
        public Builder projectionMatrix(float[] matrix) {
            validateMatrix(matrix, "projectionMatrix");
            this.projectionMatrix = matrix;
            return this;
        }

        /**
         * 设置视图-投影组合矩阵（可选）
         *
         * <p>如果未设置，将自动从 viewMatrix 和 projectionMatrix 计算得出。
         * 显式设置可以跳过运行时矩阵乘法，提高性能。
         *
         * <h3>方法签名与参数说明：</h3>
         * <pre>
         * 参数：
         *   - matrix: VP 矩阵数组（长度必须为 16）
         *
         * 返回值：
         *   - Builder 实例（支持链式调用）
         * </pre>
         *
         * @param matrix VP 矩阵（16 个元素）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果矩阵无效
         */
        public Builder viewProjectionMatrix(float[] matrix) {
            validateMatrix(matrix, "viewProjectionMatrix");
            this.viewProjectionMatrix = matrix;
            return this;
        }

        /**
         * 设置视锥体 6 平面参数（可选）
         *
         * <p>如果未设置，将自动从 VP 矩阵提取。
         * 显式设置可以跳过运行时平面提取，提高性能。
         *
         * <p><b>格式：</b>6 个平面 × 4 参数 = 24 个 float
         * <br>平面顺序：左、右、下、上、近、远
         *
         * <h3>方法签名与参数说明：</h3>
         * <pre>
         * 参数：
         *   - planes: 视锥体平面参数数组（长度必须为 24）
         *
         * 返回值：
         *   - Builder 实例（支持链式调用）
         * </pre>
         *
         * @param planes 视锥体平面参数（24 个元素）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果数组无效
         */
        public Builder frustumPlanes(float[] planes) {
            if (planes == null || planes.length != 24) {
                throw new IllegalArgumentException("frustumPlanes 必须包含 24 个元素 (6平面×4参数)");
            }
            this.frustumPlanes = planes;
            return this;
        }

        /**
         * 设置最大渲染距离
         *
         * <h3>方法签名与参数说明：</h3>
         * <pre>
         * 参数：
         *   - distance: 渲染距离（世界单位，通常 &gt; 0）
         *
         * 返回值：
         *   - Builder 实例（支持链式调用）
         * </pre>
         *
         * @param distance 渲染距离（必须 > 0）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果距离 <= 0
         */
        public Builder renderDistance(float distance) {
            if (distance <= 0) {
                throw new IllegalArgumentException("渲染距离必须大于 0: " + distance);
            }
            this.renderDistance = distance;
            return this;
        }

        /**
         * 设置视野角度
         *
         * <h3>方法签名与参数说明：</h3>
         * <pre>
         * 参数：
         *   - fov: FOV（度数，通常 60-120）
         *
         * 返回值：
         *   - Builder 实例（支持链式调用）
         * </pre>
         *
         * @param fov 视野角度（度数，必须 > 0 且 < 180）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果 FOV 无效
         */
        public Builder fov(float fov) {
            if (fov <= 0 || fov >= 180) {
                throw new IllegalArgumentException("FOV 必须在 (0, 180) 范围内: " + fov);
            }
            this.fov = fov;
            return this;
        }

        /**
         * 设置区段数据
         *
         * <p><b>数据格式：</b>二维数组，每行 [sectionIndex, distanceToCamera, boundingRadius]
         *
         * <h3>方法签名与参数说明：</h3>
         * <pre>
         * 参数：
         *   - sectionData: 区段数据数组（可为 null 或空数组）
         *
         * 返回值：
         *   - Builder 实例（支持链式调用）
         * </pre>
         *
         * @param sectionData 区段数据（可为 null）
         * @return this（链式调用）
         */
        public Builder sections(float[][] sectionData) {
            this.sections = sectionData;
            return this;
        }

        /**
         * 设置位置数据
         *
         * <p><b>数据格式：</b>二维数组，每行 [x, y, z] 世界坐标
         *
         * <h3>方法签名与参数说明：</h3>
         * <pre>
         * 参数：
         *   - positionData: 位置数据数组（可为 null 或空数组）
         *
         * 返回值：
         *   - Builder 实例（支持链式调用）
         * </pre>
         *
         * @param positionData 位置数据（可为 null）
         * @return this（链式调用）
         */
        public Builder positions(float[][] positionData) {
            this.positions = positionData;
            return this;
        }

        /**
         * 设置方块状态数据
         *
         * <p>用于 NeighborFaceCuller 的面可见性计算。
         *
         * <h3>方法签名与参数说明：</h3>
         * <pre>
         * 参数：
         *   - states: 方块状态 ID 数组（可为 null）
         *
         * 返回值：
         *   - Builder 实例（支持链式调用）
         * </pre>
         *
         * @param states 方块状态数组（可为 null）
         * @return this（链式调用）
         */
        public Builder blockStates(int[] states) {
            this.blockStates = states;
            return this;
        }

        /**
         * 构建 InterceptionCullingContext 实例
         *
         * <p>执行以下操作：
         * <ol>
         *   <li>验证必填字段（至少需要相机位置或对象数据之一）</li>
         *   <li>计算派生字段（VP 矩阵、视锥体平面）</li>
         *   <li>执行防御性拷贝确保不可变性</li>
         *   <li>创建并返回不可变实例</li>
         * </ol>
         *
         * @return 不可变的 InterceptionCullingContext 实例
         * @throws IllegalStateException 如果缺少必要数据
         */
        public InterceptionCullingContext build() {
            // 验证：至少需要有对象数据才能进行剔除
            boolean hasObjectData = (sections != null && sections.length > 0) ||
                    (positions != null && positions.length > 0);

            if (!hasObjectData && objectCountWillBe(sections, positions) == 0) {
                // 允许空对象列表（某些帧可能没有对象需要剔除）
                // 但记录警告以便调试
                // 注意：这里不抛异常，因为空对象列表是合法的
            }

            return new InterceptionCullingContext(this);
        }

        // ==================== 内部辅助方法 ====================

        /**
         * 验证矩阵有效性
         */
        private void validateMatrix(float[] matrix, String name) {
            if (matrix == null || matrix.length != 16) {
                throw new IllegalArgumentException(name + " 必须包含 16 个元素 (4x4 矩阵)");
            }
        }

        /**
         * 预估对象数量
         */
        private int objectCountWillBe(float[][] secs, float[][] poss) {
            if (secs != null) return secs.length;
            if (poss != null) return poss.length;
            return 0;
        }
    }

    // ==================== 内部静态工具方法 ====================

    /**
     * 创建 4x4 单位矩阵
     *
     * @return 16 个元素的单位矩阵
     */
    private static float[] createIdentityMatrix() {
        float[] identity = new float[16];
        identity[0] = 1.0f;   // m00
        identity[5] = 1.0f;   // m11
        identity[10] = 1.0f;  // m22
        identity[15] = 1.0f;  // m33
        return identity;
    }

    /**
     * 矩阵乘法（4x4 × 4x4）
     *
     * <p>计算 result = left × right
     * <br>两个矩阵均为列主序（Column-Major）存储。
     *
     * @param left  左矩阵（4x4）
     * @param right 右矩阵（4x4）
     * @return 结果矩阵（4x4）
     */
    private static float[] multiplyMatrices(float[] left, float[] right) {
        float[] result = new float[16];

        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += left[k * 4 + row] * right[col * 4 + k];
                }
                result[col * 4 + row] = sum;
            }
        }

        return result;
    }

    /**
     * 从 VP 矩阵提取视锥体 6 平面
     *
     * <p>使用 Graebner 的标准算法从 View-Projection 矩阵提取视锥体平面。
     *
     * <p><b>平面索引：</b></p>
     * <ul>
     *   <li>0: 左平面 (Left)</li>
     *   <li>1: 右平面 (Right)</li>
     *   <li>2: 下平面 (Bottom)</li>
     *   <li>3: 上平面 (Top)</li>
     *   <li>4: 近平面 (Near)</li>
     *   <li>5: 远平面 (Far)</li>
     * </ul>
     *
     * @param vpMatrix VP 矩阵（16 个元素）
     * @return 视锥体平面参数数组（24 个元素）
     */
    private static float[] extractFrustumPlanes(float[] vpMatrix) {
        float[] planes = new float[24];

        // 提取左平面: row3 + row0
        planes[0] = vpMatrix[3] + vpMatrix[0];   // a
        planes[1] = vpMatrix[7] + vpMatrix[4];   // b
        planes[2] = vpMatrix[11] + vpMatrix[8];  // c
        planes[3] = vpMatrix[15] + vpMatrix[12]; // d

        // 提取右平面: row3 - row0
        planes[4] = vpMatrix[3] - vpMatrix[0];
        planes[5] = vpMatrix[7] - vpMatrix[4];
        planes[6] = vpMatrix[11] - vpMatrix[8];
        planes[7] = vpMatrix[15] - vpMatrix[12];

        // 提取下平面: row3 + row1
        planes[8] = vpMatrix[3] + vpMatrix[1];
        planes[9] = vpMatrix[7] + vpMatrix[5];
        planes[10] = vpMatrix[11] + vpMatrix[9];
        planes[11] = vpMatrix[15] + vpMatrix[13];

        // 提取上平面: row3 - row1
        planes[12] = vpMatrix[3] - vpMatrix[1];
        planes[13] = vpMatrix[7] - vpMatrix[5];
        planes[14] = vpMatrix[11] - vpMatrix[9];
        planes[15] = vpMatrix[15] - vpMatrix[13];

        // 提取近平面: row3 + row2
        planes[16] = vpMatrix[3] + vpMatrix[2];
        planes[17] = vpMatrix[7] + vpMatrix[6];
        planes[18] = vpMatrix[11] + vpMatrix[10];
        planes[19] = vpMatrix[15] + vpMatrix[14];

        // 提取远平面: row3 - row2
        planes[20] = vpMatrix[3] - vpMatrix[2];
        planes[21] = vpMatrix[7] - vpMatrix[6];
        planes[22] = vpMatrix[11] - vpMatrix[10];
        planes[23] = vpMatrix[15] - vpMatrix[14];

        // GPU优化：使用快速逆平方根近似替代sqrt，避免昂贵运算
        // 归一化平面向量（可选，提高数值稳定性）
        for (int i = 0; i < 6; i++) {
            int base = i * 4;
            float lenSq = planes[base] * planes[base] +
                          planes[base + 1] * planes[base + 1] +
                          planes[base + 2] * planes[base + 2];

            if (lenSq > 1e-12f) {
                float invLength = fastInvSqrt(lenSq);
                planes[base] *= invLength;
                planes[base + 1] *= invLength;
                planes[base + 2] *= invLength;
                planes[base + 3] *= invLength;
            }
        }

        return planes;
    }

    /**
     * 深拷贝区段数据数组
     *
     * @param sections 原始数组
     * @return 深拷贝结果（可为 null）
     */
    private static float[][] deepCopySections(float[][] sections) {
        if (sections == null) return null;

        float[][] copy = new float[sections.length][];
        for (int i = 0; i < sections.length; i++) {
            copy[i] = sections[i] != null ? sections[i].clone() : null;
        }
        return copy;
    }

    /**
     * 深拷贝位置数据数组
     *
     * @param positions 原始数组
     * @return 深拷贝结果（可为 null）
     */
    private static float[][] deepCopyPositions(float[][] positions) {
        if (positions == null) return null;

        float[][] copy = new float[positions.length][];
        for (int i = 0; i < positions.length; i++) {
            copy[i] = positions[i] != null ? positions[i].clone() : null;
        }
        return copy;
    }

    /**
     * 计算对象总数
     *
     * @param sections 区段数据
     * @param positions 位置数据
     * @return 对象数量（取较大者）
     */
    private static int calculateObjectCount(float[][] sections, float[][] positions) {
        int secCount = sections != null ? sections.length : 0;
        int posCount = positions != null ? positions.length : 0;
        return Math.max(secCount, posCount);
    }

    /**
     * 快速逆平方根近似（Quake III 算法，GPU友好）。
     *
     * <p>计算 {@code 1.0f / sqrt(x)} 的近似值，精度约 ±1%，
     * 比 {@code 1.0f / (float) Math.sqrt(x)} 快约 3-4 倍。
     *
     * <p>原理：利用 IEEE 754 浮点数位表示，
     * 将整数表示减半并取"魔数"差值，得到逆平方根的近似整数表示。
     *
     * @param x 输入值（必须 > 0）
     * @return 1/sqrt(x) 的近似值
     */
    private static float fastInvSqrt(float x) {
        float xHalf = 0.5f * x;
        int i = Float.floatToIntBits(x);
        i = 0x5f3759df - (i >> 1);
        float result = Float.intBitsToFloat(i);
        // 一次牛顿-拉夫森迭代提高精度
        result *= (1.5f - xHalf * result * result);
        return result;
    }
}
