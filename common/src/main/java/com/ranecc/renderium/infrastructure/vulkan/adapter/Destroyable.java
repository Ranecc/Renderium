package com.renderium.vulkan.adapter;

/**
 * Destroyable - 可销毁资源接�?(26.2-snapshot-3 兼容)
 *
 * <p>标记可安全延迟销毁的 GPU 资源。实现此接口的资源可�? * 被加�?{@link DestructionQueue} 进行异步销毁，避免在渲染过程中
 * 直接删除导致的竞态条件�?/p>
 *
 * <h3>使用场景:</h3>
 * <ul>
 *   <li>Vulkan Buffer、Texture、Sampler 等资�?/li>
 *   <li>Vulkan Pipeline、DescriptorSet 等重型对�?/li>
 *   <li>任何需要显式释放的 GPU 资源</li>
 * </ul>
 *
 * @see DestructionQueue
 */
public interface Destroyable {

    /**
     * 销毁底层资�?     *
     * <p>释放所有关联的 GPU 资源（如 VkBuffer, VkImage, VkDeviceMemory 等）�?     * 此方法应保证幂等性：多次调用不会产生副作用�?/p>
     *
     * @apiNote 应在 {@link DestructionQueue#processPendingDestructions(long)} 中调用，
     *       或在设备关闭时手动调�?     */
    void destroy();

    /**
     * 检查资源是否已被销�?     *
     * @return true 如果 destroy() 已被调用且资源已释放
     */
    boolean isDestroyed();
}
