package com.renderium.module.impl.blaze3d.memory;

public class VmaDeferredDeallocation {
    private static final VmaDeferredDeallocation INSTANCE = new VmaDeferredDeallocation();
    public static VmaDeferredDeallocation getInstance() { return INSTANCE; }
    public void initialize() {}
}
