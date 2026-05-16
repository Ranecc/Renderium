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
        return 0;
    }
}
