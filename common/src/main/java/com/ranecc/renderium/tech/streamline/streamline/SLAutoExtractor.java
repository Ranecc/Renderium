// Renderium - Streamline SDK 自动提取器
// 将 DLSS SDK 内置到模组中，玩家无需手动配置
// 设计参考：所有支持 DLSS 的游戏（Cyberpunk 2077、UE5 游戏等）

package com.ranecc.renderium.tech.streamline.streamline;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Streamline SDK 自动提取器
 * 
 * <p>设计理念：与其他支持 DLSS 的游戏一致
 * <ul>
 *   <li>开发者将 SDK DLL 打包到模组 JAR 中</li>
 *   <li>首次运行时自动提取到游戏目录</li>
 *   <li>玩家零配置，直接可用</li>
 * </ul>
 * 
 * <p>工作流程：
 * <pre>
 * 模组 JAR (resources/streamline-sdk/)
 *   ↓ 首次运行时
 * 提取到 .minecraft/renderium/streamline-sdk/
 *   ↓ 后续运行
 * 直接加载已提取的 DLL
 * </pre>
 * 
 * @author Renderium Team
 * @since 1.0.0
 */
public final class SLAutoExtractor {

    private static final Logger LOGGER = Logger.getLogger("Renderium-SLExtractor");

    /** Streamline SDK 版本（用于检测是否需要重新提取） */
    private static final String SDK_VERSION = "2.10.3";

    /** JAR 内资源路径 */
    private static final String JAR_RESOURCE_PATH = "/streamline-sdk/";

    /** 必需的 DLL 列表 */
    private static final String[] REQUIRED_DLLS = {
        "sl.interposer.dll",
        "sl.common.dll",
        "sl.dlss.dll"
    };

    /** 可选的 DLL 列表 */
    private static final String[] OPTIONAL_DLLS = {
        "sl.dlss_g.dll",
        "sl.reflex.dll",
        "sl.xess.dll",
        "sl.fsr.dll"
    };

    /** SDK 提取目录（.minecraft/renderium/streamline-sdk/） */
    private final Path sdkDir;

    /** 已提取的 DLL 路径 */
    private String interposerPath;
    private String pluginPath;
    private String logPath;
    private String cachePath;

    /** 检测结果 */
    private final List<String> missingRequiredDlls = new ArrayList<>();
    private final List<String> availableOptionalDlls = new ArrayList<>();

    /**
     * 创建自动提取器
     * 
     * @param modConfigDir 模组配置目录（.minecraft/renderium/）
     */
    public SLAutoExtractor(Path modConfigDir) {
        this.sdkDir = modConfigDir.resolve("streamline-sdk");
    }

