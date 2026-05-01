// Renderium - 光线追踪模块
// RaytracingInstanceData - TLAS 实例数据类
// 功能: 封装顶层加速结构 (TLAS) 的实例信息

package com.ranecc.renderium.domain.model;

import org.joml.Matrix4f;

/**
 * TLAS 实例数据 📦
 * <p>
 * 封装顶层加速结构 (Top-Level Acceleration Structure, TLAS) 中的实例信息。
 * 每个 TLAS 实例代表场景中的一个物体实例，引用一个底层加速结构 (BLAS)
 * 并包含变换矩阵和命中测试控制参数。
 *
 * <h2>在光线追踪管线中的角色：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    光线追踪加速结构                          │
 * │                                                             │
 * │  ┌───────────────┐                                         │
 * │  │  TLAS (顶层)   │  ← 包含所有实例的 BVH                   │
 * │  │               │                                         │
 * │  │  Instance[0]  ├─→ BLAS[0] (Mesh A 的几何加速结构)       │
 * │  │  Instance[1]  ├─→ BLAS[1] (Mesh B 的几何加速结构)       │
 * │  │  Instance[2]  ├─→ BLAS[0] (共享 Mesh A, 不同变换)        │
 * │  │  ...          │                                         │
 * │  └───────────────┘                                         │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 当光线与 TLAS 相交时:
 * 1. 遍历 TLAS 找到命中的 Instance
 * 2. 应用 Instance 的变换矩阵
 * 3. 在对应的 BLAS 中进行精确几何测试
 * 4. 返回命中结果 (包括 instanceId, primitiveId 等)
 * </pre>
 *
 * <h3>性能优化：</h3>
 * <ul>
 *   <li><b>BLAS 共享</b>: 多个实例可引用同一个 BLAS (节省显存)</li>
 *   <li><b>Instance Mask</b>: 控制哪些实例参与特定光线的命中测试</li>
 *   <li><b>快速更新</b>: 支持仅更新变换矩阵而不重建整个 TLAS</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see com.ranecc.renderium.module.impl.raytracing.RayTracingModule#buildTopLevelAS(RaytracingInstanceData[], int)
 */
public final class RaytracingInstanceData {

    // ==================== 核心字段 ====================

    /**
     * 变换矩阵 (4×4)
     * <p>
     * 定义此实例在世界空间中的位置、旋转和缩放。
     * 光线命中时会使用此矩阵将光线转换到实例的局部空间。
     *
     * <h3>矩阵布局：</h3>
     * 使用列主序 (Column-Major) 布局，符合 Vulkan/GLSL 约定:
     * <pre>
     * [m00 m10 m20 m30]  // 列 0 (X 轴 + 平移 X)
     * [m01 m11 m21 m31]  // 列 1 (Y 轴 + 平移 Y)
     * [m02 m12 m22 m32]  // 列 2 (Z 轴 + 平移 Z)
     * [m03 m13 m23 m33]  // 列 3 (透视 + 齐次坐标)
     * </pre>
     */
    public Matrix4f transform = new Matrix4f();

    /**
     * 引用的底层加速结构 (BLAS) 句柄
     * <p>
     * 指向包含实际几何数据的 VkAccelerationStructureKHR 对象。
     * 多个不同的 RaytracingInstanceData 可以引用同一个 blasHandle (实例化渲染)。
     *
     * <p>有效值: 通过 {@link com.ranecc.renderium.module.impl.raytracing.RayTracingModule#buildBottomLevelAS} 创建的 BLAS 句柄</p>
     * <p>无效值: 0L 或 -1L 表示未初始化</p>
     */
    public long blasHandle = 0L;

    /**
     * 实例掩码 (8-bit 无符号整数)
     * <p>
     * 用于控制光线-实例相交测试的过滤。
     * 当发射光线时指定 rayMask，只有当 (instanceMask & rayMask) != 0 时才进行测试。
     *
     * <h3>典型用途：</h3>
     * <ul>
     *   <li>分层渲染: 不同 Pass 使用不同 mask (如阴影用 0x01, 反射用 0x02)</li>
     *   <li>碰撞体排除: 将碰撞体标记为 0x00 使其不参与渲染光线测试</li>
     *   <li>LOD 控制: 远距离物体使用不同 mask 以简化测试</li>
     * </ul>
     *
     * <p>默认值: 0xFF (参与所有光线测试)</p>
     * <p>有效范围: 0x00 - 0xFF (8-bit)</p>
     */
    public byte instanceMask = (byte) 0xFF;

    /**
     * 命中组索引 (Hit Group Index)
     * <p>
     * 选择光线命中时调用的着色器组 (Shader Binding Table 中的索引)。
     * 不同的 hitGroupIndex 可以对应不同的材质或着色效果。
     *
     * <h3>映射关系：</h3>
     * <pre>
     * SBT (Shader Binding Table):
     * ├── RayGen Shader (固定)
     * ├── Miss Shader(s) (固定)
     * └── Hit Groups:
     *     ├── HitGroup[0] → Opaque Material (标准 PBR)
     *     ├── HitGroup[1] → Transparent Material (玻璃/水)
     *     ├── HitGroup[2] → Emissive Material (发光体)
     *     └── ...
     *
     * instance.hitGroupIndex = 1 → 命中时调用 Transparent Material Shader
     * </pre>
     *
     * <p>默认值: 0 (第一个 Hit Group)</p>
     */
    public int hitGroupIndex = 0;

