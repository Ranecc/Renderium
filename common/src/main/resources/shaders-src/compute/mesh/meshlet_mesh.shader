// Renderium - MR2 Mesh Shader 渲染器
// Mesh Shader: 从 Meshlet 数据生成实际三角形
// 来源文档: modern-render-architecture.md §3.2 Mesh Shader Pipeline
// 功能: 读取可见 Meshlet 数据，输出到光栅化器的完整三角形流
// 扩展要求: GL_NV_mesh_shader (Vulkan 1.2+) 或 GL_EXT_mesh_shader (Vulkan 1.3+)

#version 450 core

// ====== Mesh Shader 扩展声明 ======
#ifdef USE_NV_MESH_SHADER
    #extension GL_NV_mesh_shader : require
    // NV 版本 API:
    // - gl_MeshVerticesNV[]: 输出顶点数组
    // - gl_PrimitiveIndicesNV[]: 输出原始索引数组
    // - gl_PrimitiveCountNV: 输出原始数量
#else
    #extension GL_EXT_mesh_shader : require
    // EXT 版本 API（更现代，限制更宽松）
#endif

// ====== Mesh Shader 输出配置 ======
/**
 * 最大输出顶点和原始数限制
 * 
 * NV_mesh_shader 硬件限制:
 *   - max_vertices: 64 (必须匹配 MESHLET_MAX_VERTICES)
 *   - max_primitives: 126 (必须匹配 MESHLET_MAX_INDICES / 3)
 * 
 * EXT_mesh_shader 可支持更大值:
 *   - max_vertices: up to 256
 *   - max_primitives: up to 512+
 */
layout(max_vertices = 64) out;       // 每个 Workgroup 最多输出 64 个顶点
layout(max_primitives = 126) out;     // 每个 Workgroup 最多输出 126 个原始 (=42 个三角形)

// ====== 输出图元类型 ======
/**
 * 图元类型: 三角形列表 (triangles)
 * 
 * 其他可选类型:
 *   - triangles: 标准三角形列表（最常用）
 *   - lines: 线段列表（用于线框渲染或调试）
 *   - points: 点列表（用于粒子系统）
 */
layout(triangles) out;

// ====== 输入缓冲区: Meshlet 数据 (Binding 2, Set 1) ======
/**
 * Meshlet 数据存储（与 Task Shader 共享的只读缓冲区）
 * 
 * 包含所有 Meshlets 的完整几何体数据：
 * - 顶点位置、法线、UV 坐标
 * - 三角形索引
 * - 边界球和元数据
 * 
 * 内存布局见 Task Shader 注释 (~1KB per meshlet)
 */
layout(std430, binding = 0) readonly buffer MeshletData {
    Meshlet meshlets[];
};

// 定义 Meshlet 结构体（与 Task Shader 完全一致）
struct Meshlet {
    vec3 positions[64];           // 顶点位置 (对象空间, half-float 解压后)
    vec3 normals[64];             // 顶点法线 (对象空间, octahedral 解压后)
    vec2 texCoords[64];           // UV 坐标 (half-float 解压后)
    uint indices[126];            // 三角形索引 (本地索引 0-63, packed as uint)
    vec4 boundingSphere;          // 边界球 (xyz=center, w=radius)
    uint lodLevel;                // LOD 等级 [0-7]
    uint parentChunkId;           // 父 Chunk ID
};

// ====== 输入缓冲区: 可见 Meshlet 索引列表 (Binding 2, Set 1) ======
/**
 * Task Shader 的输出 → Mesh Shader 的输入
 * 
 * 存储通过可见性测试的 Meshlet 全局索引。
 * Mesh Shader 根据 WorkgroupID 从此数组读取要处理的 Meshlet。
 * 
 * 格式: uint[] indices[]
 * 大小: 由 Task Shader 运行时决定（最多 MAX_MESHLETS）
 */
layout(std430, binding = 1) readonly buffer VisibleMeshletIndices {
    uint visibleIndices[];
};

// ====== Uniform Buffer: 变换矩阵 (Binding 1, Set 0) ======
/**
 * Mesh Shader 统一缓冲区 - 模型和视角变换参数
 * 
 * 内存布局 (std140):
 * layout(std140) uniform MeshUniforms {
 *     mat4 modelMatrix;      [0]   // 模型变换矩阵 (局部→世界, 64 bytes)
 *     mat4 viewProj;         [64]  // 视角投影矩阵 (世界→裁剪, 64 bytes)
 *     mat3 normalMatrix;     [128] // 法线变换矩阵 (9 floats + padding = 48 bytes)
 * };                         [176] // total size
 */
