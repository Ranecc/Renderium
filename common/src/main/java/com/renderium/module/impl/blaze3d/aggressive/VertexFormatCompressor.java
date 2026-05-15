package com.renderium.module.impl.blaze3d.aggressive;

public class VertexFormatCompressor {
    private static final VertexFormatCompressor INSTANCE = new VertexFormatCompressor();
    public static VertexFormatCompressor getInstance() { return INSTANCE; }
    public boolean enable() { return false; }
}
