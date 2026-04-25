// Renderium - Debug 调试系统 (v1)
// 提供统一的调试输出、性能监控、状态诊断功能
//
// 启用方式：
//   JVM 参数: -Drenderium.debug=true
//   配置文件: config/renderium/debug.properties (debug=true)

package com.renderium.debug;

import com.renderium.core.RenderiumMode;
import com.renderium.core.RenderiumDualModeManager;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renderium 统一调试系统。
 *
 * <p>提供以下功能：</p>
 * <ul>
 *   <li><b>分级日志</b>：INFO / WARN / ERROR / DEBUG 四级，支持按模块过滤</li>
 *   <li><b>性能计时</b>：自动记录方法执行时间，支持阈值告警</li>
 *   <li><b>状态快照</b>：一键生成当前运行状态的完整报告</li>
 *   <li><b>计数器</b>：统计事件发生次数（如 FBO 拦截次数、Vulkan 调用次数等）</li>
 * </ul>
 *
 * <h3>启用方式：</h3>
 * <pre>
 * # 方式1: JVM 参数（推荐用于开发）
 * -Drenderium.debug=true
 *
 * # 方式2: 配置文件
 * 在 config/renderium/debug.properties 中设置 debug=true
 * </pre>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 基本日志
 * RenderiumDebug.log("VulkanBackend", "Device created successfully");
 *
 * // 带参数的日志
 * RenderiumDebug.log("Memory", "Arena allocated {} bytes", size);
 *
 * // 计时
 * try (var ignored = RenderiumDebug.timer("RenderSystem.init")) {
 *     // ... 初始化代码 ...
 * }
 *
 * // 计数器
 * RenderiumDebug.counter("fbo_intercept").increment();
 *
 * // 状态快照
 * String report = RenderiumDebug.generateSnapshot();
 * </pre>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 1.0.0
 */
public final class RenderiumDebug {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("Renderium-Debug");

    /** JVM 参数名：启用调试模式 */
    private static final String DEBUG_PROPERTY = "renderium.debug";

    /** JVM 参数名：启用详细跟踪（更详细的输出） */
    private static final String VERBOSE_PROPERTY = "renderium.debug.verbose";

    /** 是否启用调试模式（从 JVM 参数读取） */
    private static final boolean DEBUG_ENABLED = Boolean.getBoolean(DEBUG_PROPERTY);

    /** 是否启用详细模式（从 JVM 参数读取） */
    private static final boolean VERBOSE_ENABLED = Boolean.getBoolean(VERBOSE_PROPERTY);

    /** 时间格式化器 */
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** 计数器存储（线程安全） */
    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    /** 活跃计时器存储（线程安全） */
    private static final Map<String, Long> ACTIVE_TIMERS = new ConcurrentHashMap<>();

    /** 内存管理器 */
    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    /** 运行时管理器 */
    private static final RuntimeMXBean RUNTIME_BEAN = ManagementFactory.getRuntimeMXBean();

    /**
     * 私有构造函数（工具类）
     */
    private RenderiumDebug() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ==================== 状态查询 ====================

    /**
     * 检查是否启用了调试模式
     *
     * @return true 如果调试模式已启用
     */
    public static boolean isDebugEnabled() {
        return DEBUG_ENABLED;
    }

    /**
     * 检查是否启用了详细模式
     *
     * @return true 如果详细模式已启用
     */
    public static boolean isVerboseEnabled() {
        return VERBOSE_ENABLED;
    }

    // ==================== 日志输出 ====================

    /**
     * 输出 INFO 级别日志
     *
     * @param module 模块名称（如 "VulkanBackend"、"Memory"）
     * @param message 日志消息
     * @param args   消息参数（可选，支持 {} 占位符）
     */
    public static void log(String module, String message, Object... args) {
        if (!DEBUG_ENABLED) return;

        String formattedMessage = formatMessage(module, message, args);
        LOGGER.info(formattedMessage);
    }

    /**
     * 输出 WARNING 级别日志（始终输出，不受 debug 开关控制）
     *
     * @param module 模块名称
     * @param message 日志消息
     * @param args   消息参数
     */
    public static void warn(String module, String message, Object... args) {
        String formattedMessage = formatMessage(module, message, args);
        LOGGER.warning(formattedMessage);
    }