layout(std140, binding = 1) uniform MeshUniforms {
    mat4 modelMatrix;              // 模型矩阵 (对象空间 → 世界空间)
    mat4 viewProj;                 // 视角投影矩阵 (世界空间 → 裁剪空间)
    mat3 normalMatrix;             // 法线矩阵 (用于正确变换法线方向)
};

// ====== Push Constants (可选，更高性能) ======
/*
layout(push_constant) uniform PushConstants {
    mat4 modelMatrix;
    mat4 viewProj;
    mat3 normalMatrix;
} pc;
*/

// ====== 输出到片段着色器的插值变量 ======

/**
 * 世界空间位置
 * <p>
 * 用于光照计算（视线方向、光源方向等）
 * 精度: highp (32-bit float)
 */
layout(location = 0) out vec3 worldPos[];

/**
 * 世界空间法线
 * <p>
 * 用于 PBR 光照计算（漫反射、镜面反射、法线贴图等）
 * 必须归一化！Mesh Shader 输出后插值会改变长度
 * 精度: highp (32-bit float)
 */
layout(location = 1) out vec3 vNormal[];

/**
 * UV 纹理坐标
 * <p>
 * 用于纹理采样（反照率、金属度、粗糙度等）
 * 范围: [0.0, 1.0] 通常
 * 精度: mediump (16-bit float) 即可，但此处用 highp 保持精度
 */
layout(location = 2) out vec2 texCoord[];

/**
 * 平面 ID（可选，用于多材质/多纹理）
 * <p>
 * 标识此三角形属于哪个平面或使用哪个材质
 * 可用于在 Fragment Shader 中查找正确的纹理
 * 精度: flat (无插值，每个三角形一个值)
 */
// layout(location = 3) flat out uint planeId[];

// ====== 常量定义 ======

/** 最大顶点数（必须与 layout 声明一致）*/
const int MAX_VERTICES = 64;

/** 最大索引数（必须与 layout 声明一致）*/
const int MAX_INDICES = 126;

/** 最大三角形数 = 索引数 / 3 */
const int MAX_TRIANGLES = 42;

// ====== 辅助函数 ======

/**
 * Half-float (FP16) 解压为 FP32
 * 
 * 将 16 位半精度浮点数转换为 32 位单精度浮点数。
 * GPU 端通常有硬件指令支持（f16_to_f32），
 * 但此处提供软件实现作为后备。
 * 
 * @param halfVal 16 位半精度值 (uint 表示)
 * @return 32 位单精度浮点数
 * 
 * IEEE 754-2008 FP16 格式:
 *   - 符号位: 1 bit (bit 15)
 *   - 指数位: 5 bits (bits 14-10), bias = 15
 *   - 尾数位: 10 bits (bits 9-0)
 */
float unpackHalf16(uint halfVal) {
    // 提取各部分
    uint sign = (halfVal >> 15) & 0x1u;
    uint exponent = (halfVal >> 10) & 0x1Fu;
    uint mantissa = halfVal & 0x3FFu;
    
    // 特殊值处理: 零、无穷大、NaN
    if (exponent == 0) {
        // 零或次正规数（简化处理为零）
        return sign == 1 ? -0.0 : 0.0;
    }
    
    if (exponent == 31) {
        // 无穷大或 NaN（简化返回零）
        return 0.0;
    }
    
    // 正规数转换: FP16 → FP32
    // FP32 指数 bias = 127, FP16 bias = 15
    // 新指数 = exponent - 15 + 127 = exponent + 112
    uint newExponent = exponent + 112;
    
    // FP32 尾数需要左移 13 位（23 - 10 = 13）
    uint newMantissa = mantissa << 13;
    
    // 组合为 FP32 位模式
    uint fp32Bits = (sign << 31) | (newExponent << 23) | newMantissa;
    
    // 将位模式重新解释为 float
    return uintBitsToFloat(fp32Bits);
}

/**
 * Octahedral 法线解压
 * 
 * 将压缩的 8-bit/16-bit Octahedral 法线解码为单位向量。
 * Octahedral encoding 是一种高效的三维向量压缩方法，
 * 特别适合法线数据（因为法线是单位向量）。
 * 
 * 编码过程（参考）:
 *   1. 将单位向量 (x,y,z) 投影到八面体表面
 *   2. 展开为二维坐标 (u,v)
 *   3. 量化为 8-bit 或 16-bit 整数
 * 
 * @param encodedOct 压缩的法线数据 (vec2, 范围 [-1,1])
 * @return 解压后的三维单位法线向量 (vec3)
 * 
 * 参考文献:
 *   - "A Survey of Efficient Representations for Independent Unit Vectors" (Cigolle et al., 2014)
 *   - Microsoft's Octahedral Normal Implementation
 */
