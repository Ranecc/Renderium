package com.ranecc.renderium.feature.blaze3d.memory;

import java.util.logging.Logger;

/**
 * 池化图形资源分配器（已废弃）
 *
 * <p>功能已合并到 {@link InstrumentedResourceAllocator}，此类保留仅做桥接兼容。
 * 新建代码应直接使用 {@link InstrumentedResourceAllocator}。
 *
 * <p>使用方式保持不变：
 * <pre>{@code
 * GraphicsResourceAllocator pooled = PooledGraphicsResourceAllocator.wrap(original);
 * }</pre>
 * 内部委托给 {@link InstrumentedResourceAllocator} 实现。
 */
@Deprecated
public final class PooledGraphicsResourceAllocator implements GraphicsResourceAllocator {

    private static final Logger LOGGER = Logger.getLogger(PooledGraphicsResourceAllocator.class.getName());

    /** 内部委托的 InstrumentedResourceAllocator */
    private final InstrumentedResourceAllocator delegate;

    /**
     * 创建池化分配器。
     *
     * @param backingAllocator 底层原始分配器
     */
    public PooledGraphicsResourceAllocator(GraphicsResourceAllocator backingAllocator) {
        if (backingAllocator == null) {
            throw new IllegalArgumentException("backingAllocator 不能为 null");
        }
        this.delegate = new InstrumentedResourceAllocator(backingAllocator);
        LOGGER.info("PooledGraphicsResourceAllocator 委托给 InstrumentedResourceAllocator");
    }

    /**
     * 包装现有分配器为池化版本。
     */
    public static PooledGraphicsResourceAllocator wrap(GraphicsResourceAllocator original) {
        if (original == null) return null;
        return new PooledGraphicsResourceAllocator(original);
    }

    @Override
    public <T> T acquire(ResourceDescriptor<T> descriptor) {
        return delegate.acquire(descriptor);
    }

    @Override
    public <T> void release(T resource) {
        delegate.release(resource);
    }

    /** 获取底层 InstrumentedResourceAllocator */
    public InstrumentedResourceAllocator getDelegate() { return delegate; }
}
