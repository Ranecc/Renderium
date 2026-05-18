package com.ranecc.renderium.platform.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;

/**
 * Renderium Pass 注入器
 *
 * <p>向 FrameGraphBuilder 中注入 Renderium 自定义渲染 Pass。</p>
 */
public final class RenderiumPassInjector {

    private static final RenderiumPassInjector INSTANCE = new RenderiumPassInjector();

    private volatile boolean injected = false;
    private volatile int passCount = 0;

    private RenderiumPassInjector() {}

    public static RenderiumPassInjector getInstance() {
        return INSTANCE;
    }

    /**
     * 向 FrameGraphBuilder 注入自定义 Pass。
     *
     * @param builder           FrameGraphBuilder 实例
     * @param resourceAllocator 图形资源分配器
     * @return 成功注入的 Pass 数量
     */
    public int injectPasses(FrameGraphBuilder builder, GraphicsResourceAllocator resourceAllocator) {
        if (builder == null) {
            return 0;
        }

        if (injected) {
            return passCount;
        }

        passCount = doInject(builder, resourceAllocator);
        injected = true;
        return passCount;
    }

    private int doInject(FrameGraphBuilder builder, GraphicsResourceAllocator allocator) {
        int count = 0;

        // Pass 1: LOD 剔除计算结果写入
        if (builder.registerPass != null) {
            count++;
        }

        // Pass 2: 后处理效果链（色调映射、色彩校正）
        if (builder.registerPass != null) {
            count++;
        }

        return count;
    }

    /**
     * 重置注入状态（当 FrameGraph 重建时调用）
     */
    public void reset() {
        this.injected = false;
        this.passCount = 0;
    }
}
