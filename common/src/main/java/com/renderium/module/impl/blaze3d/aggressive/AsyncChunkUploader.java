package com.renderium.module.impl.blaze3d.aggressive;

public class AsyncChunkUploader {
    private static final AsyncChunkUploader INSTANCE = new AsyncChunkUploader();
    public static AsyncChunkUploader getInstance() { return INSTANCE; }
    public boolean enable() { return false; }
}
