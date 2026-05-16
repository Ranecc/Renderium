package com.ranecc.renderium.infrastructure.config.structure;

import net.minecraft.network.chat.Component;

public interface RendererPage {
    Component getName();
    void registerTextSources(SearchIndex index, Object modOptions);
}