    /**
     * 输出 ERROR 级别日志（始终输出）
     *
     * @param module 模块名称
     * @param message 日志消息
     * @param e      异常对象
     */
    public static void error(String module, String message, Throwable e) {
        String formattedMessage = formatMessage(module, message);
        LOGGER.log(Level.SEVERE, formattedMessage, e);
    }

    /**
     * 输出 DEBUG 级别日志（仅详细模式）
     *
     * @param module 模块名称
     * @param message 日志消息
     * @param args   消息参数
     */
    public static void trace(String module, String message, Object... args) {
        if (!VERBOSE_ENABLED) return;

        String formattedMessage = formatMessage(module, message, args);
        LOGGER.fine(formattedMessage);
    }

    // ==================== 分隔线输出 ====================

    /**
     * 输出一个醒目的分隔线（用于标记重要阶段开始/结束）
     *
     * @param title 分隔线标题
     */
    public static void separator(String title) {
        if (!DEBUG_ENABLED) return;

        String line = "═".repeat(60);
        LOGGER.info("");
        LOGGER.info(line);
        LOGGER.info("  " + title);
        LOGGER.info(line);
        LOGGER.info("");
    }

    // ==================== 计时器 ====================

    /**
     * 开始一个计时器
     *
     * @param name 计时器名称（建议使用 "ClassName.methodName" 格式）
     * @return 计时器 ID（用于 stopTimer）
     */
    public static long startTimer(String name) {
        if (!DEBUG_ENABLED) return -1L;

        long startTime = System.nanoTime();
        ACTIVE_TIMERS.put(name, startTime);

        trace("Timer", "[START] {}", name);
        return startTime;
    }

    /**
     * 停止一个计时器并记录耗时
     *
     * @param name       计时器名称
     * @param warnThresholdMs 超过此毫秒数则输出警告（0 表示不警告）
     * @return 执行耗时（毫秒），如果计时器不存在返回 -1
     */
    public static long stopTimer(String name, long warnThresholdMs) {
        Long startTime = ACTIVE_TIMERS.remove(name);
        if (startTime == null) return -1L;

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        if (warnThresholdMs > 0 && elapsedMs > warnThresholdMs) {
            log("Timer", "[SLOW] {} took {} ms (threshold: {} ms)", name, elapsedMs, warnThresholdMs);
        } else {
            trace("Timer", "[END] {} took {} ms", name, elapsedMs);
        }

        return elapsedMs;
    }

    /**
     * 创建 AutoCloseable 计时器（推荐用于 try-with-resources）
     *
     * @param name 计时器名称
     * @return AutoCloseable 对象，关闭时自动停止计时
     */
    public static AutoCloseable timer(String name) {
        startTimer(name);
        return () -> stopTimer(name, 50); // 默认 50ms 阈值
    }

    // ==================== 计数器 ====================

    /**
     * 获取或创建一个计数器
     *
     * @param name 计数器名称
     * @return AtomicLong 计数器实例
     */
    public static AtomicLong counter(String name) {
        return COUNTERS.computeIfAbsent(name, k -> new AtomicLong(0));
    }

    /**
     * 递增计数器并返回新值
     *
     * @param name 计数器名称
     * @return 递增后的值
     */
    public static long incrementCounter(String name) {
        return counter(name).incrementAndGet();
    }

