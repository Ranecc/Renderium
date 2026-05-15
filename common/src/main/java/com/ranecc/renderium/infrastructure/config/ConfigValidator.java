package com.ranecc.renderium.infrastructure.config;

import com.ranecc.renderium.domain.constant.ConfigConstants;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 配置验证器（Infrastructure Layer）
 *
 * <p>负责验证 {@link RenderiumConfig} 聚合根的合法性和完整性，
 * 提供比聚合根自身 validate() 方法更详细的错误报告和范围检查。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>GPU 阈值验证</b>：检查范围 [MIN_GPU_THRESHOLD, MAX_GPU_THRESHOLD]</li>
 *   <li><b>LOD 级别验证</b>：检查范围 [1, LOD_MAX_LEVELS]</li>
 *   <li><b>FOV 验证</b>：检查范围 [30, 150]</li>
 *   <li><b>子对象验证</b>：递归验证 AlgorithmConfig 及其子配置</li>
 *   <li><b>详细错误报告</b>：返回包含所有错误的列表</li>
 * </ul>
 *
 * <h3>设计特点</h3>
 * <ul>
 *   <li>独立于 RenderiumConfig 聚合根（符合 SRP 原则）</li>
 *   <li>支持批量错误收集（一次性报告所有问题）</li>
 *   <li>所有边界值来源于 ConfigConstants（避免魔法数字）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ConfigValidator validator = new ConfigValidator();
 * ValidationResult result = validator.validate(config);
 *
 * if (result.hasErrors()) {
 *     for (String error : result.getErrors()) {
 *         System.err.println("Error: " + error);
 *     }
 * }
 * }</pre>
 *
 * @see RenderiumConfig
 * @see ConfigConstants
 * @since 1.1.0
 */
public final class ConfigValidator {

    /** 私有构造函数 - 不允许实例化（工具类） */
    private ConfigValidator() {}

    /**
     * 验证配置的完整性和合法性
     *
     * <p>执行全面验证，包括：
     * <ol>
     *   <li>GPU 使用率阈值范围检查</li>
     *   <li>FOV 视场角范围检查</li>
     *   <li>LOD 级别范围检查</li>
     *   <li>裁剪面距离合理性检查</li>
     *   <li>窗口尺寸有效性检查</li>
     *   <li>AlgorithmConfig 子对象验证</li>
     * </ol>
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>config - 待验证的配置实例（RenderiumConfig 类型）</li>
     *   <li><b>返回值：</b>ValidationResult - 验证结果（包含错误/警告列表）</li>
     *   <li><b>异常：</b>无（不会抛出异常）</li>
     * </ul>
     *
     * @param config 待验证的配置实例
     * @return ValidationResult 验证结果（包含详细的错误和警告列表）
     * @throws IllegalArgumentException 如果 config 为 null
     */
    public static ValidationResult validate(RenderiumConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. 验证 GPU 使用率阈值范围
        validateGpuThreshold(config, errors, warnings);

        // 2. 验证 FOV 范围 [30, 150]
        validateFov(config, errors, warnings);

        // 3. 验证 LOD 级别范围 [1, LOD_MAX_LEVELS]
        validateLodLevels(config, errors, warnings);

        // 4. 验证裁剪面距离
        validatePlanes(config, errors, warnings);

        // 5. 验证窗口尺寸
        validateWindowSize(config, errors, warnings);

        // 6. 验证 AlgorithmConfig 子对象
        validateAlgorithmConfig(config, errors, warnings);

        // 构建结果
        if (!errors.isEmpty()) {
            return ValidationResult.error(errors);
        }
        if (!warnings.isEmpty()) {
            return ValidationResult.warning(warnings);
        }
        return ValidationResult.ok();
    }

    /**
     * 快速验证配置是否合法（仅检查是否有错误）
     *
     * <p>适用于需要快速判断的场景，不关心具体错误信息。
     *
     * @param config 待验证的配置实例
     * @return true 如果配置合法（无错误），false 如果存在错误
     */
    public static boolean isValid(RenderiumConfig config) {
        ValidationResult result = validate(config);
        return !result.hasErrors();
    }

    // ==================== 私有验证方法 ====================

