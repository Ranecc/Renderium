package com.renderium.bridge.video;

/**
 * 存储事件处理器函数式接口
 * <p>
 * 用于处理配置保存后的回调操作，例如将更改刷新到磁盘。
 * 通过 {@link BooleanOptionBuilder#setStorageHandler(StorageHandler)} 等方法绑定到具体选项。
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li><b>轻量级</b>：接口仅包含一个方法，可作为 Lambda 表达式使用</li>
 *   <li><b>可选性</b>：不是每个选项都需要存储处理器</li>
 *   <li><b>时机保证</b>：在所有选项值保存到绑定后调用</li>
 * </ul>
 *
 * <h4>使用示例：</h4>
 * <pre>{@code
 * builder.createBooleanOption(id)
 *     .setBinding(setter, getter)
 *     .setStorageHandler(() -> {
 *         // 将配置写入文件
 *         configManager.save();
 *     });
 * }</pre>
 *
 * @see BooleanOptionBuilder#setStorageHandler(StorageHandler)
 * @see IntegerOptionBuilder#setStorageHandler(StorageHandler)
 * @see EnumOptionBuilder#setStorageHandler(StorageHandler)
 * @since 1.0.0
 */
@FunctionalInterface
public interface StorageHandler {

    /**
     * 在选项值保存到存储机制后调用
     * <p>
     * 典型用途包括：
     * <ul>
     *   <li>将内存中的配置刷新到磁盘文件</li>
     *   <li>通知其他模块配置已更新</li>
     *   <li>触发配置同步操作</li>
     *   <li>记录配置变更日志</li>
     * </ul>
     * <p>
     * 性能要求：此方法应快速完成（&lt; 10ms），
     * 避免阻塞主线程。如果需要执行耗时操作，
     * 应考虑异步处理。
     *
     * @throws Exception 如果保存过程中发生错误（建议捕获并记录）
     */
    void save();
}