    /**
     * 获取所有计数器的快照
     *
     * @return 计数器名称到值的不可变映射
     */
    public static Map<String, Long> getCounterSnapshot() {
        Map<String, Long> snapshot = new ConcurrentHashMap<>();
        COUNTERS.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    // ==================== 状态快照 ====================

    /**
     * 生成当前运行状态的完整快照
     *
     * <p>包含以下信息：</p>
     * <ul>
     *   <li>系统信息（JVM、OS、CPU、内存）</li>
     *   <li>Renderium 状态（运行模式、组件初始化情况）</li>
     *   <li>计数器统计</li>
     *   <li>Vulkan 后端状态（如果可用）</li>
     * </ul>
     *
     * @return 格式化的状态报告字符串
     */
    public static String generateSnapshot() {
        StringBuilder sb = new StringBuilder(2048);
        LocalDateTime now = LocalDateTime.now();

        sb.append("\n╔══════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║  Renderium Debug Snapshot - %s", now.format(TIME_FORMAT)));
        sb.append(" ".repeat(Math.max(0, 42 - now.format(TIME_FORMAT).length()))).append("║\n");
        sb.append("╠══════════════════════════════════════════════════════════╣\n");

        // 1. 系统信息
        appendSection(sb, "System Info");
        appendKeyValue(sb, "JVM", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        appendKeyValue(sb, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        appendKeyValue(sb, "CPU", System.getProperty("os.arch"));
        appendKeyValue(sb, "Uptime", formatUptime(RUNTIME_BEAN.getUptime()));

        // 2. 内存信息
        appendSection(sb, "Memory Usage");
        MemoryUsage heap = MEMORY_BEAN.getHeapMemoryUsage();
        MemoryUsage nonHeap = MEMORY_BEAN.getNonHeapMemoryUsage();
        appendKeyValue(sb, "Heap", formatBytes(heap.getUsed()) + " / " + formatBytes(heap.getMax()));
        appendKeyValue(sb, "Non-Heap", formatBytes(nonHeap.getUsed()) + " / " + formatBytes(nonHeap.getMax()));

        // 3. Renderium 状态
        appendSection(sb, "Renderium Status");
        try {
            RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();
            appendKeyValue(sb, "Mode", dualMode.getCurrentMode().name());
            appendKeyValue(sb, "Sodium Present", String.valueOf(dualMode.isPerformanceModPresent()));
            // 注意: isInitialized() 方法可能不存在，使用 try-catch 保护
            try {
                appendKeyValue(sb, "Initialized", String.valueOf(dualMode.getClass().getMethod("isInitialized").invoke(dualMode)));
            } catch (Exception e) {
                appendKeyValue(sb, "Initialized", "N/A");
            }
        } catch (Exception e) {
            appendKeyValue(sb, "Error", "Failed to get DualModeManager: " + e.getMessage());
        }

        // 4. 计数器
        if (!COUNTERS.isEmpty()) {
            appendSection(sb, "Counters");
            COUNTERS.forEach((k, v) -> appendKeyValue(sb, k, String.valueOf(v.get())));
        }

        // 5. Debug 配置
        appendSection(sb, "Debug Config");
        appendKeyValue(sb, "Debug Mode", DEBUG_ENABLED ? "ENABLED" : "DISABLED");
        appendKeyValue(sb, "Verbose Mode", VERBOSE_ENABLED ? "ENABLED" : "DISABLED");
        appendKeyValue(sb, "Active Timers", String.valueOf(ACTIVE_TIMERS.size()));

        sb.append("╚══════════════════════════════════════════════════════════╝\n");

        return sb.toString();
    }

    /**
     * 将快照输出到日志
     */
    public static void logSnapshot() {
        if (!DEBUG_ENABLED) return;
        LOGGER.info(generateSnapshot());
    }

    // ==================== 内部工具方法 ====================

    /**
     * 格式化消息（支持 {} 占位符）
     */
    private static String formatMessage(String module, String message, Object... args) {
        String timestamp = LocalDateTime.now().format(TIME_FORMAT);
        String formatted = message;

        // 替换占位符
        for (Object arg : args) {
            formatted = formatted.replaceFirst("\\{}", arg != null ? arg.toString() : "null");
        }

        return String.format("[%s] [%s] %s", timestamp, module, formatted);
    }

    /**
     * 添加分区标题
     */
    private static void appendSection(StringBuilder sb, String title) {
        sb.append("├── ").append(title).append("\n");
    }

    /**
     * 添加键值对
     */
    private static void appendKeyValue(StringBuilder sb, String key, String value) {
        sb.append("│  ").append(String.format("%-20s : %s", key, value)).append("\n");
    }

    /**
     * 格式化字节数为人类可读形式
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 格式化运行时间为人类可读形式
     */
    private static String formatUptime(long uptimeMs) {
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds % 60);
        return String.format("%ds", seconds);
    }
}
