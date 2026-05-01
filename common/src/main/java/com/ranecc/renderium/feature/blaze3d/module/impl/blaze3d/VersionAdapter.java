// Renderium - Mixin 基础设施
// 版本适配器 - 检测 MC 版本和功能可用性

package com.ranecc.renderium.feature.blaze3d.module.impl.blaze3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 版本适配器 🔧
 * <p>
 * 提供版本检测和功能检测能力，用于 Mixin 在不同 Minecraft 版本间进行适配。
 * 支持方法签名验证、功能特性查询、版本比较等核心能力。
 *
 * <h2>核心功能：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────┐
 * │  1. 版本检测                                             │
 * │     - 获取当前 MC 版本号                                  │
 * │     - 版本比较（支持语义化版本）                            │
 * ├─────────────────────────────────────────────────────────┤
 * │  2. 方法签名验证                                          │
 * │     - 验证目标版本的方法签名是否有效                        │
 * │     - 维护版本→签名映射表                                  │
 * ├─────────────────────────────────────────────────────────┤
 * │  3. 功能特性检测                                          │
 * │     - frame-graph-inspector: 帧图检查器支持                │
 * │     - vma-allocator: Vulkan 内存分配器                     │
 * │     - dynamic-rendering: 动态渲染支持                      │
 * └─────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li>线程安全：所有操作均为无状态或使用并发集合</li>
 *   <li>延迟初始化：版本信息在首次访问时加载</li>
 *   <li>可扩展：通过注册机制添加新版本的签名映射</li>
 * </ul>
 *
 * @see MethodSignature
 * @author Renderium Team
 * @since 2.0.0
 */
public final class VersionAdapter {

    private static final Logger LOGGER = Logger.getLogger(VersionAdapter.class.getName());

    // ==================== 功能特性常量 ====================

    /** 帧图检查器 (Frame Graph Inspector) 特性标识 */
    public static final String FEATURE_FRAME_GRAPH_INSPECTOR = "frame-graph-inspector";

    /** VMA 内存分配器 (Vulkan Memory Allocator) 特性标识 */
    public static final String FEATURE_VMA_ALLOCATOR = "vma-allocator";

    /** 动态渲染 (Dynamic Rendering) 特性标识 */
    public static final String FEATURE_DYNAMIC_RENDERING = "dynamic-rendering";

    /** 管线缓存 (Pipeline Cache) 特性标识 */
    public static final String FEATURE_PIPELINE_CACHE = "pipeline-cache";

    /** 描述符索引 (Descriptor Indexing) 特性标识 */
    public static final String FEATURE_DESCRIPTOR_INDEXING = "descriptor-indexing";

    // ==================== 内部状态 ====================

    /** 缓存的当前 MC 版本字符串 */
    private static volatile String cachedMCVersion;

    /** 方法签名注册表 (version -> methodKey -> signature) */
    private static final ConcurrentHashMap<String, Map<String, MethodSignature>> methodSignatureRegistry =
            new ConcurrentHashMap<>();

    /** 功能特性注册表 (featureName -> supportedVersions) */
    private static final ConcurrentHashMap<String, String[]> featureRegistry = new ConcurrentHashMap<>();

    // ==================== 静态初始化块 ====================

    static {
        // 注册已知的功能特性及其支持的最低版本
        registerDefaultFeatures();
        LOGGER.fine("VersionAdapter 初始化完成");
    }

    // 私有构造函数，防止实例化
    private VersionAdapter() {
        throw new UnsupportedOperationException("VersionAdapter 是工具类，不允许实例化");
    }

    // ==================== 公共 API：版本检测 ====================

    /**
     * 获取当前 Minecraft 版本号
     * <p>
     * 优先从系统属性获取，若未设置则从环境变量获取。
     * 格式示例："1.20.4"、"1.21.1"
     *
     * @return 当前 MC 版本字符串，无法获取时返回 "unknown"
     */
    public static String getMCVersion() {
        if (cachedMCVersion != null) {
            return cachedMCVersion;
        }

        // 尝试从系统属性获取
        String version = System.getProperty("minecraft.version");

        if (version == null || version.isBlank()) {
            // 尝试从环境变量获取
            version = System.getenv("MINECRAFT_VERSION");
        }

        if (version == null || version.isBlank()) {
            // [1.1.0] Java 25: Package.getPackage() removed, use System property fallback
            try {
                version = System.getProperty("minecraft.version", null);
            } catch (Exception e) {
                LOGGER.fine("无法获取 MC 版本属性: " + e.getMessage());
            }
        }

        if (version == null || version.isBlank()) {
            version = "unknown";
            LOGGER.warning("无法确定 Minecraft 版本，将使用默认值 'unknown'");
        } else {
            LOGGER.info(String.format("检测到 Minecraft 版本: %s", version));
        }

        cachedMCVersion = version;
        return version;
    }

