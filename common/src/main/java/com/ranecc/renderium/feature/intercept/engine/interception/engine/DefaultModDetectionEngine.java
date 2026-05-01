// Renderium - 光影系统 v2.0
// 扩展的默认模组检测引擎 - 支持兼容性检查和更多模组

package com.ranecc.renderium.feature.intercept.engine.interception.engine;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 扩展的默认模组检测引擎实现
 * <p>
 * 在原版基础上增加：
 * <ul>
 *   <li>更多模组支持（Canvas、Rubidium、Lithium 等）</li>
 *   <li>光影系统兼容性检查</li>
 *   <li>冲突等级分类（阻塞/警告/安全）</li>
 * </ul>
 *
 * <h3>模组分类：</h3>
 * <table border="1">
 *   <tr><th>等级</th><th>说明</th><th>处理方式</th></tr>
 *   <tr><td>BLOCKING</td><td>完全冲突，光影系统无法工作</td><td>自动切换到兼容模式</td></tr>
 *   <tr><td>WARNING</td><td>可能存在兼容性问题</td><td>记录警告日志，可尝试共存</td></tr>
 *   <tr><td>SAFE</td><td>无冲突或纯服务端优化</td><td>正常放行</td></tr>
 * </table>
 *
 * @see ModDetectionEngine
 * @see CompatibilityResult
 * @since 2.1.0
 */
public final class DefaultModDetectionEngine implements ModDetectionEngine {

    private static final Logger LOGGER = Logger.getLogger("Renderium|ModDetection");

    /** 检测结果缓存（modId → 是否存在） */
    private final Map<String, Boolean> detectionCache = new ConcurrentHashMap<>();

    /** 已注册的模组 ID → 模组信息映射 */
    private final Map<String, ModInfo> modRegistry = new ConcurrentHashMap<>();

    /**
     * 模组信息记录
     */
    public record ModInfo(
            String mainClass,          // 主类路径
            ConflictLevel level,       // 冲突等级
            String description         // 描述
    ) {}

    /**
     * 冲突等级枚举
     */
    public enum ConflictLevel {
        /** 阻塞级：与 Renderium 光影系统完全冲突 */
        BLOCKING,
        /** 警告级：可能存在兼容性问题 */
        WARNING,
        /** 安全级：无冲突 */
        SAFE
    }

    /**
     * 兼容性检查结果
     */
    public static final class CompatibilityResult {
        private final List<String> blockingMods;
        private final List<String> warningMods;
        private final List<String> safeMods;
        /** 模组信息查找函数 */
        private final java.util.function.Function<String, ModInfo> modInfoLookup;

        CompatibilityResult(List<String> blocking, List<String> warning, List<String> safe,
                           java.util.function.Function<String, ModInfo> lookup) {
            this.blockingMods = Collections.unmodifiableList(blocking);
            this.warningMods = Collections.unmodifiableList(warning);
            this.safeMods = Collections.unmodifiableList(safe);
            this.modInfoLookup = lookup != null ? lookup : (id -> null);
        }

        /** 光影系统是否可用（无阻塞模组） */
        public boolean isShaderSystemAllowed() {
            return blockingMods.isEmpty();
        }

        /** 获取诊断消息 */
        public String getDiagnosticMessage() {
            StringBuilder sb = new StringBuilder();
            sb.append("═══ Renderium 兼容性检查结果 ═══\n");

            if (!blockingMods.isEmpty()) {
                sb.append("\n❌ 阻塞模组 (光影系统将被禁用):\n");
                for (String mod : blockingMods) {
                    ModInfo info = modInfoLookup.apply(mod);
                    sb.append(String.format("   • %s - %s\n", mod,
                            info != null ? info.description() : "未知"));
                }
            }

            if (!warningMods.isEmpty()) {
                sb.append("\n⚠️ 警告模组 (可能存在兼容性问题):\n");
                for (String mod : warningMods) {
                    ModInfo info = modInfoLookup.apply(mod);
                    sb.append(String.format("   • %s - %s\n", mod,
                            info != null ? info.description() : "未知"));
                }
            }

            if (!safeMods.isEmpty()) {
                sb.append(String.format("\n✅ 安全模组 (%d 个):\n", safeMods.size()));
                for (String mod : safeMods) {
                    sb.append(String.format("   • %s\n", mod));
                }
            }

            sb.append("\n").append(isShaderSystemAllowed()
                    ? "🟢 结论: 光影系统可以正常启用"
                    : "🔴 结论: 光影系统将禁用，使用兼容模式");

            return sb.toString();
        }

