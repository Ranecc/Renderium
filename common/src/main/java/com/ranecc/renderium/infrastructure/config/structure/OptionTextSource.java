package com.ranecc.renderium.infrastructure.config.structure;

import net.minecraft.network.chat.Component;

final class OptionTextSource {
    final RendererOption option;
    final RendererOptionGroup group;

    OptionTextSource(RendererOption option, RendererOptionGroup group) {
        this.option = option;
        this.group = group;
    }

    @Override
    public String toString() {
        return String.format("Option[id=%s, name=%s]",
            option instanceof SearchableOption s ? s.id() : "unknown",
            option.getName()
        );
    }
}