    /**
     * 比较两个版本号
     * <p>
     * 支持语义化版本比较（major.minor.patch）。
     *
     * @param version1 第一个版本号
     * @param version2 第二个版本号
     * @return 负数表示 version1 < version2，0 表示相等，正数表示 version1 > version2
     */
    public static int compareVersions(String version1, String version2) {
        if (version1 == null || version2 == null) {
            throw new IllegalArgumentException("版本号不能为 null");
        }

        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");

        int maxLength = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLength; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;

            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }

        return 0;
    }

    /**
     * 判断当前版本是否大于或等于目标版本
     *
     * @param targetVersion 目标版本号
     * @return 如果当前版本 >= 目标版本则返回 true
     */
    public static boolean isVersionAtLeast(String targetVersion) {
        return compareVersions(getMCVersion(), targetVersion) >= 0;
    }

    // ==================== 公共 API：方法签名验证 ====================

    /**
     * 验证指定版本的方法签名是否有效
     * <p>
     * 从注册表中查找对应版本和方法名的签名信息。
     *
     * @param targetVersion 目标版本号（如 "1.20.4"）
     * @param methodName    要验证的方法名（或方法键）
     * @return 如果找到有效签名则返回 true
     */
    public static boolean isMethodSignatureValid(String targetVersion, String methodName) {
        Map<String, MethodSignature> versionSignatures = methodSignatureRegistry.get(targetVersion);

        if (versionSignatures == null || versionSignatures.isEmpty()) {
            LOGGER.fine(String.format("未找到版本 %s 的签名注册表", targetVersion));
            return false;
        }

        boolean valid = versionSignatures.containsKey(methodName);

        if (!valid) {
            LOGGER.fine(String.format("版本 %s 中未找到方法 '%s' 的签名", targetVersion, methodName));
        }

        return valid;
    }

    /**
     * 使用 MethodSignature 对象验证方法签名
     * <p>
     * 重载方法，接受完整的 MethodSignature 对象进行验证。
     *
     * @param targetVersion 目标版本号
     * @param signature     要验证的方法签名对象
     * @return 如果签名有效则返回 true
     */
    public static boolean isMethodSignatureValid(String targetVersion, MethodSignature signature) {
        if (signature == null) {
            return false;
        }
        return isMethodSignatureValid(targetVersion, signature.methodName());
    }

    /**
     * 获取指定版本和方法名的方法签名
     *
     * @param targetVersion 目标版本号
     * @param methodName    方法名（或方法键）
     * @return 找到的 MethodSignature，未找到则返回 null
     */
    public static MethodSignature getMethodSignature(String targetVersion, String methodName) {
        Map<String, MethodSignature> versionSignatures = methodSignatureRegistry.get(targetVersion);

        if (versionSignatures == null) {
            return null;
        }

        return versionSignatures.get(methodName);
    }

    /**
     * 注册方法签名到指定版本
     * <p>
     * 用于在运行时动态添加新的方法签名映射。
     *
     * @param version   目标版本号
     * @param signature 要注册的方法签名
     */
    public static void registerMethodSignature(String version, MethodSignature signature) {
        if (version == null || signature == null) {
            throw new IllegalArgumentException("版本和签名不能为 null");
        }

        methodSignatureRegistry.computeIfAbsent(version, k -> new ConcurrentHashMap<>())
                .put(signature.methodName(), signature);

        LOGGER.fine(String.format("已注册方法签名: %s → %s", version, signature.getShortIdentifier()));
    }

    // ==================== 公共 API：功能特性检测 ====================

    /**
     * 检测指定功能特性在当前版本是否可用
     * <p>
     * 通过比对当前版本与特性的最低支持版本来判断。
     *
     * @param featureName 功能特性名称（使用常量定义）
     * @return 如果功能可用则返回 true
     */
    public static boolean hasFeature(String featureName) {
        if (featureName == null || featureName.isBlank()) {
            return false;
        }

        String[] supportedVersions = featureRegistry.get(featureName);

        if (supportedVersions == null || supportedVersions.length == 0) {
            LOGGER.warning(String.format("未知的功能特性: %s", featureName));
            return false;
        }

        String currentVersion = getMCVersion();

        for (String minVersion : supportedVersions) {
            if (compareVersions(currentVersion, minVersion) >= 0) {
                LOGGER.fine(String.format("功能 '%s' 可用 (当前版本: %s >= 要求: %s)",
                        featureName, currentVersion, minVersion));
                return true;
            }
        }

        LOGGER.fine(String.format("功能 '%s' 不可用 (当前版本: %s)", featureName, currentVersion));
        return false;
    }

    /**
     * 注册功能特性及其支持的版本列表
     * <p>
     * 用于扩展支持的新功能特性。
     *
     * @param featureName         功能特性名称
     * @param minimumSupportVersions 支持该功能的最低版本数组
     */
    public static void registerFeature(String featureName, String... minimumSupportVersions) {
        if (featureName == null || featureName.isBlank()) {
            throw new IllegalArgumentException("功能名称不能为空");
        }
        if (minimumSupportVersions == null || minimumSupportVersions.length == 0) {
            throw new IllegalArgumentException("必须提供至少一个支持的版本");
        }

        featureRegistry.put(featureName, minimumSupportVersions.clone());

        LOGGER.fine(String.format("已注册功能特性: %s (支持版本: %s)",
                featureName, String.join(", ", minimumSupportVersions)));
    }

    /**
     * 获取所有已注册的功能特性名称
     *
     * @return 功能特性名称数组
     */
    public static String[] getRegisteredFeatures() {
        return featureRegistry.keySet().toArray(new String[0]);
    }

    // ==================== 内部实现方法 ====================

    /**
     * 解析版本号的数字部分
     * <p>
     * 处理可能包含的非数字字符（如 "-beta", "-snapshot"）。
     *
     * @param part 版本号的一部分
     * @return 解析后的整数值
     */
    private static int parseVersionPart(String part) {
        if (part == null || part.isEmpty()) {
            return 0;
        }

        // 只取数字部分
        StringBuilder numericPart = new StringBuilder();
        for (char c : part.toCharArray()) {
            if (Character.isDigit(c)) {
                numericPart.append(c);
            } else {
                break; // 遇到非数字字符停止
            }
        }

        if (numericPart.isEmpty()) {
            return 0;
        }

        try {
            return Integer.parseInt(numericPart.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 注册默认的功能特性
     * <p>
     * 在静态初始化时调用，建立基础的功能-版本映射关系。
     */
    private static void registerDefaultFeatures() {
        // 帧图检查器 - 1.20.4+ 支持
        registerFeature(FEATURE_FRAME_GRAPH_INSPECTOR, "1.20.4");

        // VMA 分配器 - 1.21.0+ 支持（需要较新的 Vulkan 绑定）
        registerFeature(FEATURE_VMA_ALLOCATOR, "1.21.0");

        // 动态渲染 - 1.20.4+ 支持（VK_KHR_dynamic_rendering）
        registerFeature(FEATURE_DYNAMIC_RENDERING, "1.20.4");

        // 管线缓存 - 1.20.1+ 支持
        registerFeature(FEATURE_PIPELINE_CACHE, "1.20.1");

        // 描述符索引 - 1.21.1+ 支持（需要 VK_EXT_descriptor_indexing）
        registerFeature(FEATURE_DESCRIPTOR_INDEXING, "1.21.1");
    }

    // ==================== 工具方法 ====================

    /**
     * 重置缓存的版本信息
     * <p>
     * 主要用于测试场景，强制下次调用时重新检测版本。
     */
    public static void resetCache() {
        cachedMCVersion = null;
        LOGGER.fine("版本缓存已重置");
    }

    /**
     * 获取格式化的版本报告
     * <p>
     * 包含当前版本、已注册功能状态等信息。
     *
     * @return 格式化的报告字符串
     */
    public static String formatReport() {
        StringBuilder report = new StringBuilder();
        String currentVersion = getMCVersion();

        report.append("Version Adapter Report:\n");
        report.append(String.format("  Current MC Version: %s\n", currentVersion));
        report.append(String.format("  Registered Versions: %d\n", methodSignatureRegistry.size()));
        report.append(String.format("  Registered Features: %d\n", featureRegistry.size()));

        report.append("\n  Feature Status:\n");
        for (String feature : featureRegistry.keySet()) {
            boolean available = hasFeature(feature);
            report.append(String.format("    %-30s %s\n",
                    feature,
                    available ? "✓ AVAILABLE" : "✗ UNAVAILABLE"));
        }

        return report.toString();
    }
}