    /**
     * 自定义索引 (Custom Index)
     * <p>
     * 用户定义的 24-bit 整数，传递给着色器以标识实例类型或其他属性。
     * 可用于在着色器中查找材质 ID、纹理索引等数据。
     *
     * <h3>典型用法：</h3>
     * <ul>
     *   <li>材质 ID: 查找材质参数缓冲区</li>
     *   <li>纹理索引: 绑定正确的纹理集</li>
     *   <li>对象 ID: 用于调试或特殊逻辑</li>
     *   <li>LOD 级别: 动态选择细节层次</li>
     * </ul>
     *
     * <p>默认值: 0</p>
     * <p>有效范围: 0 - 16777215 (24-bit unsigned)</p>
     */
    public int customIndex = 0;

    // ==================== 实例标志位 (可选高级功能) ====================

    /**
     * 是否禁用三角形剔除 (Culling Disable)
     * <p>
     * 如果为 true，则对此实例的所有三角形都进行双面测试 (无背面剔除)。
     * 适用于薄壁物体（如叶子、布料、单面墙）或需要看到内部的情况。
     *
     * <p>默认值: false (启用背面剔除)</p>
     */
    public boolean disableCulling = false;

    /**
     * 是否强制不透明 (Force Opaque)
     * <p>
     * 如果为 true，则任何命中都视为完全不透明命中 (跳过 Any-Hit Shader)。
     * 可以提高性能，因为不需要执行 alpha 测试。
     *
     * <p>默认值: false (允许透明度测试)</p>
     */
    public boolean forceOpaque = false;

    /**
     * 实例 ID (自动分配或手动指定)
     * <p>
     * 全局唯一的实例标识符。
     * 通常由系统在构建 TLAS 时自动分配，
     * 但也可以手动指定以保持稳定性 (如网络同步场景)。
     *
     * <p>特殊值: -1 表示未初始化 / 自动分配</p>
     */
    public int instanceId = -1;

    // ==================== 构造函数 ====================

    /**
     * 创建空的实例数据 (所有字段使用默认值)
     * <p>
     * 必须在使用前设置 transform 和 blasHandle！
     */
    public RaytracingInstanceData() {
        // 使用默认值 (已在字段声明中指定)
    }

    /**
     * 创建实例数据并指定核心参数
     *
     * @param transform      变换矩阵 (不能为 null)
     * @param blasHandle     BLAS 句柄 (> 0)
     * @throws IllegalArgumentException 如果参数无效
     */
    public RaytracingInstanceData(Matrix4f transform, long blasHandle) {
        if (transform == null) {
            throw new IllegalArgumentException("transform 不能为 null");
        }
        if (blasHandle <= 0) {
            throw new IllegalArgumentException("blasHandle 必须大于 0");
        }
        this.transform.set(transform);
        this.blasHandle = blasHandle;
    }

    /**
     * 创建完整的实例数据
     *
     * @param transform      变换矩阵
     * @param blasHandle     BLAS 句柄
     * @param instanceMask   实例掩码
     * @param hitGroupIndex  命中组索引
     * @param customIndex    自定义索引
     */
    public RaytracingInstanceData(Matrix4f transform, long blasHandle,
                        byte instanceMask, int hitGroupIndex, int customIndex) {
        this(transform, blasHandle);
        this.instanceMask = instanceMask;
        this.hitGroupIndex = hitGroupIndex;
        this.customIndex = customIndex;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建静态实例 (位置不变，适合地形/建筑)
     *
     * @param position 世界空间位置 (x, y, z)
     * @param blasHandle BLAS 句柄
     * @return 配置好的 RaytracingInstanceData
     */
    public static RaytracingInstanceData createStatic(float x, float y, float z, long blasHandle) {
        RaytracingInstanceData data = new RaytracingInstanceData();
        data.transform.translation(x, y, z);
        data.blasHandle = blasHandle;
        return data;
    }

    /**
     * 创建动态实例 (每帧可能移动的物体)
     *
     * @param transform 当前帧的变换矩阵
     * @param blasHandle BLAS 句柄
     * @param customIndex 物体 ID (用于识别)
     * @return 配置好的 RaytracingInstanceData
     */
    public static RaytracingInstanceData createDynamic(Matrix4f transform, long blasHandle,
                                              int customIndex) {
        return new RaytracingInstanceData(transform, blasHandle, (byte) 0xFF, 0, customIndex);
    }

    // ==================== 验证方法 ====================

    /**
     * 验证实例数据的有效性
     *
     * @return true 如果数据有效可用于构建 TLAS
     */
    public boolean isValid() {
        if (blasHandle <= 0) {
            return false; // 必须有有效的 BLAS
        }
        if (transform == null) {
            return false; // 变换矩阵不能为空
        }
        if (hitGroupIndex < 0 || hitGroupIndex > 65535) {
            return false; // 命中组索引超出合理范围
        }
        if (customIndex < 0 || customIndex > 16777215) {
            return false; // 自定义索引超出 24-bit 范围
        }
        return true;
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        return String.format(
                "RaytracingInstanceData{blas=0x%X, mask=0x%02X, hitGroup=%d, customIdx=%d, id=%d}",
                blasHandle, instanceMask & 0xFF, hitGroupIndex, customIndex, instanceId
        );
    }
}
