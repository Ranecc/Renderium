package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class NativeLibraryLoader {
    private static final Logger LOGGER = Logger.getLogger(NativeLibraryLoader.class.getName());
    public static NativeLibraryLoader INSTANCE = new NativeLibraryLoader();

    private boolean loaded;
    private final Map<String, MethodHandle> methodHandleCache = new ConcurrentHashMap<>();

    public static NativeLibraryLoader getInstance() { return INSTANCE; }

    public boolean load(String libraryName) {
        try {
            System.loadLibrary(libraryName);
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.severe("Failed to load native library: " + libraryName + " - " + e.getMessage());
            loaded = false;
        }
        return loaded;
    }

    public boolean isLoaded() { return loaded; }

    public MethodHandle get(String functionName, FunctionDescriptor descriptor) {
        return methodHandleCache.computeIfAbsent(functionName, key -> {
            try {
                return SymbolLookup.loaderLookup().find(key)
                    .map(addr -> Linker.nativeLinker().downcallHandle(addr, descriptor))
                    .orElse(null);
            } catch (Exception e) {
                LOGGER.severe("Failed to get native function: " + key + " - " + e.getMessage());
                return null;
            }
        });
    }
}
