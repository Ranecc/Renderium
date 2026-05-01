package com.ranecc.renderium.infrastructure.vulkan.vulkan.adapter;

/**
 * GpuQueryPool - GPU 查询池接口 (26.2-snapshot-3 兼容)
 *
 * <p>管理 GPU 查询对象的分配和回收。
 * 查询池预分配一组查询对象，避免运行时分配开销。</p>
 *
 * <h3>生命周期:</h3>
 * <pre>
 *   GpuQueryPool pool = device.createQueryPool(GpuQueryType.TIMESTAMP, 64);
 *
 *   // 分配查询
 *   GpuQuery query = pool.allocateQuery();
 *
 *   // 使用后归还（可选，池会自动管理）
 *   pool.freeQuery(query);
 *
 *   // 设备关闭时
 *   pool.destroy();
 * </pre>
 */
public interface GpuQueryPool {

    /**
     * 查询类型枚举
     */
    enum QueryType {
        /** 时间戳查询 - 用于 GPU 计时 */
        TIMESTAMP,
        /** 遮挡查询 - 用于可见性测试 */
        OCCLUSION,
        /** 管线统计查询 - 用于性能分析 */
        PIPELINE_STATISTICS
    }

    /**
     * 从池中分配一个查询对象
     *
     * @return 可用的查询对象，如果池已耗尽则返回 null
     */
    GpuQuery allocateQuery();

    /**
     * 归还查询到池中以供复用
     *
     * @param query 要归还的查询（不能为 null）
     */
    void freeQuery(GpuQuery query);

    /**
     * 重置池中所有查询
     *
     * <p>通常在每帧开始时调用。</p>
     */
    void reset();

    /**
     * 获取池中可用查询数量
     *
     * @return 当前可分配的查询数量
     */
    int getAvailableCount();

    /**
     * 销毁查询池及所有关联的查询对象
     */
    void destroy();
}
