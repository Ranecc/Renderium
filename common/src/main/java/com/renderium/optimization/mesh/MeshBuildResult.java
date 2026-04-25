// Renderium - 网格构建结果
// 多线程网格构建的输出，包含构建完成的 MeshData 和性能指标

package com.renderium.optimization.mesh;

/**
 * 网格构建结果。
 *
 * <p>Worker 线程完成构建后产生的结果对象，包含：
 * <ul>
 *   <li>原始任务的引用（用于关联区段位置）</li>
 *   <li>构建完成的 {@link MeshData}</li>
 *   <li>构建耗时</li>
 * </ul>
 *
 * <p>结果是不可变的，主线程可以安全地读取。
 *
 * @see MeshBuildTask
 * @see MeshBuildScheduler
 * @author Renderium Team
 * @since 1.0.0
 */
public final class MeshBuildResult {

    /** 原始构建任务 */
    public final MeshBuildTask task;

    /** 构建完成的网格数据 */
    public final MeshData meshData;

    /** 构建耗时（纳秒） */
    public final long buildTimeNs;

    /**
     * 创建构建结果
     *
     * @param task 原始任务
     * @param meshData 构建完成的网格数据
     * @param buildTimeNs 构建耗时（纳秒）
     */
    public MeshBuildResult(MeshBuildTask task, MeshData meshData, long buildTimeNs) {
        this.task = task;
        this.meshData = meshData;
        this.buildTimeNs = buildTimeNs;
    }

    /** 获取区段 X 索引 */
    public int getSectionX() { return task.sectionX; }

    /** 获取区段 Y 索引 */
    public int getSectionY() { return task.sectionY; }

    /** 获取区段 Z 索引 */
    public int getSectionZ() { return task.sectionZ; }

    /** 获取构建耗时（毫秒） */
    public double getBuildTimeMs() {
        return buildTimeNs / 1_000_000.0;
    }

    @Override
    public String toString() {
        return String.format(
                "MeshBuildResult{pos=(%d,%d,%d), vertices=%d, indices=%d, time=%.2fms}",
                task.sectionX, task.sectionY, task.sectionZ,
                meshData.getVertexCount(), meshData.getIndexCount(),
                getBuildTimeMs()
        );
    }
}
