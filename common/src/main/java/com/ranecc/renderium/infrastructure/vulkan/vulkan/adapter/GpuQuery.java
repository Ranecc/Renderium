package com.ranecc.renderium.infrastructure.vulkan.vulkan.adapter;

/**
 * GpuQuery - GPU 查询接口 (26.2-snapshot-3 兼容)
 *
 * <p>表示一个活跃的 GPU 性能查询对象（如时间戳查询），
 * 用于测量 GPU 操作的耗时。</p>
 *
 * <h3>典型用法:</h3>
 * <pre>
 *   GpuQuery query = queryPool.allocateQuery();
 *   commandEncoder.beginQuery(query);   // 在命令编码开始时
 *   // ... 执行 GPU 操作 ...
 *   commandEncoder.endQuery(query);     // 在命令编码结束时
 *
 *   // 稍后查询结果:
 *   if (query.isReady()) {
 *       long nanos = query.getResultNanoseconds();
 *   }
 * </pre>
 */
public interface GpuQuery {

    /**
     * 检查查询结果是否已就绪
     *
     * @return true 如果 GPU 已完成查询并可以读取结果
     */
    boolean isReady();

    /**
     * 获取查询结果（纳秒）
     *
     * <p>仅当 {@link #isReady()} 返回 true 时调用才有效。</p>
     *
     * @return 查询结果的纳秒数，如果尚未就绪则返回 -1
     */
    long getResultNanoseconds();

    /**
     * 重置查询以便复用
     */
    void reset();
}
