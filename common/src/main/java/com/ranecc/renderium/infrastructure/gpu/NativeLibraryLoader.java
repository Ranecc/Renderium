package com.ranecc.renderium.infrastructure.gpu;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        if (loaded) return true;

        // 策略 1: 从 java.library.path 加载
        try {
            System.loadLibrary(libraryName);
            loaded = true;
            return true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.fine("System.loadLibrary(" + libraryName + ") 失败，尝试从 classpath 提取: " + e.getMessage());
        }

        // 策略 2: 从 classpath 资源目录提取并加载
        try {
            String osArch = System.getProperty("os.arch").toLowerCase();
            String osName = System.getProperty("os.name").toLowerCase();
            String libDir;
            String libExtension;

            if (osName.contains("win")) {
                libDir = osArch.contains("64") ? "windows-x64" : "windows-x86";
                libExtension = ".dll";
            } else if (osName.contains("linux")) {
                libDir = osArch.contains("64") ? "linux-x64" : "linux-x86";
                libExtension = ".so";
            } else if (osName.contains("mac")) {
                libDir = "macos-x64";
                libExtension = ".dylib";
            } else {
                LOGGER.severe("不支持的操作系统: " + osName);
                return false;
            }

            String resourcePath = "/native/" + libDir + "/" + libraryName + libExtension;
            Path tempDir = Files.createTempDirectory("renderium_native_");
            tempDir.toFile().deleteOnExit();
            Path tempFile = tempDir.resolve(libraryName + libExtension);
            tempFile.toFile().deleteOnExit();

            try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    LOGGER.severe("找不到原生库资源: " + resourcePath);
                    return false;
                }
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            System.load(tempFile.toAbsolutePath().toString());
            loaded = true;
            LOGGER.info("从资源提取加载原生库成功: " + libraryName + libExtension);
            return true;
        } catch (Exception e) {
            LOGGER.severe("原生库加载失败: " + libraryName + " - " + e.getMessage());
            loaded = false;
            return false;
        }
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
