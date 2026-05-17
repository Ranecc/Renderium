// Renderium - Blaze3D 优化模块
// 渲染 Pass 节点定义 - 帧图 DAG 分析的核心数据结构

package com.ranecc.renderium.feature.blaze3d;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渲染 Pass 节点
 * <p>
 * 表示帧图中的一个渲染通道，包含其资源依赖关系和执行元数据。
 * 用于构建 DAG（有向无环图）进行依赖分析和拓扑排序。
 *
 * <h3>字段说明：</h3>
 * <ul>
 *   <li>passId: 唯一标识符，由 RenderPassMixin 分配</li>
 *   <li>passName: 人类可读名称，用于调试和日志</li>
 *   <li>pipelineHash: 当前使用的 Pipeline 配置哈希</li>
 *   <li>inputResources: 此 Pass 读取的资源集合（纹理、Buffer 等）</li>
 *   <li>outputResources: 此 Pass 写入的资源集合（渲染目标等）</li>
 *   <li>dependencies: 此 Pass 依赖的其他 Pass ID 集合</li>
 *   <li>dependents: 依赖此 Pass 的其他 Pass ID 集合</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public class RenderPassNode {
    /** 唯一标识符（由 RenderPassMixin 分配） */
    final long passId;

    /** 人类可读的 Pass 名称 */
    final String passName;

    /** 当前绑定的 Pipeline 配置哈希 */
    volatile long pipelineHash;

    /** 输入资源集合：此 Pass 读取的资源（只读依赖） */
    final Set<Long> inputResources;

    /** 输出资源集合：此 Pass 写入的资源（写依赖） */
    final Set<Long> outputResources;

    /** 依赖的 Pass ID 集合（必须在当前 Pass 之前执行） */
    final Set<Long> dependencies;

    /** 被依赖的 Pass ID 集合（必须在当前 Pass 之后执行） */
    final Set<Long> dependents;

    /** 拓扑排序后的执行顺序索引（-1 表示未分配） */
    int topologyIndex;

    /** Pass 类型分类（用于状态切换优化） */
    PassType passType;

    /**
     * Pass 类型枚举
     * <p>用于状态切换优化：相同类型的 Pass 相邻时可减少切换开销
     */
    enum PassType {
        /** 几何渲染（不透明物体） */
        GEOMETRY_OPAQUE,
        /** 几何渲染（透明/半透明物体） */
        GEOMETRY_TRANSLUCENT,
        /** 后处理效果 */
        POST_PROCESS,
        /** 计算着色器 Pass */
        COMPUTE,
        /** 复制/Blit 操作 */
        COPY,
        /** 其他未分类 */
        UNKNOWN
    }

    /**
     * 创建渲染 Pass 节点
     *
     * @param passId       唯一标识符
     * @param passName     人类可读名称
     * @param pipelineHash 初始 Pipeline 哈希
     */
    RenderPassNode(long passId, String passName, long pipelineHash) {
        this.passId = passId;
        this.passName = passName;
        this.pipelineHash = pipelineHash;
        this.inputResources = ConcurrentHashMap.newKeySet();
        this.outputResources = ConcurrentHashMap.newKeySet();
        this.dependencies = ConcurrentHashMap.newKeySet();
        this.dependents = ConcurrentHashMap.newKeySet();
        this.topologyIndex = -1;
        this.passType = PassType.UNKNOWN;
    }

    /**
     * 添加输入资源（只读依赖）
     *
     * @param resourceId 资源句柄或唯一标识
     */
    void addInputResource(long resourceId) {
        inputResources.add(resourceId);
    }

    /**
     * 添加输出资源（写依赖）
     *
     * @param resourceId 资源句柄或唯一标识
     */
    void addOutputResource(long resourceId) {
        outputResources.add(resourceId);
    }

    /**
     * 添加对另一个 Pass 的依赖
     *
     * @param dependentPassId 被依赖的 Pass ID
     */
    void addDependency(long dependentPassId) {
        dependencies.add(dependentPassId);
    }

    /**
     * 获取此节点的总度数（入度 + 出度）
     *
     * @return 总边数
     */
    int getTotalDegree() {
        return dependencies.size() + dependents.size();
    }
}