    /**
     * 初始化 Streamline SDK
     * 
     * <p>执行流程：
     * <ol>
     *   <li>检查是否已提取</li>
     *   <li>如果未提取或版本过旧，从 JAR 中提取</li>
     *   <li>验证 DLL 完整性</li>
     *   <li>设置路径</li>
     * </ol>
     * 
     * @return 是否成功初始化
     */
    public boolean initialize() {
        try {
            // 步骤 1: 检查是否需要提取
            if (!isExtracted() || needsUpdate()) {
                LOGGER.info("Extracting Streamline SDK v" + SDK_VERSION + " from mod JAR...");
                if (!extractFromJar()) {
                    LOGGER.severe("Failed to extract Streamline SDK from JAR");
                    return false;
                }
            }

            // 步骤 2: 验证 DLL 完整性
            return validate();
        } catch (Exception e) {
            LOGGER.severe("Streamline SDK initialization failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 检查 SDK 是否已提取
     * 
     * @return true 如果已提取且版本匹配
     */
    private boolean isExtracted() {
        if (!Files.isDirectory(sdkDir)) {
            return false;
        }

        // 检查版本文件
        Path versionFile = sdkDir.resolve("version.txt");
        if (!Files.exists(versionFile)) {
            return false;
        }

        try {
            String version = Files.readString(versionFile).trim();
            if (!SDK_VERSION.equals(version)) {
                LOGGER.info("SDK version mismatch: extracted=" + version + ", current=" + SDK_VERSION);
                return false;
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to read version file: " + e.getMessage());
            return false;
        }

        // 检查所有必需的 DLL 是否存在
        for (String dll : REQUIRED_DLLS) {
            if (!Files.isRegularFile(sdkDir.resolve(dll))) {
                LOGGER.warning("Required DLL missing: " + dll);
                return false;
            }
        }

        return true;
    }

    /**
     * 检查是否需要更新
     * 
     * @return true 如果有可用更新
     */
    private boolean needsUpdate() {
        // 当前始终返回 false，后续可实现版本比较
        return false;
    }

    /**
     * 从 JAR 中提取 Streamline SDK
     * 
     * @return true 如果提取成功
     */
    private boolean extractFromJar() {
        try {
            // 创建提取目录
            Files.createDirectories(sdkDir);

            // 写入版本文件
            Files.writeString(sdkDir.resolve("version.txt"), SDK_VERSION);

            int extractedCount = 0;

            // 提取必需的 DLL
            for (String dll : REQUIRED_DLLS) {
                if (extractResource(JAR_RESOURCE_PATH + dll, sdkDir.resolve(dll))) {
                    extractedCount++;
                }
            }

            // 提取可选的 DLL
            for (String dll : OPTIONAL_DLLS) {
                if (extractResource(JAR_RESOURCE_PATH + dll, sdkDir.resolve(dll))) {
                    extractedCount++;
                }
            }

            LOGGER.info("Extracted " + extractedCount + " Streamline SDK files");
            LOGGER.info("  Target: " + sdkDir);
            
            return extractedCount >= REQUIRED_DLLS.length;
        } catch (IOException e) {
            LOGGER.severe("Failed to extract Streamline SDK: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 从 JAR 资源提取单个文件
     * 
     * @param resourceName JAR 内资源路径（如 /streamline-sdk/sl.interposer.dll）
     * @param targetPath 目标路径
     * @return true 如果提取成功
     */
    private boolean extractResource(String resourceName, Path targetPath) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resourceName)) {
            if (in == null) {
                LOGGER.fine("Resource not found in JAR: " + resourceName);
                return false;
            }

            // 创建父目录
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }

            // 写入文件
            try (OutputStream out = Files.newOutputStream(targetPath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            LOGGER.fine("Extracted: " + resourceName);
            return true;
        }
    }

    /**
     * 验证 SDK 完整性
     * 
     * @return true 如果所有必需的 DLL 都存在
     */
    private boolean validate() {
        missingRequiredDlls.clear();
        availableOptionalDlls.clear();

        // 检查必需的 DLL
        for (String dll : REQUIRED_DLLS) {
            Path dllPath = sdkDir.resolve(dll);
            if (!Files.isRegularFile(dllPath)) {
                missingRequiredDlls.add(dll);
                LOGGER.severe("Missing required DLL: " + dll);
            }
        }

        // 检查可选的 DLL
        for (String dll : OPTIONAL_DLLS) {
            Path dllPath = sdkDir.resolve(dll);
            if (Files.isRegularFile(dllPath)) {
                availableOptionalDlls.add(dll);
                LOGGER.fine("Available optional DLL: " + dll);
            }
        }

        // 设置路径
        this.interposerPath = sdkDir.resolve("sl.interposer.dll").toAbsolutePath().toString();
        this.pluginPath = sdkDir.toAbsolutePath().toString();
        this.logPath = sdkDir.resolve("logs").toAbsolutePath().toString();
        this.cachePath = sdkDir.resolve("cache").toAbsolutePath().toString();

        // 创建日志和缓存目录
        try {
            Files.createDirectories(sdkDir.resolve("logs"));
            Files.createDirectories(sdkDir.resolve("cache"));
        } catch (IOException e) {
            LOGGER.warning("Failed to create log/cache directories: " + e.getMessage());
        }

        // 验证 interposer
        if (!Files.isRegularFile(sdkDir.resolve("sl.interposer.dll"))) {
            LOGGER.severe("sl.interposer.dll not found");
            return false;
        }

        LOGGER.info("Streamline SDK v" + SDK_VERSION + " validated");
        LOGGER.info("  SDK Path: " + sdkDir);
        LOGGER.info("  Optional DLLs: " + availableOptionalDlls.size());

        return missingRequiredDlls.isEmpty();
    }

    // ==================== Getter ====================

    public String getInterposerPath() { return interposerPath; }
    public String getPluginPath() { return pluginPath; }
    public String getLogPath() { return logPath; }
    public String getCachePath() { return cachePath; }

    public List<String> getMissingRequiredDlls() {
        return List.copyOf(missingRequiredDlls);
    }

    public List<String> getAvailableOptionalDlls() {
        return List.copyOf(availableOptionalDlls);
    }

    public boolean isDLSSGAvailable() {
        return availableOptionalDlls.contains("sl.dlss_g.dll");
    }

    public boolean isXeSSAvailable() {
        return availableOptionalDlls.contains("sl.xess.dll");
    }

    public boolean isFSRAvailable() {
        return availableOptionalDlls.contains("sl.fsr.dll");
    }

    public boolean isReflexAvailable() {
        return availableOptionalDlls.contains("sl.reflex.dll");
    }

    /**
     * 清理提取的文件（用于调试）
     */
    public void cleanup() {
        try {
            if (Files.isDirectory(sdkDir)) {
                Files.walk(sdkDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOGGER.warning("Failed to delete: " + path);
                        }
                    });
                LOGGER.info("Cleaned up Streamline SDK directory");
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to cleanup: " + e.getMessage());
        }
    }
}
