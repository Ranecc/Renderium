// ============================================================
// Renderium Accelerator - 核心计算算法接口 (L2)
// ============================================================
// 定义所有可从Java调用的计算密集型算法
// 每个算法遵循统一模式:
//   1. createContext() - 创建计算上下文（预分配内存）
//   2. execute() - 执行计算（输入数据来自共享内存）
//   3. getResult() - 获取结果（写入共享内存）
//   4. destroyContext() - 销毁上下文
//
// 数据交换:
//   Java → [写入共享内存] → C++ [读取+计算] → [写回共享内存] → Java
// ============================================================

#pragma once

#include "accel_config.h"
#include "platform_abstraction.h"

namespace renderium {
namespace accel {

// ==================== 通用数据结构 ====================

/// 区块坐标（整数网格，跨算法共享）
struct SectionCoord {
    i32 x, y, z;
};

// ==================== BFS 遮挡剔除引擎 ====================

namespace bfs_occlusion {

/// 相机视图参数
struct CameraView {
    f32 eyeX, eyeY, eyeZ;              // 相机位置
    f32 lookX, lookY, lookZ;          // 观察方向
    f32 fov;                           // 视野角度（度）
    f32 renderDistance;                // 渲染距离
};

/// 可见性结果
struct VisibilityResult {
    u32 visibleCount;                  // 可见区块数量
    u32 totalProcessed;                 // 总处理数
    u64 traverseTimeNs;                // 遍历耗时（纳秒）
    u32 visibilityBitmap[1];           // 可见性位图（变长数组，调用者分配足够空间）
};

/**
 * 创建BFS遮挡剔除上下文
 * @param maxSections 最大区块数
 * @param outContext 输出: 上下文句柄
 * @return 操作结果
 */
ACCEL_API OperationResult createContext(
    u32 maxSections,
    ComputeContextHandle& outContext
);

/**
 * 初始化区块图
 * <p>
 * 必须在findVisible之前调用，建立邻接关系。
 *
 * @param context 上下文句柄
 * @param sections 区块坐标数组
 * @param count 区块数量
 * @return 操作结果
 */
ACCEL_API OperationResult initializeGraph(
    ComputeContextHandle context,
    const SectionCoord* sections,
    u32 count
);

/**
 * 设置邻居关系
 *
 * @param context 上下文句柄
 * @param sectionIdx 区块索引
 * @param neighbors 邻居索引数组（最多6个）
 * @param neighborCount 邻居数量
 * @return 操作结果
 */
ACCEL_API OperationResult setNeighbors(
    ComputeContextHandle context,
    u32 sectionIdx,
    const u32* neighbors,
    u32 neighborCount
);

/**
 * 执行BFS可见性查询
 * <p>
 * 从相机位置开始BFS遍历，标记所有可见区块。
 * 结果通过getResult()获取。
 *
 * @param context 上下文句柄
 * @param camera 相机参数
 * @param frameNumber 当前帧号（用于去重）
 * @return 操作结果（包含耗时统计）
 */
ACCEL_API OperationResult findVisibleSections(
    ComputeContextHandle context,
    const CameraView& camera,
    u32 frameNumber
);

/**
 * 获取可见性结果
 * <p>
 * 结果数据直接写入用户提供的缓冲区（零拷贝）。
 *
 * @param context 上下文句柄
 * @param result 输出结果结构体指针
 * @param bufferSize 缓冲区大小（字节）
 * @return 操作结果
 */
ACCEL_API OperationResult getResult(
    ComputeContextHandle context,
    VisibilityResult* result,
    size_t bufferSize
);

/**
 * 销毁上下文并释放资源
 * @param context 上下文句柄（销毁后无效）
 * @return 操作结果
 */
ACCEL_API OperationResult destroyContext(ComputeContextHandle context);

} // namespace bfs_occlusion

// ==================== LOD 距离计算器 ====================

namespace lod_calculator {

/// LOD配置参数
struct LODConfig {
    u32 maxLevels;                     // 最大LOD等级 (1-8)
    f32 distances[8];                  // 距离阈值数组
    f32 baseDistance;                  // 基础距离
    f32 falloffFactor;                 // 衰减因子
};

/// 单个区块的LOD计算输入
struct LODInput {
    SectionCoord position;             // 区块位置
    f32 cameraX, cameraY, cameraZ;     // 相机位置
    f32 fov;                          // 视野角度
};

/// LOD计算输出
struct LODOutput {
    u8 lodLevel;                       // 计算出的LOD等级 (0-maxLevels-1)
    f32 distance;                      // 到相机的距离
    f32 screenCoverage;               // 屏幕覆盖率估计
};

/**
 * 创建LOD计算器实例
 * @param config 配置参数
 * @param outContext 输出: 上下文句柄
 * @return 操作结果
 */
ACCEL_API OperationResult createContext(
    const LODConfig& config,
    ComputeContextHandle& outContext
);

/**
 * 批量计算LOD等级
 * <p>
 * 使用SIMD优化（如果CPU支持AVX2/AVX-512）。
 *
 * @param context 上下文句柄
 * @param inputs 输入数组
 * @param outputs 输出数组（必须预分配）
 * @param count 计算数量
 * @return 操作结果（包含总耗时）
 */
ACCEL_API OperationResult batchComputeLOD(
    ComputeContextHandle context,
    const LODInput* inputs,
    LODOutput* outputs,
    u32 count
);

/**
 * 更新距离阈值表
 * <p>
 * 允许运行时动态调整LOD策略。
 *
 * @param context 上下文句柄
 * @param newThresholds 新阈值数组
 * @param count 数组大小
 * @return 操作结果
 */
ACCEL_API OperationResult updateThresholds(
    ComputeContextHandle context,
    const f32* newThresholds,
    u32 count
);

/**
 * 销毁LOD计算器
 * @param context 上下文句柄
 * @return 操作结果
 */
ACCEL_API OperationResult destroyContext(ComputeContextHandle context);

} // namespace lod_calculator

// ==================== Lyapunov 质量评估器 ====================

namespace lyapunov_checker {

/// 帧质量评估输入
struct QualityInput {
    const f32* frameData;              // 帧数据指针（亮度/对比度等）
    size_t dataSize;                   // 数据大小（元素数）
    f32 previousLyapunov;              // 前一帧的Lyapunov指数
    f64 deltaTime;                     // 帧间隔时间（秒）
};

/// 质量评估输出
struct QualityOutput {
    f32 lyapunovExponent;              // Lyapunov指数（稳定性指标）
    f32 qualityScore;                  // 质量评分 (0.0-1.0)
    bool isStable;                     // 是否稳定
    u32 degradationLevel;              // 退化等级 (0=正常, 1=轻微, 2=严重)
};

/**
 * 创建质量评估器
 * @param windowSize 窗口大小（用于滑动平均）
 * @param outContext 输出: 上下文句柄
 * @return 操作结果
 */
ACCEL_API OperationResult createContext(
    u32 windowSize,
    ComputeContextHandle& outContext
);

/**
 * 评估单帧质量
 *
 * @param context 上下文句柄
 * @param input 输入数据
 * @param output 输出结果
 * @return 操作结果
 */
ACCEL_API OperationResult evaluateFrame(
    ComputeContextHandle context,
    const QualityInput& input,
    QualityOutput& output
);

/**
 * 重置评估器状态
 * <p>
 * 在场景切换时调用。
 *
 * @param context 上下文句柄
 * @return 操作结果
 */
ACCEL_API OperationResult reset(ComputeContextHandle context);

/**
 * 销毁质量评估器
 * @param context 上下文句柄
 * @return 操作结果
 */
ACCEL_API OperationResult destroyContext(ComputeContextHandle context);

} // namespace lyapunov_checker

// ==================== Kahan 累加器 ====================

namespace kahan_accumulator {

/// Kahan累加器状态
struct KahanState {
    f64 sum;                           // 主累加和
    f64 compensation;                   // 补偿值
    u64 elementCount;                  // 已累加元素数
};

/**
 * 创建Kahan累加器
 * @param initialSum 初始值（默认0）
 * @param outContext 输出: 上下文句柄
 * @return 操作结果
 */
ACCEL_API OperationResult createContext(
    f64 initialSum,
    ComputeContextHandle& outContext
);

/**
 * 累加单个元素
 * <p>
 * 使用Neumaier改进版Kahan算法，数值精度接近f80。
 *
 * @param context 上下文句柄
 * @param value 要累加的值
 * @return 操作结果
 */
ACCEL_API OperationResult add(
    ComputeContextHandle context,
    f64 value
);

/**
 * 批量累加
 * <p>
 * SIMD优化的批量版本，适合大数组求和。
 *
 * @param context 上下文句柄
 * @param values 数组指针
 * @param count 元素数量
 * @return 操作结果
 */
ACCEL_API OperationResult batchAdd(
    ComputeContextHandle context,
    const f64* values,
    u64 count
);

/**
 * 获取当前累加结果
 * @param context 上下文句柄
 * @param outState 输出当前状态
 * @return 操作结果
 */
ACCEL_API OperationResult getState(
    ComputeContextHandle context,
    KahanState& outState
);

/**
 * 重置累加器
 * @param context 上下文句柄
 * @param newInitialSum 新初始值
 * @return 操作结果
 */
ACCEL_API OperationResult reset(
    ComputeContextHandle context,
    f64 newInitialSum = 0.0
);

/**
 * 销毁累加器
 * @param context 上下文句柄
 * @return 操作结果
 */
ACCEL_API OperationResult destroyContext(ComputeContextHandle context);

} // namespace kahan_accumulator

// ==================== 收敛监控器 ====================

namespace convergence_monitor {

/// 监控维度枚举
enum class Dimension : u32 {
    Position = 0,                    // 位置收敛
    Velocity = 1,                    // 速度收敛
    Energy = 2,                      // 能量收敛
    Quality = 3,                     // 质量收敛
    Count                            // 维度总数
};

/// 收敛状态
struct ConvergenceState {
    bool isConverged[4];              // 各维度是否收敛
    f32 residualNorm[4];              // 残差范数
    u32 iterationCount[4];            // 迭代次数
    f64 totalTimeNs[4];              // 各维度耗时
};

/**
 * 创建收敛监控器
 * @param tolerance 容差阈值
 * @param maxIterations 最大迭代次数
 * @param outContext 输出: 上下文句柄
 * @return 操作结果
 */
ACCEL_API OperationResult createContext(
    f32 tolerance,
    u32 maxIterations,
    ComputeContextHandle& outContext
);

/**
 * 检查单维度收敛性
 *
 * @param context 上下文句柄
 * @param dimension 监控维度
 * @param currentValue 当前值
 * @param targetValue 目标值
 * @param isConverged 输出: 是否收敛
 * @return 操作结果
 */
ACCEL_API OperationResult checkConvergence(
    ComputeContextHandle context,
    Dimension dimension,
    f32 currentValue,
    f32 targetValue,
    bool& isConverged
);

/**
 * 获取完整收敛状态
 * @param context 上下文句柄
 * @param state 输出状态
 * @return 操作结果
 */
ACCEL_API OperationResult getConvergenceState(
    ComputeContextHandle context,
    ConvergenceState& state
);

/**
 * 重置监控器
 * @param context 上下文句柄
 * @return 操作结果
 */
ACCEL_API OperationResult reset(ComputeContextHandle context);

/**
 * 销毁监控器
 * @param context 上下文句柄
 * @return 操作结果
 */
ACCEL_API OperationResult destroyContext(ComputeContextHandle context);

} // namespace convergence_monitor

} // namespace accel
} // namespace renderium