    /**
     * 验证 GPU 使用率阈值范围
     *
     * <p>检查范围：[ConfigConstants.MIN_GPU_THRESHOLD, ConfigConstants.MAX_GPU_THRESHOLD]
     * 即 [0.3f, 0.95f]
     *
     * @param config   配置实例
     * @param errors   错误列表（可变）
     * @param warnings 警告列表（可变）
     */
    private static void validateGpuThreshold(RenderiumConfig config,
                                              List<String> errors, List<String> warnings) {
        float threshold = config.getGpuUsageThreshold();

        if (threshold < ConfigConstants.MIN_GPU_THRESHOLD) {
            errors.add(String.format(
                "GPU usage threshold (%.2f) is below minimum (%.2f)",
                threshold, ConfigConstants.MIN_GPU_THRESHOLD
            ));
        } else if (threshold > ConfigConstants.MAX_GPU_THRESHOLD) {
            errors.add(String.format(
                "GPU usage threshold (%.2f) exceeds maximum (%.2f)",
                threshold, ConfigConstants.MAX_GPU_THRESHOLD
            ));
        }

        // 性能警告：阈值过高可能导致频繁切换
        if (threshold > 0.85f && threshold <= ConfigConstants.MAX_GPU_THRESHOLD) {
            warnings.add(String.format(
                "High GPU threshold (%.2f) may cause frequent path switching",
                threshold
            ));
        }

        // 同时验证 AlgorithmConfig 中的阈值
        float algoThreshold = config.getAlgorithmConfig().getGpuUsageThreshold();
        if (algoThreshold < ConfigConstants.MIN_GPU_THRESHOLD ||
            algoThreshold > ConfigConstants.MAX_GPU_THRESHOLD) {
            errors.add(String.format(
                "AlgorithmConfig GPU threshold (%.2f) out of range [%.2f, %.2f]",
                algoThreshold, ConfigConstants.MIN_GPU_THRESHOLD, ConfigConstants.MAX_GPU_THRESHOLD
            ));
        }
    }

    /**
     * 验证 FOV 视场角范围
     *
     * <p>检查范围：[30, 150] 度
     *
     * @param config   配置实例
     * @param errors   错误列表（可变）
     * @param warnings 警告列表（可变）
     */
    private static void validateFov(RenderiumConfig config,
                                    List<String> errors, List<String> warnings) {
        float fov = config.getFov();

        if (fov < 30.0f) {
            errors.add(String.format("FOV (%.1f) is too narrow (minimum: 30)", fov));
        } else if (fov > 150.0f) {
            errors.add(String.format("FOV (%.1f) is too wide (maximum: 150)", fov));
        }

        // 性能警告：极端 FOV 可能导致性能问题
        if (fov >= 120.0f && fov <= 150.0f) {
            warnings.add(String.format(
                "Very wide FOV (%.1f) may impact rendering performance", fov
            ));
        }
    }

    /**
     * 验证 LOD 级别范围
     *
     * <p>检查范围：[1, ConfigConstants.LOD_MAX_LEVELS]
     * 即 [1, 4]
     *
     * @param config   配置实例
     * @param errors   错误列表（可变）
     * @param warnings 警告列表（可变）
     */
    private static void validateLodLevels(RenderiumConfig config,
                                           List<String> errors, List<String> warnings) {
        int maxLevels = config.getAlgorithmConfig().getLod().getMaxLevels();

        if (maxLevels < 1) {
            errors.add("LOD maxLevels must be at least 1");
        } else if (maxLevels > ConfigConstants.LOD_MAX_LEVELS) {
            errors.add(String.format(
                "LOD maxLevels (%d) exceeds maximum (%d)",
                maxLevels, ConfigConstants.LOD_MAX_LEVELS
            ));
        }

        // 性能警告：高 LOD 级别增加计算开销
        if (maxLevels > 3 && maxLevels <= ConfigConstants.LOD_MAX_LEVELS) {
            warnings.add(String.format(
                "High LOD levels (%d) may increase computation overhead", maxLevels
            ));
        }
    }

    /**
     * 验证裁剪面距离
     *
     * <p>检查条件：
     * <ul>
     *   <li>nearPlane > 0</li>
     *   <li>farPlane > nearPlane</li>
     *   <li>farPlane > 0</li>
     * </ul>
     *
     * @param config   配置实例
     * @param errors   错误列表（可变）
     * @param warnings 警告列表（可变）
     */
    private static void validatePlanes(RenderiumConfig config,
                                       List<String> errors, List<String> warnings) {
        float near = config.getNearPlane();
        float far = config.getFarPlane();

        if (near <= 0) {
            errors.add(String.format("Near plane (%.4f) must be positive", near));
        }

        if (far <= 0) {
            errors.add(String.format("Far plane (%.4f) must be positive", far));
        }

        if (near > 0 && far > 0 && far <= near) {
            errors.add(String.format(
                "Far plane (%.4f) must be greater than near plane (%.4f)", far, near
            ));
        }

        // 精度警告：Z-fighting 风险
        if (near > 0 && far > 0 && (far / near) > 100000) {
            warnings.add(String.format(
                "Large depth range ratio (far/near=%.0f) may cause Z-fighting", far / near
            ));
        }
    }

    /**
     * 验证窗口尺寸
     *
     * <p>检查条件：width > 0 且 height > 0
     *
     * @param config   配置实例
     * @param errors   错误列表（可变）
     * @param warnings 警告列表（可变）
     */
    private static void validateWindowSize(RenderiumConfig config,
                                           List<String> errors, List<String> warnings) {
        int width = config.getWindowWidth();
        int height = config.getWindowHeight();

        if (width <= 0) {
            errors.add("Window width must be positive (current: " + width + ")");
        }

        if (height <= 0) {
            errors.add("Window height must be positive (current: " + height + ")");
        }

        // 性能警告：超高分辨率可能影响性能
        if (width > 3840 || height > 2160) {
            warnings.add(String.format(
                "High resolution (%dx%d) may require powerful GPU", width, height
            ));
        }
    }

