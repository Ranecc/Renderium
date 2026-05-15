package com.ranecc.renderium.feature.pipeline.node;

public abstract class Command {
    public abstract void execute();
    public void begin() {}
    public void end() {}
}
