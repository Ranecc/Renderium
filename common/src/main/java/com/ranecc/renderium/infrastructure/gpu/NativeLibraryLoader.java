package com.ranecc.renderium.infrastructure.gpu;

/** NativeLibraryLoader stub */
public class NativeLibraryLoader {
    public static NativeLibraryLoader INSTANCE = new NativeLibraryLoader();
    public static NativeLibraryLoader getInstance() { return INSTANCE; }
    public boolean load(String name) { return false; }
    public boolean isLoaded() { return false; }
}
