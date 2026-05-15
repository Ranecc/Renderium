package com.renderium.module.impl.blaze3d.transform;

public class StaticGeometryCache {
    private static final StaticGeometryCache INSTANCE = new StaticGeometryCache();
    public static StaticGeometryCache getInstance() { return INSTANCE; }
    public boolean enable() { return false; }
}