        public List<String> getBlockingMods() { return blockingMods; }
        public List<String> getWarningMods() { return warningMods; }
        public List<String> getSafeMods() { return safeMods; }
    }

    // 单例引用（用于内部查询）
    private static volatile DefaultModDetectionEngine instance;

    /**
     * 构造函数 - 注册所有已知模组
     */
    public DefaultModDetectionEngine() {
        instance = this;
        registerKnownMods();
    }

    /**
     * 获取单例实例（用于 CompatibilityResult 内部查询）
     */
    public static DefaultModDetectionEngine getInstance() {
        return instance;
    }

    /**
     * 注册所有已知模组及其冲突等级
     */
    private void registerKnownMods() {
        // ===== 阻塞级模组（与光影系统完全冲突）=====
        
        // Sodium - 完全替换渲染管线
        registerMod("sodium",
                "me.jellysquid.mods.sodium.client.SodiumClientMod",
                ConflictLevel.BLOCKING,
                "渲染管线优化器，与 Renderium SPIR-V 注入冲突");
        
        // Iris - 独占 OpenGL 光影加载
        registerMod("iris",
                "net.coderbot.iris.Iris",
                ConflictLevel.BLOCKING,
                "OpenGL 光影加载器，独占着色器管线");
        
        // Oculus - VR + Iris 分支
        registerMod("oculus",
                "curse.oculus.Oculus",
                ConflictLevel.BLOCKING,
                "VR 模组，基于 Iris 的光影系统");
        
        // Rubidium - Sodium 分支
        registerMod("rubidium",
                "me.jellysquid.mods.rubidium",
                ConflictLevel.BLOCKING,
                "Sodium 的 Fabric 移植版");

        // ===== 警告级模组（可能存在兼容性问题）=====

        // Canvas - 自定义着色器加载器
        registerMod("canvas",
                "io.github.vdan03.canvasapi.Canvas",
                ConflictLevel.WARNING,
                "自定义着色器系统，可能与 SPIR-V 注入冲突");

        // ===== 安全模组（无冲突或纯服务端优化）=====

        // Lithium - 服务端/通用优化
        registerMod("lithium",
                "com.caffeinemc.lithium.Lithium",
                ConflictLevel.SAFE,
                "通用性能优化（服务端为主），通常安全");
        
        // Phosphor - 光照优化
        registerMod("phosphor",
                "com.jamieswhiteshaker.phosphor.Phosphor",
                ConflictLevel.SAFE,
                "光照系统优化，不涉及渲染管线修改");
        
        // Starlight - 光照重写
        registerMod("starlight",
                "ca.spottedleaf.starlight.Starlight",
                ConflictLevel.SAFE,
                "光照引擎重写，不涉及渲染管线");
        
        // FerriteCore - 内存优化
        registerMod("ferritecore",
                "com.moulberry.ferritecore.FerriteCore",
                ConflictLevel.SAFE,
                "内存使用优化，不影响渲染");
        
        // LazyDFU - 数据优化
        registerMod("lazydfu",
                "com.railwaymc.lazydfu.LazyDFU",
                ConflictLevel.SAFE,
                "数据生成器优化，仅影响启动");
    }

    /**
     * 注册单个模组
     */
    private void registerMod(String id, String mainClass, ConflictLevel level, String description) {
        modRegistry.put(id.toLowerCase(), new ModInfo(mainClass, level, description));
    }

    /**
     * 获取模组信息
     */
    public ModInfo getModInfo(String modId) {
        return modRegistry.get(modId != null ? modId.toLowerCase() : "");
    }

    // ==================== ModDetectionEngine 接口实现 ====================

