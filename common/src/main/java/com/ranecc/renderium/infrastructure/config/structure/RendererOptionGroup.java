package com.ranecc.renderium.infrastructure.config.structure;

import java.util.List;

public final class RendererOptionGroup {
    private final List<RendererOption> options;
    private final net.minecraft.network.chat.Component name;

    public RendererOptionGroup(List<RendererOption> options) {
        this.options = options != null ? options : List.of();
        this.name = null;
    }

    public RendererOptionGroup(net.minecraft.network.chat.Component name, List<RendererOption> options) {
        this.options = options != null ? options : List.of();
        this.name = name;
    }

    public List<RendererOption> options() {
        return options;
    }

    public net.minecraft.network.chat.Component name() {
        return name;
    }
}
