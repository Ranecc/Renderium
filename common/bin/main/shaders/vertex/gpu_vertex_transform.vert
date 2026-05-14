#version 450 core
// ============================================================================
// Renderium Phase 2 - 狂暴模式优化
// GPU Vertex Transform Vertex Shader (GPU 顶点变换着色器)
// ============================================================================
//
// 用途: GPUVertexTransformSystem - CPU→GPU 变换迁移
//
// 功能描述:
//   将传统的 CPU 端顶点变换 (模型空间 → 世界空间 → 裁剪空间) 完全迁移到
//   GPU Vertex Shader 中执行。这是 GPU-Driven Rendering 的核心组件之一。
//
// 传统方式的问题:
//   CPU 每帧对所有可见 Chunk 的所有顶点执行矩阵乘法:
//     for (chunk : visibleChunks) {
//         for (vertex : chunk.vertices) {
//             screenPos = viewProj * chunkTransform * vertex.position;
//         }
//     }
//   对于 1000 chunks × 1000 vertices = 100 万次 4x4 矩阵乘法!
//
// GPU 化方案的优势:
//   - 零 CPU 变换开销 (CPU 只负责上传变换矩阵)
//   - 利用 GPU 数千个并行核心同时处理变换
//   - 消除 CPU-GPU 同步点 (无需等待变换完成)
//   - 自动适应不同硬件性能 (更好的 GPU = 更快)
//
// 数据流:
//   ┌──────────────┐     ┌─────────────────────┐     ┌──────────────┐
//   │ Global VBO    │────▶│ This Vertex Shader  │────▶│ Rasterizer   │
//   │ (模型空间     │     │                     │     │              │
//   │  顶点数据)    │     │ 1. 读取 instance    │     │ 光栅化       │
//   │              │     │    变换矩阵          │     │ 插值属性     │
//   │ 所有 chunk   │     │ 2. modelMatrix × pos│     │              │
//   │  共享        │     │ 3. viewProj × world │     │              │
//   └──────────────┘     └─────────────────────┘     └──────────────┘
//                                ▲
//                                │ per-instance
//                       ┌────────┴────────┐
//                       │ Instance Buffer  │
//                       │ (每个 chunk 一个  │
//                       │  mat4 变换矩阵)   │
//                       └─────────────────┘
//
// 性能对比 (估算):
//   场景: 1000 chunks, 平均 500 vertices/chunk, 500K total vertices
//   ┌──────────────┬─────────────┬─────────────┐
//   │ 操作         │ CPU 变换    │ GPU 变换     │
//   ├──────────────┼─────────────┼─────────────┤
//   │ 矩阵乘法次数 │ 500,000     │ 0           │
//   │ CPU 时间     │ 3-5 ms      │ <0.1 ms     │
//   │ 内存带宽     │ 高(写回)    │ 低(只读)     │
//   │ 延迟         │ 同步等待    │ 流水线并行   │
//   └──────────────┴─────────────┴─────────────┘
//
// 集成点:
//   Java端: GPUVertexTransformSystem.prepareFrame() + render()
//   渲染调用: vkCmdDrawIndexedIndirect() 或 vkCmdDrawIndirectCount()
//   配合使用: indirect_draw_gen.comp 生成的命令
//
// 作者: Renderium Shader Team
// 日期: 2026-04-14
// ============================================================================

// ==================== 扩展声明 ====================
// 支持绘制参数扩展 (用于获取 baseInstance)
#extension GL_ARB_shader_draw_parameters : require

// ==================== 输入属性: 全局顶点缓冲数据 ====================
//
// 这是所有 Chunk 共享的模型空间顶点数据。
// 每个 Chunk 在全局缓冲中有连续的顶点范围，通过 baseVertex 指定偏移。
//
// 顶点格式 (标准 28 bytes/vertex):
//   location 0: vec3 position      (12 bytes) - 模型空间位置
//   location 1: vec4 color         (16 bytes) - RGBA 颜色 [0,1]
//   location 2: vec2 texCoord      (8 bytes)  - UV 坐标 [0,1]
//   location 3: vec2 lightData     (8 bytes)  - (skyLight, blockLight)

/** 模型空间位置 (相对于 Chunk 局部坐标原点) */
layout(location = 0) in vec3 inPosition;

/** 顶点颜色 (RGBA) */
layout(location = 1) in vec4 inColor;

/** 纹理坐标 */
layout(location = 2) in vec2 inTexCoord;

/** 光照数据 (sky_light, block_light) */
layout(location = 3) in vec2 inLightData;

