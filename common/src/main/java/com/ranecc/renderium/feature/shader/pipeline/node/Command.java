package com.ranecc.renderium.feature.shader.pipeline.node;

public abstract class Command {
    public abstract void execute();
    public void begin() {}
    public void end() {}
}
