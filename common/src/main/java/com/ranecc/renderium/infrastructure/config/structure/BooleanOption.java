package com.ranecc.renderium.infrastructure.config.structure;

public class BooleanOption implements RendererOption {
    private boolean value;

    public BooleanOption() {}

    public boolean getValue() { return value; }
    public void setValue(boolean v) { this.value = v; }
    public boolean isEnabled(ConfigState state) { return true; }

    @Override
    public net.minecraft.network.chat.Component getName() {
        return net.minecraft.network.chat.Component.literal("BooleanOption");
    }
}