    @Override
    public boolean detectMod(String modId) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId 不能为 null 或空字符串");
        }

        String normalizedId = modId.toLowerCase();

        return detectionCache.computeIfAbsent(normalizedId, id -> {
            ModInfo info = modRegistry.get(id);

            if (info == null) {
                LOGGER.fine("未知模组 ID: " + id + "，跳过检测");
                return false;
            }

            try {
                Class.forName(info.mainClass());
                LOGGER.fine("✓ 检测到模组: " + id + " [" + info.description() + "]");
                return true;
            } catch (ClassNotFoundException e) {
                LOGGER.fine("✗ 未检测到模组: " + id);
                return false;
            } catch (NoClassDefFoundError e) {
                LOGGER.warning("⚠ 模组 " + id + " 类存在但依赖缺失: " + e.getMessage());
                return false;
            } catch (Exception e) {
                LOGGER.warning("检测模组 " + id + " 时异常: " + e.getMessage());
                return false;
            }
        });
    }

    @Override
    public Map<String, Boolean> batchDetect() {
        LOGGER.info("开始批量模组检测（共 " + modRegistry.size() + " 个已知模组）...");

        for (String modId : modRegistry.keySet()) {
            detectMod(modId);
        }

        long detectedCount = detectionCache.values().stream().filter(Boolean::booleanValue).count();
        LOGGER.info("批量模组检测完成：检测到 " + detectedCount + "/" + modRegistry.size() + " 个模组");

        return Map.copyOf(detectionCache);
    }

    @Override
    public String[] getDetectedMods() {
        return detectionCache.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .sorted()
                .toArray(String[]::new);
    }

    // ==================== 兼容性检查 API ====================

    /**
     * 检查光影系统兼容性
     * <p>
     * 批量检测所有已注册模组，按冲突等级分类，
     * 返回详细的兼容性报告。
     *
     * 【返回值】
     * @return CompatibilityResult - 兼容性检查结果
     *
     * 【调用时机】
     * 建议在游戏启动早期调用一次，
     * 结果用于决定启用狂暴模式还是兼容模式。
     */
    public CompatibilityResult checkShaderSystemCompatibility() {
        // 先执行批量检测
        batchDetect();

        List<String> blocking = new ArrayList<>();
        List<String> warning = new ArrayList<>();
        List<String> safe = new ArrayList<>();

        for (Map.Entry<String, Boolean> entry : detectionCache.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) continue;

            String modId = entry.getKey();
            ModInfo info = modRegistry.get(modId);

            if (info == null) continue;

            switch (info.level()) {
                case BLOCKING -> blocking.add(modId);
                case WARNING -> warning.add(modId);
                case SAFE -> safe.add(modId);
            }
        }

        CompatibilityResult result = new CompatibilityResult(blocking, warning, safe, this::getModInfo);

        // 输出诊断日志
        LOGGER.info(result.getDiagnosticMessage());

        return result;
    }

    /**
     * 快速检查光影系统是否可用
     * <p>
     * 不输出详细日志，只返回布尔值。
     * 适用于频繁调用的场景。
     *
     * 【返回值】
     * @return boolean - true 表示没有阻塞模组，光影系统可以启用
     */
    public boolean isShaderSystemAllowed() {
        // 只检查阻塞级模组
        for (Map.Entry<String, ModInfo> entry : modRegistry.entrySet()) {
            if (entry.getValue().level() == ConflictLevel.BLOCKING && detectMod(entry.getKey())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取指定模组的冲突等级
     *
     * 【方法参数】
     * @param modId String - 模组 ID
     *
     * 【返回值】
     * @return ConflictLevel - 冲突等级，未知模组返回 null
     */
    public ConflictLevel getConflictLevel(String modId) {
        ModInfo info = modRegistry.get(modId != null ? modId.toLowerCase() : "");
        return info != null ? info.level() : null;
    }

    /**
     * 注册自定义模组检测
     * <p>
     * 允许外部代码注册额外的模组检测项。
     * 用于动态扩展检测列表。
     *
     * 【方法参数】
     * @param modId        String        - 模组标识符
     * @param mainClass    String        - 主类路径
     * @param level        ConflictLevel - 冲突等级
     * @param description  String        - 描述信息
     */
    public void registerCustomMod(String modId, String mainClass,
                                  ConflictLevel level, String description) {
        registerMod(modId, mainClass, level, description);
        LOGGER.info("注册自定义模组检测: " + modId + " (" + level + ")");
    }
}