vec3 decodeNormalOctahedron(vec2 encodedOct) {
    vec3 n = vec3(encodedOct.x, encodedOct.y, 1.0);
    n.z = 1.0 - abs(n.x) - abs(n.y);
    
    // 处理底部四面体的情况
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * (step(0.0, n.xy) * 2.0 - 1.0);
    }
    
    return normalize(n);
}

/**
 * 从打包的 uint 数组提取本地索引
 * 
 * 由于 GLSL 不直接支持 uint8 数组类型，
 * 我们将 byte 索引打包存储在 uint 数组中。
 * 此函数从 uint 中提取单个 byte 索引。
 * 
 * 打包方案（示例）:
 *   uint[0]: index[0], index[1], index[2], index[3] (每个 byte 一个索引)
 *   uint[1]: index[4], index[5], index[6], index[7]
 *   ...
 * 
 * @param packedIndices 打包后的 uint 数组
 * @param indexIndex    要提取的索引位置 (0..125)
 * @return 本地顶点索引 (0..63)
 */
uint unpackLocalIndex(uint packedIndices[], int indexIndex) {
    // 计算包含目标索引的 uint 元素
    int uintIndex = indexIndex / 4;
    // 计算在该 uint 内的字节偏移
    int byteOffset = indexIndex % 4;
    
    // 提取对应的字节并转换为 uint
    uint packedValue = packedIndices[uintIndex];
    uint localIndex = (packedValue >> (byteOffset * 8)) & 0xFFu;
    
    return localIndex;
}

// ====== 主函数 ======

/**
 * Mesh Shader 入口点
 * 
 * 每个 Workgroup 处理一个可见 Meshlet：
 * 1. 根据 WorkGroupID 从 visibleIndices[] 读取 Meshlet 全局索引
 * 2. 从 meshlets[] 读取完整的 Meshlet 几何数据
 * 3. 并行生成所有输出顶点（位置、法线、UV 等）
 * 4. 并行设置所有三角形的原始索引
 * 5. 设置输出的顶点数和原始数
 * 
 * 关键特性:
 * - 完全并行：不同线程独立处理不同顶点/三角形
 * - 无分支：使用 Early Return 和条件赋值避免 warp divergence
 * - 内存高效：按需读取，避免缓存未使用的数据
 */