/** 材质索引 (可选，如果未在 VBO 中则从 SSBO 读取) */
layout(location = 4) in uint inMaterialIndex;

// ==================== SSBO: Instance 变换矩阵数组 ====================
// Binding 0: 每个 Chunk/Instance 一个 4x4 变换矩阵
//
// 内存布局:
//   transforms[instanceId] = mat4 模型-世界变换矩阵 (列优先存储)
//
// 矩阵内容说明:
//   该矩阵将 Chunk 局部坐标转换为世界坐标。
//   对于 Minecraft 地形 Chunk，这通常是一个纯平移矩阵:
//     [ 1   0   0   chunkX*16 ]
//     [ 0   1   0   chunkY*16 ]
//     [ 0   0   1   chunkZ*16 ]
//     [ 0   0   0   1           ]
//
//   但也支持更复杂的变换 (旋转、缩放)，用于:
//     - 动画方块 (如活塞、门)
//     - 实体渲染 (非地形几何)
//     - 自定义变换效果
//
// Java端填充:
//   for (int i = 0; i < visibleChunks.size(); i++) {
//       ChunkRenderData chunk = visibleChunks.get(i);
//       Mat4 transform = Mat4.translation(
//           chunk.getX() * 16,
//           chunk.getY() * 16,
//           chunk.getZ() * 16
//       );
//       // 按列优先顺序写入 buffer
//       transformBuffer.write(transform, i * 64);  // 64 bytes per mat4
//   }
//
layout(std430, binding = 0) readonly buffer InstanceTransformBuffer {
    mat4 transforms[];            // 每个 instance 一个 mat4 (64 bytes)
};

// ==================== UBO: 帧数据 (每帧更新一次) ====================
// Binding 1: View-Projection 矩阵和帧相关参数
//
// 注意: 这个 UBO 对同一帧的所有 Draw Call 是共享的，
//       只在相机移动或窗口大小改变时更新。
//
layout(std140, binding = 1) uniform FrameData {
    /** View-Projection 组合矩阵 */
    mat4 viewProjection;

    /** View 矩阵 (可选，用于面向相机的效果) */
    mat4 viewMatrix;

    /** 相机世界位置 (可选，用于距离雾等效果) */
    vec3 cameraPosition;

    /** 时间戳 (用于动画) */
    float time;

    /** Delta Time (用于物理模拟) */
    float deltaTime;

    /** Frame Index (用于 TAA 等时序算法) */
    uint frameIndex;

    /** Base Instance 偏移 (由 vkCmdDraw* 的 firstInstance 参数设置) */
    uint baseInstanceOffset;
};

// ==================== Push Constants (可选，高频更新数据) ====================
// Push Constants 用于每 Draw Call 更新的少量数据 (最大 128 bytes)
// 适合存储: 物体 ID、LOD 级别、材质参数等
//
// 注意: 如果使用 push constants，需要确保 Java 端正确调用 vkCmdPushConstants()
//
/*
layout(push_constant) uniform PushConstantData {
    uint objectID;                // 当前渲染对象 ID
    uint lodLevel;                 // LOD 细节级别 [0, MAX_LOD]
    vec4 materialParams;           // 材质参数 (roughness, metallic, etc.)
} pc;
*/

// ==================== 输出变量: 传递给 Fragment Shader ====================
/** 世界空间位置 (用于光照计算、阴影映射等) */
layout(location = 0) out vec3 outWorldPos;

/** 视图空间位置 (可选，用于视图空间效果) */
layout(location = 1) out vec3 outViewPos;

/** 顶点颜色 (插值后传递) */
layout(location = 2) out vec4 outVertexColor;

/** 纹理坐标 */
layout(location = 3) out vec2 outTexCoord;

/** 光照数据 */
layout(location = 4) out vec2 outLightData;

/** 材质索引 (flat: 不插值) */
layout(location = 5) flat out uint outMaterialIndex;

/** 屏幕 space 位置 (可选，用于屏幕空间效果) */
// layout(location = 6) out vec3 outScreenPos;

