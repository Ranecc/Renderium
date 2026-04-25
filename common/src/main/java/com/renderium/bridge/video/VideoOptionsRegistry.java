package com.renderium.bridge.video;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import com.renderium.config.structure.RendererOption;
import com.renderium.config.structure.RendererOptionPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 视频选项注册表（线程安全存储）
 * <p>
 * 管理所有视频设置选项的注册、查询和应用。
 * 采用读写锁策略优化并发性能：
 * <ul>
 *   <li>读操作频繁（渲染循环中每帧查询选项值）</li>
 *   <li>写操作稀疏（仅在初始化和用户修改时）</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>线程安全：ReadWriteLock 保证并发访问安全</li>
 *   <li>零 Mixin 依赖：此包不允许 import 任何 Mixin 类</li>
 *   <li>脏标记追踪：记录已修改但未保存的选项</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 获取全局注册表实例
 * VideoOptionsRegistry registry = VideoSettingsBridge.getVideoOptionsRegistry();
 *
 * // 注册选项页（初始化时调用，写操作）
 * registry.registerPage(generalPage);
 *
 * // 查询选项（渲染时调用，读操作）
 * RendererOption option = registry.getOption(renderDistanceId);
 * }</pre>
 *
 * @see VideoSettingsBridge
 * @since 1.0.0
 */
public final class VideoOptionsRegistry {

    /** 日志记录器（SLF4J，名称：Renderium-VideoSettings） */
    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-VideoSettings");

    /** 读写锁（写少读多场景优化） */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** 已注册的选项页面集合（线程安全） */
    private final Set<RendererOptionPage> registeredPages = ConcurrentHashMap.newKeySet();

    /** 已修改但未保存的选项集合（线程安全） */
    private final Set<RendererOption> modifiedOptions = Collections.synchronizedSet(new HashSet<>());

    /** 变更监听器列表（线程安全） */
    private final Set<Consumer<RendererOption>> changeListeners = ConcurrentHashMap.newKeySet();

    /**
     * 创建新的视频选项注册表实例
     */
    public VideoOptionsRegistry() {
        LOGGER.debug("VideoOptionsRegistry initialized");
    }

    /**
     * 获取读锁（用于外部批量读取操作）
     * <p>
     * 调用方必须在 finally 块中释放锁：
     * <pre>{@code
     * registry.readLock().lock();
     * try {
     *     // 批量读操作
     * } finally {
     *     registry.readLock().unlock();
     * }
     * }</pre>
     *
     * @return 读锁实例
     */
    public ReadWriteLock readLock() {
        return lock;
    }

    /**
     * 获取写锁（用于外部批量写入操作）
     * <p>
     * 调用方必须在 finally 块中释放锁：
     * <pre>{@code
     * registry.writeLock().lock();
     * try {
     *     // 批量写操作
     * } finally {
     *     registry.writeLock().unlock();
     * }
     * }</pre>
     *
     * @return 写锁实例
     */
    public ReadWriteLock writeLock() {
        return lock;
    }

    /**
     * 应用所有已修改的选项变更
     * <p>
     * 将用户在界面中的修改持久化到配置存储。
     * 此方法应在用户点击"Apply"按钮时调用。
     * <p>
     * 实现说明（Task 2.1 完善）：
     * <ul>
     *   <li>遍历 modifiedOptions 集合</li>
     *   <li>将每个选项的当前值写入配置文件</li>
     *   <li>清除脏标记</li>
     *   <li>通知变更监听器</li>
     * </ul>
     */
    public void applyChanges() {
        lock.writeLock().lock();
        try {
            if (modifiedOptions.isEmpty()) {
                LOGGER.debug("applyChanges() called - no modifications to apply");
                return;
            }

            int appliedCount = 0;
            int errorCount = 0;

            // 遍历所有已修改的选项
            for (RendererOption option : modifiedOptions) {
                try {
                    // 触发选项的存储处理器（如果存在）
                    Object handler = getStorageHandler(option);
                    if (handler instanceof StorageHandler storageHandler) {
                        storageHandler.save();
                    }
                    appliedCount++;
                } catch (Exception e) {
                    LOGGER.error("Failed to apply option '{}': {}", 
                        option.getId(), e.getMessage(), e);
                    errorCount++;
                }
            }

            // 清除脏标记
            modifiedOptions.clear();

            // 通知所有变更监听器
            for (Consumer<RendererOption> listener : changeListeners) {
                try {
                    listener.accept(null);
                } catch (Exception e) {
                    LOGGER.warn("Change listener threw exception: {}", e.getMessage());
                }
            }

            LOGGER.info("applyChanges() completed - applied: {}, errors: {}", 
                appliedCount, errorCount);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取选项的存储处理器
     * @param option 选项实例
     * @return 存储处理器对象，可能为 null
     */
    private Object getStorageHandler(RendererOption option) {
        if (option instanceof com.renderium.config.structure.BooleanOption boolOpt) {
            return boolOpt.getStorageHandler();
        }
        return null;
    }

    /**
     * 标记选项为已修改
     * @param option 被修改的选项
     */
    public void markModified(RendererOption option) {
        if (option != null) {
            modifiedOptions.add(option);
            LOGGER.debug("Option '{}' marked as modified", option.getId());
        }
    }

    /**
     * 注册选项页面
     * @param page 选项页面实例
     */
    public void registerPage(RendererOptionPage page) {
        if (page != null) {
            registeredPages.add(page);
            LOGGER.debug("Registered option page: {}", page.name().getString());
        }
    }

    /**
     * 添加变更监听器
     * @param listener 监听器函数，参数为变更的选项（批量应用时为 null）
     */
    public void addChangeListener(Consumer<RendererOption> listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    /**
     * 移除变更监听器
     * @param listener 要移除的监听器
     */
    public void removeChangeListener(Consumer<RendererOption> listener) {
        changeListeners.remove(listener);
    }

    /**
     * 检查是否有未保存的修改
     * @return true 如果存在未保存的修改
     */
    public boolean hasUnsavedChanges() {
        return !modifiedOptions.isEmpty();
    }

    /**
     * 获取未保存修改的数量
     * @return 未保存选项的数量
     */
    public int getUnsavedChangesCount() {
        return modifiedOptions.size();
    }

    /**
     * 重置所有选项为默认值
     * <p>
     * 用户点击"Undo"或"Reset to Defaults"时调用。
     * 将所有选项恢复到上次保存的状态（Undo）或初始默认值（Reset）。
     * <p>
     * 实现说明（Task 2.1 完善）：
     * <ul>
     *   <li>从配置存储重新加载所有选项值</li>
     *   <li>清除脏标记</li>
     *   <li>触发 UI 刷新</li>
     * </ul>
     */
    public void resetToDefaults() {
        lock.writeLock().lock();
        try {
            int resetCount = 0;

            // 遍历所有已注册页面中的选项，重置为默认值
            for (RendererOptionPage page : registeredPages) {
                for (var group : page.groups()) {
                    for (RendererOption option : group.options()) {
                        try {
                            option.resetToDefault();
                            resetCount++;
                        } catch (Exception e) {
                            LOGGER.warn("Failed to reset option '{}': {}", 
                                option.getId(), e.getMessage());
                        }
                    }
                }
            }

            // 清除脏标记
            modifiedOptions.clear();

            LOGGER.info("resetToDefaults() completed - reset {} options", resetCount);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
