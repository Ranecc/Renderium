// Renderium - Streamline Config Loader
// Streamline SDK 配置加载和 DLL 路径验证

package com.renderium.streamline;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Streamline 配置加载器
 * <p>
 * 负责：
 * <ul>
 *   <li>验证 Streamline SDK DLL 文件完整性</li>
 *   <li>加载配置属性</li>
 *   <li>确定插件路径</li>
 *   <li>确定日志和缓存路径</li>
 * </ul>
 * <p>
 * 必需的 DLL 文件（来自 streamline-sdk-v2.10.3）：
 * <ul>
 *   <li>sl.interposer.dll - 核心库</li>
 *   <li>sl.common.dll - 通用功能</li>
 *   <li>sl.dlss.dll - DLSS 超分辨率</li>
 *   <li>sl.dlss_g.dll - DLSS 帧生成</li>
 *   <li>sl.reflex.dll - NVIDIA Reflex（可选）</li>
 *   <li>sl.xess.dll - Intel XeSS（可选）</li>
 *   <li>sl.fsr.dll - AMD FSR（可选）</li>
 * </ul>
 */
public final class SLConfigLoader {

    private static final Logger LOGGER = Logger.getLogger(SLConfigLoader.class.getName());

    /**
     * 必需的 DLL 文件列表
     */
    private static final String[] REQUIRED_DLLS = {
        "sl.interposer.dll",
        "sl.common.dll",
        "sl.dlss.dll"
    };

    /**
     * 可选的 DLL 文件列表
     */
    private static final String[] OPTIONAL_DLLS = {
        "sl.dlss_g.dll",
        "sl.reflex.dll",
        "sl.xess.dll",
        "sl.fsr.dll",
        "sl.nis.dll",
        "sl.deepdvc.dll",
        "sl.latewarp.dll"
    };

    private final Path sdkBasePath;
    private Path interposerPath;
    private Path pluginPath;
    private Path logPath;
    private Path cachePath;
    private final List<String> missingRequiredDlls = new ArrayList<>();
    private final List<String> availableOptionalDlls = new ArrayList<>();

    /**
     * 创建配置加载器
     *
     * @param sdkBasePath Streamline SDK 基础路径
     *                    （如 env/streamline-sdk-v2.10.3）
     */
    public SLConfigLoader(Path sdkBasePath) {
        this.sdkBasePath = sdkBasePath;
    }

    /**
     * 验证 SDK 路径和 DLL 文件
     * <p>
     * 检查所有必需和可选的 DLL 文件是否存在。
     *
     * @return 是否所有必需文件都存在
     */
    public boolean validate() {
        missingRequiredDlls.clear();
        availableOptionalDlls.clear();

        // 确定平台路径
        Path binPath = sdkBasePath.resolve("bin").resolve("x64");
        if (!Files.isDirectory(binPath)) {
            // 尝试直接在基础路径查找
            binPath = sdkBasePath;
        }

        // 检查必需 DLL
        for (String dll : REQUIRED_DLLS) {
            Path dllPath = binPath.resolve(dll);
            if (!Files.isRegularFile(dllPath)) {
                missingRequiredDlls.add(dll);
                LOGGER.severe("Missing required DLL: " + dll);
            }
        }

        // 检查可选 DLL
        for (String dll : OPTIONAL_DLLS) {
            Path dllPath = binPath.resolve(dll);
            if (Files.isRegularFile(dllPath)) {
                availableOptionalDlls.add(dll);
                LOGGER.info("Available optional DLL: " + dll);
            }
        }

        // 设置路径
        this.interposerPath = binPath.resolve("sl.interposer.dll");
        this.pluginPath = binPath;

        // 验证 interposer
        if (!Files.isRegularFile(interposerPath)) {
            LOGGER.severe("sl.interposer.dll not found at: " + interposerPath);
            return false;
        }

        LOGGER.info("Streamline SDK validated successfully");
        LOGGER.info("  Interposer: " + interposerPath);
        LOGGER.info("  Plugin path: " + pluginPath);
        LOGGER.info("  Available optional: " + availableOptionalDlls.size());

        return missingRequiredDlls.isEmpty();
    }

    /**
     * 设置日志路径
     *
     * @param logPath 日志目录
     */
    public void setLogPath(Path logPath) {
        this.logPath = logPath;
    }

    /**
     * 设置缓存路径
     *
     * @param cachePath 缓存目录
     */
    public void setCachePath(Path cachePath) {
        this.cachePath = cachePath;
    }

    /**
     * 获取 sl.interposer.dll 完整路径
     */
    public String getInterposerPath() {
        if (interposerPath == null) return null;
        return interposerPath.toAbsolutePath().toString();
    }

    /**
     * 获取插件目录路径
     * <p>
     * 包含 sl.dlss.dll、sl.dlss_g.dll 等插件的目录。
     */
    public String getPluginPath() {
        if (pluginPath == null) return null;
        return pluginPath.toAbsolutePath().toString();
    }

    /**
     * 获取日志路径
     */
    public String getLogPath() {
        if (logPath == null) return null;
        return logPath.toAbsolutePath().toString();
    }

    /**
     * 获取缓存路径
     */
    public String getCachePath() {
        if (cachePath == null) return null;
        return cachePath.toAbsolutePath().toString();
    }

    /**
     * 获取缺失的必需 DLL 列表
     */
    public List<String> getMissingRequiredDlls() {
        return Collections.unmodifiableList(missingRequiredDlls);
    }

    /**
     * 获取可用的可选 DLL 列表
     */
    public List<String> getAvailableOptionalDlls() {
        return Collections.unmodifiableList(availableOptionalDlls);
    }

    /**
     * 检查 DLSS 帧生成是否可用
     */
    public boolean isDLSSGAvailable() {
        return availableOptionalDlls.contains("sl.dlss_g.dll");
    }

    /**
     * 检查 XeSS 是否可用
     */
    public boolean isXeSSAvailable() {
        return availableOptionalDlls.contains("sl.xess.dll");
    }

    /**
     * 检查 FSR 是否可用
     */
    public boolean isFSRAvailable() {
        return availableOptionalDlls.contains("sl.fsr.dll");
    }

    /**
     * 检查 Reflex 是否可用
     */
    public boolean isReflexAvailable() {
        return availableOptionalDlls.contains("sl.reflex.dll");
    }

    /**
     * 确保路径目录存在
     *
     * @param path 目录路径
     * @return 是否成功创建或已存在
     */
    public static boolean ensureDirectoryExists(Path path) {
        if (path == null) return false;
        try {
            Files.createDirectories(path);
            return true;
        } catch (IOException e) {
            LOGGER.severe("Failed to create directory: " + path + " - " + e.getMessage());
            return false;
        }
    }
}