// ==================== 主函数 ====================
void main() {
    // ---- 步骤 1: 确定 Instance ID ----
    //
    // 获取当前正在处理的 instance (Chunk) 索引
    //
    // 方法 A (推荐): 使用 gl_BaseInstanceARB + gl_InstanceID
    //   - gl_BaseInstanceARB: 来自 VkDraw* 结构体的 firstInstance 字段
    //   - gl_InstanceID: 当前 instance 在 draw call 内的局部 ID
    //   - 实际 instance ID = gl_BaseInstanceARB + gl_InstanceID
    //
    // 方法 B: 直接使用 gl_InstanceIndex (Vulkan GLSL)
    //   - gl_Index 已经包含了正确的全局 instance index
    //   - 需要 Vulkan 1.2+ 或 VK_KHR_shader_draw_parameters
    //
    // 方法 C: 从 UBO 读取 baseInstanceOffset + gl_InstanceID
    //   - 最兼容但多一次内存访问
    //

#ifdef GL_ARB_shader_draw_parameters
    // 使用扩展提供的内置变量 (最高效)
    uint instanceID = gl_BaseInstanceARB + gl_InstanceID;
#else
    // 回退方案: 手动计算
    uint instanceID = baseInstanceOffset + gl_InstanceID;
#endif

    // ---- 步骤 2: 读取 Instance 变换矩阵 ----
    //
    // 从 SSBO 中读取当前 Chunk 的模型-世界变换矩阵
    // 这是一个 64 字节的内存读取 (4 × vec4)
    //
    // 性能优化说明:
    //   - 同一个 triangle 的 3 个顶点会读取相同的 matrix → GPU 缓存命中
    //   - 相邻 instances 可能访问相邻内存 → 合并访问优化
    //   - 使用 readonly 限制编译器优化
    //
    mat4 modelMatrix = transforms[instanceID];

    // ---- 步骤 3: 执行模型-世界变换 ----
    //
    // 将顶点从模型 (Chunk 局部) 空间变换到世界空间
    // 公式: worldPos = M_modelToWorld × position
    //
    // 注意: position 的 w 分量设为 1.0 (表示这是一个点，不是方向)
    //
    vec4 worldPos4 = modelMatrix * vec4(inPosition, 1.0);
    vec3 worldPos = worldPos4.xyz;

    // ---- 步骤 4: 执行 View-Projection 变换 ----
    //
    // 将世界空间坐标变换到裁剪空间 (NDC 之前的最后一步)
    // 公式: clipPos = VP × worldPos
    //
    // 结果说明:
    //   gl_Position 是一个 vec4:
    //   - xyz: 裁剪空间坐标 (透视除法前)
    //   - w: 透视除法因子 (用于 perspective-correct interpolation)
    //
    //   在光栅化阶段，GPU 会自动执行: ndc = xyz / w
    //
    gl_Position = viewProjection * worldPos4;

    // ---- 步骤 5: 计算并输出其他所需数据 ----

    // 世界空间位置 (传递给 FS 用于光照、雾效等)
    outWorldPos = worldPos;

    // 视图空间位置 (可选，用于视图空间光照或特效)
    // viewPos = V × worldPos (V = viewMatrix)
    vec4 viewPos4 = viewMatrix * worldPos4;
    outViewPos = viewPos4.xyz;

    // 透传顶点属性 (GPU 会自动进行 perspective-correct 插值)
    outVertexColor = inColor;
    outTexCoord = inTexCoord;
    outLightData = inLightData;
    outMaterialIndex = inMaterialIndex;

    // 可选: 计算屏幕空间位置 (用于屏幕空间反射、SSAO 等)
    // 这需要在 FS 中执行透视除法
    // outScreenPos = gl_Position.xyz / gl_Position.w;  // NDC
    // outScreenPos = outScreenPos * 0.5 + 0.5;        // [0,1]

    // ---- 函数结束 ----
    //
    // 此 Vertex Shader 完成的操作总结:
    //
    //   输入:
    //     - inPosition:    模型空间位置 (来自全局 VBO)
    //     - inColor/U/etc: 其他顶点属性
    //     - transforms[i]: Instance 变换矩阵 (来自 SSBO)
    //     - viewProjection: VP 矩阵 (来自 UBO)
    //
    //   处理:
    //     1. 确定当前 instance ID
    //     2. 读取对应的变换矩阵
    //     3. 模型空间 → 世界空间 (modelMatrix × pos)
    //     4. 世界空间 → 裁剪空间 (viewProjection × worldPos)
    //
    //   输出:
    //     - gl_Position:   裁剪空间坐标 (给光栅器)
    //     - outWorldPos:   世界空间位置 (给 FS)
    //     - outViewPos:    视图空间位置 (给 FS)
    //     - 其他:          插值后的属性 (给 FS)
    //
    // 性能特征:
    //   - ALU: ~20 FMA ( fused multiply-add ) 操作
    //   - Memory: 64 bytes (matrix) + 28 bytes (vertex attributes)
    //   - Latency: ~4-6 cycles on modern GPUs (隐藏在顶点获取延迟中)
}