    /**
     * 验证 AlgorithmConfig 子对象
     *
     * <p>递归验证 BFS/LOD/Kahan 各子配置的合法性
     *
     * @param config   配置实例
     * @param errors   错误列表（可变）
     * @param warnings 警告列表（可变）
     */
    private static void validateAlgorithmConfig(RenderiumConfig config,
                                                 List<String> errors, List<String> warnings) {
        RenderiumConfig.AlgorithmConfig algoConfig = config.getAlgorithmConfig();

        // 验证强制模式
        String forceMode = algoConfig.getForceMode();
        if (!"auto".equals(forceMode) && !"java".equals(forceMode) && !"native".equals(forceMode)) {
            errors.add("Algorithm forceMode must be 'auto', 'java', or 'native' (current: '" + forceMode + "')");
        }

        // 验证 BFS 配置
        RenderiumConfig.AlgorithmConfig.BFSConfig bfs = algoConfig.getBfs();
        if (bfs.getMaxSections() <= 0) {
            errors.add("BFS maxSections must be positive (current: " + bfs.getMaxSections() + ")");
        }
        if (bfs.getMaxSections() > 65536) {
            warnings.add("BFS maxSections (" + bfs.getMaxSections() + ") is very high, may use excessive memory");
        }

        // 验证 LOD 配置
        RenderiumConfig.AlgorithmConfig.LODConfig lod = algoConfig.getLod();
        if (lod.getMaxLevels() < 1) {
            errors.add("LOD maxLevels must be at least 1 (current: " + lod.getMaxLevels() + ")");
        }

        // 验证 Kahan 配置
        RenderiumConfig.AlgorithmConfig.KahanConfig kahan = algoConfig.getKahan();
        if (kahan.getBatchSize() <= 0) {
            errors.add("Kahan batchSize must be positive (current: " + kahan.getBatchSize() + ")");
        }
        if (kahan.getBatchSize() > 1024) {
            warnings.add("Kahan batch size (" + kahan.getBatchSize() + ") exceeds recommended maximum (1024)");
        }
    }

    // ==================== ValidationResult 内部类 ====================

    /**
     * 详细验证结果（包含错误和警告列表）
     *
     * <p>与 RenderiumConfig.ValidationResult 不同，此类提供更详细的错误报告，
     * 包含完整的错误列表而非单条消息字符串。
     *
     * @since 1.1.0
     */
    public static final class ValidationResult {

        /** 验证级别 */
        public enum Level {
            /** 验证通过 */
            OK,
            /** 验证通过但有警告 */
            WARNING,
            /** 验证失败 */
            ERROR
        }

        /** 验证级别 */
        private final Level level;

        /** 错误列表（不可变） */
        private final List<String> errors;

        /** 警告列表（不可变） */
        private final List<String> warnings;

        /**
         * 私有构造函数
         *
         * @param level    验证级别
         * @param errors   错误列表
         * @param warnings 警告列表
         */
        private ValidationResult(Level level, List<String> errors, List<String> warnings) {
            this.level = level;
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        /**
         * 创建成功的验证结果
         *
         * @return 验证结果（OK 级别，空列表）
         */
        public static ValidationResult ok() {
            return new ValidationResult(Level.OK, List.of(), List.of());
        }

        /**
         * 创建带警告的验证结果
         *
         * @param warnings 警告列表
         * @return 验证结果（WARNING 级别）
         */
        public static ValidationResult warning(List<String> warnings) {
            return new ValidationResult(Level.WARNING, List.of(), warnings);
        }

        /**
         * 创建失败的验证结果
         *
         * @param errors 错误列表
         * @return 验证结果（ERROR 级别）
         */
        public static ValidationResult error(List<String> errors) {
            return new ValidationResult(Level.ERROR, errors, List.of());
        }

        /**
         * 检查验证是否成功（无错误）
         *
         * @return true 如果验证通过（OK 或 WARNING）
         */
        public boolean isOk() {
            return level != Level.ERROR;
        }

        /**
         * 检查是否有错误
         *
         * @return true 如果存在错误
         */
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        /**
         * 检查是否有警告
         *
         * @return true 如果存在警告
         */
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        /**
         * 获取验证级别
         *
         * @return 验证级别（OK/WARNING/ERROR）
         */
        public Level getLevel() { return level; }

        /**
         * 获取错误列表（不可变）
         *
         * @return 错误字符串列表
         */
        public List<String> getErrors() { return errors; }

        /**
         * 获取警告列表（不可变）
         *
         * @return 警告字符串列表
         */
        public List<String> getWarnings() { return warnings; }

        /**
         * 获取错误数量
         *
         * @return 错误总数
         */
        public int getErrorCount() { return errors.size(); }

        /**
         * 获取警告数量
         *
         * @return 警告总数
         */
        public int getWarningCount() { return warnings.size(); }

        @Override
        public String toString() {
            return switch (level) {
                case OK -> "ValidationResult{OK}";
                case WARNING -> String.format("ValidationResult{WARNING: %d warning(s)}",
                    warnings.size());
                case ERROR -> String.format("ValidationResult{ERROR: %d error(s), %d warning(s)}",
                    errors.size(), warnings.size());
            };
        }
    }
}
