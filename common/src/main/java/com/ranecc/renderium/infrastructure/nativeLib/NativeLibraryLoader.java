// Renderium Accelerator - 原生库加载与FFM基础设施
// 加载优先级: JAR内嵌资源提取 > 本地路径搜索 > 系统library.path > loaderLookup
package com.renderium.accel;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 原生库加载器 - Panama FFM 基础设施
 *
 * <p>加载策略（按优先级）:
 * <ol>
 *   <li>JAR内嵌原生库 → 运行时释放到临时目录 → SymbolLookup.libraryLookup()</li>
 *   <li>本地路径搜索 (./native, ./lib, ../lib)</li>
 *   <li>系统 java.library.path</li>
 *   <li>ClassLoader loaderLookup (标准JNI方式)</li>
 * </ol>
 *
 * @author Renderium Team
 */
final class NativeLibraryLoader {

    private static final Logger LOGGER = Logger.getLogger(NativeLibraryLoader.class.getName());

    /** 库标识名 */
    private static final String LIBRARY_NAME = "renderium_accel";

    /** 本地搜索路径列表 */
    private static final String[] LOCAL_SEARCH_PATHS = {
        "./native", "./lib", "../lib",
        System.getProperty("java.library.path", ""),
        System.getProperty("user.dir", "") + "/lib"
    };

    /** FFM Linker 实例 */
    private final Linker linker;

    /** 已加载的原生库符号查找表 */
    private final SymbolLookup nativeLib;

    /** 方法句柄缓存 (避免重复创建) */
    private final ConcurrentHashMap<String, MethodHandle> methodHandles = new ConcurrentHashMap<>();

    /** 原生库提取器 */
    private final NativeLibraryExtractor extractor;

    /** 实际加载来源 (用于诊断) */
    private String loadSource = "unknown";

    /**
     * 创建原生库加载器
     * 自动尝试所有加载策略，直到成功或全部失败
     */
    NativeLibraryLoader() {
        this.linker = Linker.nativeLinker();
        this.extractor = NativeLibraryExtractor.create();
        this.nativeLib = loadWithStrategy();
    }

    // ==================== 公共API ====================

    /** 获取 FFM Linker */
    Linker getLinker() { return linker; }

    /** 获取原生库符号查找表 */
    SymbolLookup getNativeLib() { return nativeLib; }

    /** 获取原生库提取器 */
    NativeLibraryExtractor getExtractor() { return extractor; }

    /**
     * 获取指定函数的 MethodHandle (带缓存)
     *
     * @param name 函数名称
     * @param descriptor 函数签名描述符
     * @return 可调用的 MethodHandle
     * @throws NoSuchMethodError 如果函数不存在
     */
    MethodHandle get(String name, FunctionDescriptor descriptor) {
        return methodHandles.computeIfAbsent(name, k -> {
            MemorySegment symbol = nativeLib.find(k).orElseThrow(
                () -> new NoSuchMethodError("找不到原生函数: " + k + " [来源=" + loadSource + "]")
            );
            return linker.downcallHandle(symbol, descriptor);
        });
    }

    /**
     * 检查指定符号是否存在
     *
     * @param name 符号名称
     * @return true 如果存在
     */
    boolean hasSymbol(String name) {
        return nativeLib.find(name).isPresent();
    }

    /** 获取加载来源描述 */
    String getLoadSource() { return loadSource; }

    // ==================== 加载策略 ====================

    /**
     * 多策略加载原生库
     *
     * <p>按优先级依次尝试，成功即返回
     *
     * @return 成功加载的 SymbolLookup
     * @throws UnsatisfiedLinkError 所有策略均失败
     */
    private SymbolLookup loadWithStrategy() {
        UnsatisfiedLinkError lastError = null;

        // 策略1: 从JAR内嵌资源提取 (最高优先)
        try {
            SymbolLookup lib = loadFromEmbedded();
            if (lib != null) {
                loadSource = "embedded-jar";
                LOGGER.info("✓ 通过JAR内嵌资源加载原生库");
                return lib;
            }
        } catch (UnsatisfiedLinkError e) {
            lastError = e;
            LOGGER.fine("JAR内嵌资源不可用: " + e.getMessage());
        }

        // 策略2: 本地文件系统搜索
        try {
            SymbolLookup lib = loadFromLocalPaths();
            if (lib != null) {
                loadSource = "local-path";
                LOGGER.info("✓ 通过本地路径加载原生库");
                return lib;
            }
        } catch (UnsatisfiedLinkError e) {
            if (lastError == null) lastError = e;
            LOGGER.fine("本地路径未找到: " + e.getMessage());
        }

        // 策略3: ClassLoader loaderLookup
        try {
            SymbolLookup lib = SymbolLookup.loaderLookup();
            lib.find("accel_initialize").orElseThrow(
                () -> new UnsatisfiedLinkError("loaderLookup中未找到accel_initialize")
            );
            loadSource = "classloader";
            LOGGER.info("✓ 通过ClassLoader加载原生库");
            return lib;
        } catch (Exception ignored) {}

        // 全部失败
        throw lastError != null ? lastError :
            new UnsatisfiedLinkError("无法找到原生库: " + LIBRARY_NAME +
                " (已尝试: JAR内嵌/本地路径/systempath/loaderLookup)");
    }

    /**
     * 策略1: 从JAR内嵌资源提取并加载
     *
     * @return SymbolLookup 或 null (如果JAR中无此资源)
     */
    private SymbolLookup loadFromEmbedded() {
        if (!extractor.isEmbedded(LIBRARY_NAME)) {
            return null;
        }

        Path extractedPath = extractor.extract(LIBRARY_NAME);
        if (extractedPath == null) {
            throw new UnsatisfiedLinkError("JAR内嵌资源提取失败");
        }

        try {
            SymbolLookup lib = SymbolLookup.libraryLookup(extractedPath.toString(), Arena.ofAuto());
            // 验证关键符号存在
            lib.find("accel_initialize").orElseThrow(
                () -> new UnsatisfiedLinkError("提取的库缺少accel_initialize符号")
            );
            return lib;
        } catch (UnsatisfiedLinkError e) {
            throw e;
        }
    }

    /**
     * 策略2: 从本地文件系统路径搜索并加载
     *
     * @return SymbolLookup 或 null
     */
    private SymbolLookup loadFromLocalPaths() {
        for (String path : LOCAL_SEARCH_PATHS) {
            if (path == null || path.isEmpty()) continue;
            try {
                String libPath = path + "/" + System.mapLibraryName(LIBRARY_NAME);
                SymbolLookup lib = SymbolLookup.libraryLookup(libPath, Arena.ofAuto());
                lib.find("accel_initialize").orElseThrow(
                    () -> new UnsatisfiedLinkError("符号未找到: " + libPath)
                );
                return lib;
            } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
                continue;
            }
        }
        return null;
    }
}
