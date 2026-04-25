// Renderium - GraphicsResourceAllocator 适配接口
// 为 Minecraft 26.1.2 提供与 26.2+ 兼容的资源分配器接口
//
// 注意：这是一个适配层接口，用于在 26.1.2 中模拟 26.2+ 的 GraphicsResourceAllocator 行为
// 当运行在 26.2+ 时，应该使用 Mojang 的原生实现

package com.renderium.module.impl.blaze3d;

/**
 * 图形资源分配器接口
 * <p>
 * 模拟 Minecraft 26.2+ 的 com.mojang.blaze3d.resource.GraphicsResourceAllocator 接口。
 * 用于在 26.1.2 环境中提供兼容性支持。
 *
 * <h3>设计目的：</h3>
 * <ul>
 *   <li>在 26.1.2 中提供与 26.2+ 类似的资源分配接口</li>
 *   <li>允许 InstrumentedResourceAllocator 在不修改的情况下工作</li>
 *   <li>为未来迁移到官方 API 做准备</li>
 * </ul>
 *
 * <h3>适配策略：</h3>
 * <ul>
 *   <li>26.1.2: 使用此接口的 Renderium 实现</li>
 *   <li>26.2+: 委托给 Mojang 的官方实现</li>
 * </ul>
 *
 * @see InstrumentedResourceAllocator
 * @author Renderium Team
 * @since 2.0.0
 */
public interface GraphicsResourceAllocator {

    /**
     * 获取图形资源
     *
     * @param <T>        资源类型泛型参数
     * @param descriptor 资源描述符，包含资源的类型、大小、用途等信息
     * @return 获取到的资源实例
     * @throws NullPointerException 如果 descriptor 为 null
     */
    <T> T acquire(ResourceDescriptor<T> descriptor);

    /**
     * 释放图形资源
     *
     * @param <T>      资源类型泛型参数
     * @param resource 要释放的资源实例
     * @throws NullPointerException 如果 resource 为 null
     */
    <T> void release(T resource);

    /**
     * 资源描述符
     * <p>
     * 描述所需资源的特征，包括类型、大小、用途等。
     */
    interface ResourceDescriptor<T> {
        /**
         * 获取资源类型
         *
         * @return 资源类型字符串
         */
        String getType();

        /**
         * 获取资源大小（字节）
         *
         * @return 资源大小，如果未知返回 -1
         */
        long getSize();

        /**
         * 获取资源用途
         *
         * @return 资源用途描述
         */
        String getUsage();
    }
}
