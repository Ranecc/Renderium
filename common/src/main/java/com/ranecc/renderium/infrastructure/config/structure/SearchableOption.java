package com.ranecc.renderium.infrastructure.config.structure;

import net.minecraft.resources.Identifier;

public interface SearchableOption extends RendererOption {
    Identifier getId();
    boolean isEnabled(ConfigState state);
    void registerTextSources(SearchIndex index, Object modOptions, RendererOptionGroup optionGroup);
    String id();
}