void main() {
    // ========== 步骤 1: 确定当前处理的 Meshlet ==========
    /**
     * WorkGroupID 对应可见 Meshlet 列表中的索引
     * 
     * Task Shader 输出的第 N 个可见 Meshlet → 第 N 个 Mesh Shader Workgroup
     * 这保证了 Workgroup 与 Meshlet 的一一对应关系
     */
    uint visibleMeshletIndex = gl_WorkGroupID.x;
    
    // 读取全局 Meshlet 索引
    uint globalMeshletIndex = visibleIndices[visibleMeshletIndex];
    
    // 读取 Meshlet 数据（整个结构体加载到寄存器/local memory）
    Meshlet m = meshlets[globalMeshletIndex];
    
    // ========== 步骤 2: 获取线程本地索引 ==========
    /**
     * LocalInvocationID 用于并行处理：
     * - [0, 63]: 处理对应编号的顶点
     * - [0, 125]: 处理对应编号的三角形索引
     * 
     * 注意: 顶点和三角形使用相同的 LocalInvocationID 空间，
     * 通过范围检查区分任务类型
     */
    uint localIdx = gl_LocalInvocationIndex;
    
    // ========== 步骤 3: 生成输出顶点（并行）==========
    /**
     * 每个线程负责一个顶点的完整输出：
     * - 读取顶点属性（位置、法线、UV）
     * - 应用几何变换
     * - 写入输出数组
     * 
     * 条件: localIdx < m.vertexCount（实际顶点数）
     * 使用 Early Return 快速跳过多余线程
     */
    if (localIdx < MAX_VERTICES && localIdx < 64) {  // 安全边界检查
        
        // ----- 读取顶点位置 -----
        /**
         * 顶点位置解压和变换流程:
         * 1. 从 Meshlet 读取压缩位置 (half-float or float)
         * 2. 如果是 half-float，解压为 FP32
         * 3. 应用模型变换: localPos → worldPos
         * 4. 应用视角投影: worldPos → clipSpace
         * 5. 写入 gl_Position（光栅化器输入）
         */
        vec3 localPosition = m.positions[localIdx];
        
        // TODO: 如果位置存储为 half-float (uint)，需要解压:
        // vec3 localPosition = vec3(
        //     unpackHalf16(m.packedPositions[localIdx * 3 + 0]),
        //     unpackHalf16(m.packedPositions[localIdx * 3 + 1]),
        //     unpackHalf16(m.packedPositions[localIdx * 3 + 2])
        // );
        
        // 构建齐次坐标 (x, y, z, 1.0)
        vec4 positionLocal = vec4(localPosition, 1.0);
        
        // 应用模型变换（对象空间 → 世界空间）
        vec4 worldPosition = modelMatrix * positionLocal;
        
        // 应用视角投影（世界空间 → 裁剪空间）
        vec4 clipPosition = viewProj * worldPosition;
        
        // ----- 读取和解压法线 -----
        /**
         * 法线处理流程:
         * 1. 从 Meshlet 读取压缩法线 (octahedral encoding)
         * 2. 解码为单位向量
         * 3. 应用法线矩阵（处理非均匀缩放）
         * 4. 归一化（插值后会改变长度，但此处先存原值）
         */
        vec3 localNormal = m.normals[localIdx];
        
        // TODO: 如果法线使用 octahedral encoding，需要解压:
        // vec2 encodedNormal = unpackUnorm2x16(m.packedNormals[localIdx]) * 2.0 - 1.0;
        // vec3 localNormal = decodeNormalOctahedron(encodedNormal);
        
        // 应用法线矩阵（处理非均匀缩放导致的法线方向改变）
        vec3 worldNormal = normalize(normalMatrix * localNormal);
        
        // ----- 读取 UV 坐标 -----
        /**
         * UV 坐标解压:
         * 1. 从 Meshlet 读取压缩 UV (half-float)
         * 2. 解压为 FP32
         * 3. 直接传递给插值器
         */
        vec2 uv = m.texCoords[localIdx];
        
        // TODO: 如果 UV 存储 为 half-float:
        // vec2 uv = vec2(
        //     unpackHalf16(m.packedTexCoords[localIdx * 2 + 0]),
        //     unpackHalf16(m.packedTexCoords[localIdx * 2 + 1])
        // );
        
        // ----- 写入输出变量 -----
#ifdef USE_NV_MESH_SHADER
        // NV API: 直接写入内置输出数组
        gl_MeshVerticesNV[localIdx].gl_Position = clipPosition;
#else
        // EXT API: 类似接口（具体函数名可能不同）
        gl_MeshVerticesEXT[localIdx].gl_Position = clipPosition;
#endif
        
        // 自定义输出变量（传递给 Fragment Shader）
        worldPos[localIdx] = worldPosition.xyz;
        vNormal[localIdx] = worldNormal;
        texCoord[localIdx] = uv;
    }
    
    // ========== 步骤 4: 设置三角形索引（并行）==========
    /**
     * 每个线程负责一个三角形的索引输出：
     * - 从 Meshlet 读取 3 个本地顶点索引
     * - 写入原始索引数组
     * 
     * 条件: localIdx < primitiveCount（实际三角形数）
     * 注意: 索引值必须是本地顶点索引 [0, vertexCount-1]
     */
    if (localIdx < MAX_TRIANGLES && localIdx < 42) {  // 安全边界检查
        
        // 计算该三角形的起始索引位置
        uint triBase = localIdx * 3;
        
        // 从 Meshlet 读取 3 个顶点的本地索引
        // 注意: m.indices[] 可能是 uint 类型（每元素一个索引）
        // 或者 packed 格式（需要 unpackLocalIndex 解包）
        uint idx0 = m.indices[triBase + 0];
        uint idx1 = m.indices[triBase + 1];
        uint idx2 = m.indices[triBase + 2];
        
        // TODO: 如果使用打包格式:
        // uint idx0 = unpackLocalIndex(m.packedIndices, triBase + 0);
        // uint idx1 = unpackLocalIndex(m.packedIndices, triBase + 1);
        // uint idx2 = unpackLocalIndex(m.packedIndices, triBase + 2);
        
        // 写入原始索引数组（光栅化器据此组装三角形）
#ifdef USE_NV_MESH_SHADER
        gl_PrimitiveIndicesNV[triBase + 0] = idx0;
        gl_PrimitiveIndicesNV[triBase + 1] = idx1;
        gl_PrimitiveIndicesNV[triBase + 2] = idx2;
#else
        gl_PrimitiveIndicesEXT[triBase + 0] = idx0;
        gl_PrimitiveIndicesEXT[triBase + 1] = idx1;
        gl_PrimitiveIndicesEXT[triBase + 2] = idx2;
#endif
    }
    
    // ========== 步骤 5: 设置输出数量（仅主线程）==========
    /**
     * 只有 Workgroup 的第一个线程 (invocation 0) 负责设置输出计数
     * 这确保了只有一个线程修改这些全局状态变量
     * 
     * 重要: 这些值决定了光栅化器实际处理多少顶点和三角形
     * 设置错误会导致渲染崩溃或内存越界！
     */
    if (gl_LocalInvocationID.x == 0) {
        // 设置实际输出的顶点数量
        // 应该等于 Meshlet 的实际顶点数（<= MAX_VERTICES）
        // TODO: 从 Meshlet 结构体读取实际的 vertexCount 字段
        // 此处假设固定使用 64（需根据实际情况调整）
#ifdef USE_NV_MESH_SHADER
        gl_PrimitiveCountNV = MAX_TRIANGLES;  // 输出三角形数量
#else
        gl_PrimitiveCountEXT = MAX_TRIANGLES;
#endif
    }
}

