// Renderium - 原生库运行时提取器
// 将C++加速库、Streamline SDK等DLL/SO打包进JAR，运行时释放到临时目录加载
//
// 资源布局:
// resources/native/{os}-{arch}/
//   ├── renderium_accel.{dll,so,dylib}
//   └── (Streamline SDK DLLs 由 SLAutoExtractor 单独管理)
//
// 提取目标:
//   {java.io.tmpdir}/renderium-native-{pid}/
package com.renderium.accel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 原生库运行时提取器
 *
 * <p>设计原则:
 * <ul>
 *   <li>所有原生二进制文件打包在 JAR 的 resources/native/ 目录下</li>
 *   <li>首次使用时自动提取到系统临时目录</li>
 *   <li>进程退出时自动清理（通过 shutdown hook）</li>
 *   <li>支持多平台: Windows(x64), Linux(x64), macOS(arm64/x64)</li>
 * </ul>
 *
 * <p>生命周期:
 * <pre>
 * JAR 内嵌资源 → extract() → 临时目录 → System.load() / SymbolLookup.libraryLookup()
 * </pre>
 *
 * @author Renderium Team
 */
public final class NativeLibraryExtractor {

    private static final Logger LOGGER = Logger.getLogger("Renderium-NativeExtractor");

    /** JAR内原生资源根路径 */
    private static final String NATIVE_RESOURCE_ROOT = "/native/";

    /** 提取目录前缀 */
    private static final String EXTRACTION_PREFIX = "renderium-native-";

    /** 当前平台标识符 (os-arch) */
    private final String platformId;

    /** JAR内该平台的资源路径 */
    private final String platformResourcePath;

    /** 运行时提取目标目录 */
    private final Path extractionDir;

    /** 已提取文件缓存 (避免重复IO) */
    private final ConcurrentHashMap<String, Path> extractedFiles = new ConcurrentHashMap<>();

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /**
     * 创建原生库提取器
     *
     * @return 平台检测后的提取器实例
     */
    public static NativeLibraryExtractor create() {
        return new NativeLibraryExtractor(detectPlatform());
    }

    /**
     * 使用指定平台创建提取器（测试用）
     *
     * @param platform 平台标识符 (如 "windows-x64")
     */
    public static NativeLibraryExtractor forPlatform(String platform) {
        return new NativeLibraryExtractor(platform);
    }

