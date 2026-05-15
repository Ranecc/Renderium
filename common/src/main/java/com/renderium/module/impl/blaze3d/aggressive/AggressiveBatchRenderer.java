package com.renderium.module.impl.blaze3d.aggressive;

public class AggressiveBatchRenderer {
    private static final AggressiveBatchRenderer INSTANCE = new AggressiveBatchRenderer();
    public static AggressiveBatchRenderer getInstance() { return INSTANCE; }
    public boolean enable() { return false; }
}