// ====== 片段着色器接口说明 ======
//
// Mesh Shader 的输出将自动传递给后续绑定的 Fragment Shader。
// Fragment Shader 应声明匹配的 input 变量:
//
// layout(location = 0) in vec3 worldPos;     // 插值后的世界位置
// layout(location = 1) in vec3 vNormal;       // 插值后的法线（需重新归一化!）
// layout(location = 2) in vec2 texCoord;      // 插值后的 UV
//
// void main() {
//     // 重新归一化法线（插值后会改变长度）
//     vec3 N = normalize(vNormal);
//     
//     // 计算 PBR 光照（简化示例）
//     vec3 albedo = texture(diffuseMap, texCoord).rgb;
//     vec3 L = normalize(lightPos - worldPos);
//     vec3 V = normalize(cameraPos - worldPos);
//     vec3 H = normalize(L + V);
//     
//     // Lambert 漫反射
//     float diffuse = max(dot(N, L), 0.0);
//     
//     // Blinn-Phong 高光
//     float specular = pow(max(dot(N, H), 0.0), 32.0);
//     
//     // 最终颜色
//     vec3 color = albedo * (diffuse + specular);
//     
//     fragColor = vec4(color, 1.0);
// }

// ====== 编译说明 ======
//
// 编译命令 (glslangValidator):
//   glslangValidator -V -e main --target-env vulkan1.2 \
//     -DUSE_NV_MESH_SHADER \
//     meshlet_mesh.shader -o meshlet_mesh.shader.spv
//
// 或对于 EXT 版本:
//   glslangValidator -V -e main --target-env vulkan1.3 \
//     meshlet_mesh.shader -o meshlet_mesh_ext.shader.spv
//
// 注意事项:
//   1. Mesh Shader 在 Vulkan 中属于 Graphics Pipeline（不是 Compute！）
//   2. 必须与 Task Shader 和 Fragment Shader 一起链接成完整 Pipeline
//   3. Pipeline 创建时指定 VK_PIPELINE_CREATE_DISPATCH_BASE 位（如果需要偏移）
//   4. 输出变量的 location 必须与 Fragment Shader 的 input 匹配
//
// 性能优化建议:
//   1. 尽量减少分支（warp divergence 会严重影响 GPU 效率）
//   2. 使用紧凑的数据格式（half-float, octahedral encoding）节省带宽
//   3. 利用 Shared Memory 缓存频繁访问的数据
//   4. 考虑使用 Subgroup 操作优化并行缩减
//   5. 确保 vertexCount 和 primitiveCount 准确（避免光栅化器处理空数据）
//
// 调试技巧:
//   1. 使用 gl_Position.w = 0 强制所有顶点到屏幕中心（验证剔除逻辑）
//   2. 输出法线作为颜色（验证法线变换是否正确）
//   3. 输出 UV 作为颜色（验证纹理坐标是否正确）
//   4. 使用 flat 插值输出 Meshlet ID（调试归属问题）
//
// 与 Java 端的接口对应关系:
//   - Meshlet → com.renderium.module.impl.blaze3d.modern.MeshShaderRenderer.Meshlet
//   - MeshUniforms → render() 方法的 viewProjMatrix 参数
//   - VisibleMeshletIndices ← Task Shader 的输出
//
// 文档参考:
//   - modern-render-architecture.md §3.2
//   - NVIDIA Mesh Shader Whitepaper (GDC 2019)
//   - Vulkan Specification: VK_NV_mesh_shader / VK_EXT_mesh_shader
//   - "A Survey of Efficient Representations for Independent Unit Vectors"