    private NativeLibraryExtractor(String platform) {
        this.platformId = Objects.requireNonNull(platform);
        this.platformResourcePath = NATIVE_RESOURCE_ROOT + platform + "/";
        this.extractionDir = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve(EXTRACTION_PREFIX + ProcessHandle.current().pid());

        // 注册 shutdown hook 清理临时文件
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup, "Renderium-NativeCleanup"));
    }

    // ==================== 核心API ====================

    /**
     * 初始化提取环境（创建目录）
     *
     * @return true 如果初始化成功
     */
    public boolean initialize() {
        if (initialized) return true;
        try {
            Files.createDirectories(extractionDir);
            initialized = true;
            LOGGER.fine("Native extraction dir: " + extractionDir);
            return true;
        } catch (IOException e) {
            LOGGER.warning("无法创建提取目录: " + e.getMessage());
            return false;
        }
    }

    /**
     * 提取指定原生库到临时目录
     *
     * @param libraryName 库名称不含扩展名 (如 "renderium_accel")
     * @return 提取后的绝对路径，如果提取失败返回 null
     */
    public Path extract(String libraryName) {
        if (!initialized && !initialize()) {
            return null;
        }

        // 检查缓存
        Path cached = extractedFiles.get(libraryName);
        if (cached != null && Files.exists(cached)) {
            return cached;
        }

        String mappedName = System.mapLibraryName(libraryName);
        String resourcePath = platformResourcePath + mappedName;

        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                LOGGER.fine("原生库未嵌入JAR: " + resourcePath +
                           " (平台=" + platformId + ", 尝试从系统路径加载)");
                return null;
            }

            Path target = extractionDir.resolve(mappedName);

            // 写入临时文件
            copyStream(in, target);

            // 设置可执行权限 (Linux/macOS)
            if (!isWindows()) {
                try {
                    String[] chmod = {"/bin", "chmod", "+x", target.toString()};
                    new ProcessBuilder(chmod).start().waitFor();
                } catch (Exception ignored) {}
            }

            extractedFiles.put(libraryName, target.toAbsolutePath());
            LOGGER.info("提取原生库: " + libraryName + " -> " + target);
            return target.toAbsolutePath();

        } catch (IOException e) {
            LOGGER.warning("提取原生库失败: " + libraryName + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 提取并获取 SymbolLookup（供 Panama FFM 使用）
     *
     * @param libraryName 库名称
     * @return SymbolLookup 实例，失败返回空
     */
    /** 空SymbolLookup (始终找不到任何符号) */
    private static final SymbolLookup EMPTY_LOOKUP = name -> java.util.Optional.empty();

    public SymbolLookup extractAndLookup(String libraryName) {
        Path libPath = extract(libraryName);
        if (libPath == null) {
            return EMPTY_LOOKUP;
        }
        try {
            return SymbolLookup.libraryLookup(libPath.toString(), Arena.ofAuto());
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warning("加载提取的原生库失败: " + libPath + " - " + e.getMessage());
            return EMPTY_LOOKUP;
        }
    }

    /**
     * 检查指定库是否已嵌入JAR
     *
     * @param libraryName 库名称
     * @return true 如果资源存在
     */
    public boolean isEmbedded(String libraryName) {
        String mappedName = System.mapLibraryName(libraryName);
        String resourcePath = platformResourcePath + mappedName;
        return getClass().getResourceAsStream(resourcePath) != null;
    }

    // ==================== 批量操作 ====================

    /**
     * 提取平台目录下所有原生库
     *
     * @return 成功提取的文件路径列表
     */
    public java.util.List<Path> extractAll() {
        java.util.List<Path> results = new java.util.ArrayList<>();
        if (!initialized && !initialize()) {
            return results;
        }

        try {
            var resourceList = listResources(platformResourcePath);
            for (String resource : resourceList) {
                String fileName = resource.substring(resource.lastIndexOf('/') + 1);
                Path target = extractionDir.resolve(fileName);

                try (InputStream in = getClass().getResourceAsStream(resource)) {
                    if (in != null) {
                        copyStream(in, target);
                        results.add(target.toAbsolutePath());
                        LOGGER.fine("批量提取: " + fileName);
                    }
                }
            }
            LOGGER.info("批量提取完成: " + results.size() + " 个文件");
        } catch (IOException e) {
            LOGGER.warning("批量提取失败: " + e.getMessage());
        }

        return results;
    }

    // ==================== 查询 ====================

    /** 获取当前平台ID */
    public String getPlatformId() { return platformId; }

    /** 获取提取目录 */
    public Path getExtractionDir() { return extractionDir; }

    /** 是否为Windows平台 */
    public boolean isWindows() { return platformId.startsWith("windows"); }

    /** 是否已初始化 */
    public boolean isInitialized() { return initialized; }

    // ==================== 清理 ====================

    /**
     * 清理所有提取的临时文件
     * 通常由 shutdown hook 自动调用
     */
    public void cleanup() {
        if (!Files.isDirectory(extractionDir)) return;

        try {
            Files.walk(extractionDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {}
                    });
            LOGGER.fine("清理完成: " + extractionDir);
        } catch (IOException ignored) {}

        extractedFiles.clear();
        initialized = false;
    }

    // ==================== 内部方法 ====================

    /**
     * 检测当前操作系统和架构
     *
     * @return 平台标识符: "{os}-{arch}"
     */
    private static String detectPlatform() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        String arch = System.getProperty("os.arch", "unknown").toLowerCase();

        // 归一化OS名称
        if (os.contains("win")) os = "windows";
        else if (os.contains("mac") || os.contains("darwin")) os = "macos";
        else if (os.contains("nux") || os.contains("nix")) os = "linux";
        else os = "unknown";

        // 归一化架构名称
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) arch = "x64";
        else if (arch.contains("aarch64") || arch.contains("arm64")) arch = "arm64";
        else if (arch.contains("arm")) arch = "arm32";
        else arch = "unknown";

        return os + "-" + arch;
    }

    /**
     * 复制输入流到目标文件
     */
    private void copyStream(InputStream in, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    /**
     * 列出classpath下指定路径的所有资源
     */
    private java.util.List<String> listResources(String basePath) throws IOException {
        java.util.List<String> result = new java.util.ArrayList<>();
        ClassLoader loader = getClass().getClassLoader();

        // 尝试从 classpath 枚举资源
        var urls = loader.getResources(basePath);
        while (urls.hasMoreElements()) {
            var url = urls.nextElement();
            String protocol = url.getProtocol();

            if ("jar".equals(protocol)) {
                // JAR 文件中的资源 - 需要枚举
                String jarPath = url.getPath();
                int bangIndex = jarPath.indexOf('!');
                if (bangIndex > 0) {
                    String jarFile = jarPath.substring(5, bangIndex); // 去掉 file:
                    String entryPrefix = jarPath.substring(bangIndex + 1);
                    try (var jarFileSystem = java.nio.file.FileSystems.newFileSystem(
                            java.net.URI.create("jar:file:" + jarFile), java.util.Map.of())) {
                        Path root = jarFileSystem.getPath(entryPrefix);
                        if (Files.isDirectory(root)) {
                            Files.walk(root)
                                    .filter(p -> !Files.isDirectory(p))
                                    .forEach(p -> result.add(basePath + root.relativize(p).toString()));
                        }
                    }
                }
            } else if ("file".equals(protocol)) {
                // 开发模式下的文件系统资源
                try {
                    Path dir = Path.of(url.toURI());
                    if (Files.isDirectory(dir)) {
                        Files.walk(dir)
                                .filter(p -> !Files.isDirectory(p))
                                .forEach(p -> result.add(basePath + dir.relativize(p).toString()));
                    }
                } catch (Exception ignored) {}
            }
        }

        return result;
    }
}
