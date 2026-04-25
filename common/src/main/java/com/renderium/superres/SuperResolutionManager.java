// Renderium - Super Resolution Manager
// 超分辨率技术统一管理器，自动检测和降级

package com.renderium.superres;
import com.renderium.interception.context.SuperResolutionContext;

import com.renderium.streamline.SLConfigLoader;
import com.renderium.streamline.SLContext;

import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 超分辨率管理器
 * <p>
 * 统一管理 DLSS、XeSS、FSR 等超分辨率技术。
 * 提供自动检测、降级和手动选择功能。
 * <p>
 * 降级优先级：DLSS → XeSS → FSR → Native
 * <p>
 * 使用方式：
 * <pre>
 * SuperResolutionManager srm = new SuperResolutionManager(slContext, configLoader);
 * srm.detectAndSelect(); // 自动检测最佳技术
 * srm.setQuality(Quality.BALANCED);
 * srm.evaluate(frameData);
 * </pre>
 *
 * @see SuperResolutionAdapter
 * @see SLContext
 */
public final class SuperResolutionManager {

    private static final Logger LOGGER = Logger.getLogger(SuperResolutionManager.class.getName());

    private final Map<SuperResolutionAdapter.Technology, SuperResolutionAdapter> adapters = new EnumMap<>(SuperResolutionAdapter.Technology.class);
    private SuperResolutionAdapter activeAdapter;
    private SuperResolutionAdapter.Technology preferredTechnology;
    private SuperResolutionAdapter.Quality quality = SuperResolutionAdapter.Quality.BALANCED;

    private final SLContext slContext;
    private final SLConfigLoader configLoader;

    public SuperResolutionManager(SLContext slContext, SLConfigLoader configLoader) {
        this.slContext = slContext;
        this.configLoader = configLoader;
    }

    /**
     * 注册适配器
     *
     * @param adapter 适配器实例
     */
    public void registerAdapter(SuperResolutionAdapter adapter) {
        adapters.put(adapter.getTechnology(), adapter);
        LOGGER.info("Registered adapter: " + adapter.getName());
    }

    /**
     * 自动检测可用技术并选择最佳
     * <p>
     * 降级优先级：DLSS → XeSS → FSR → Native
     *
     * @return 选中的技术
     */
    public SuperResolutionAdapter.Technology detectAndSelect() {
        // 按优先级检测
        SuperResolutionAdapter.Technology[] priority = {
            SuperResolutionAdapter.Technology.DLSS,
            SuperResolutionAdapter.Technology.XESS,
            SuperResolutionAdapter.Technology.FSR,
            SuperResolutionAdapter.Technology.NATIVE
        };

        for (SuperResolutionAdapter.Technology tech : priority) {
            // 如果用户有偏好，优先使用
            if (preferredTechnology != null && tech != preferredTechnology) {
                continue;
            }

            SuperResolutionAdapter adapter = adapters.get(tech);
            if (adapter != null && adapter.isAvailable()) {
                activeAdapter = adapter;
                LOGGER.info("Selected super resolution technology: " + adapter.getName());
                return tech;
            }
        }

        // 如果偏好技术不可用，尝试其他
        if (preferredTechnology != null) {
            for (SuperResolutionAdapter.Technology tech : priority) {
                SuperResolutionAdapter adapter = adapters.get(tech);
                if (adapter != null && adapter.isAvailable()) {
                    activeAdapter = adapter;
                    LOGGER.info("Preferred technology unavailable, fallback to: " + adapter.getName());
                    return tech;
                }
            }
        }

        LOGGER.warning("No super resolution technology available");
        return SuperResolutionAdapter.Technology.NATIVE;
    }

    /**
     * 启用超分辨率
     */
    public void enable() {
        if (activeAdapter != null && activeAdapter.isAvailable()) {
            activeAdapter.enable(quality);
            LOGGER.info("Super resolution enabled: " + activeAdapter.getName() + " @ " + quality);
        }
    }

    /**
     * 禁用超分辨率
     */
    public void disable() {
        if (activeAdapter != null) {
            activeAdapter.disable();
        }
    }

    /**
     * 设置质量
     */
    public void setQuality(SuperResolutionAdapter.Quality quality) {
        this.quality = quality;
        if (activeAdapter != null && activeAdapter.isEnabled()) {
            activeAdapter.setQuality(quality);
        }
    }

    /**
     * 设置偏好技术
     *
     * @param technology 偏好技术
     */
    public void setPreferredTechnology(SuperResolutionAdapter.Technology technology) {
        this.preferredTechnology = technology;
        // 如果当前适配器不是偏好的，重新选择
        if (activeAdapter != null && activeAdapter.getTechnology() != technology) {
            SuperResolutionAdapter newAdapter = adapters.get(technology);
            if (newAdapter != null && newAdapter.isAvailable()) {
                if (activeAdapter.isEnabled()) {
                    activeAdapter.disable();
                }
                activeAdapter = newAdapter;
                activeAdapter.enable(quality);
                LOGGER.info("Switched to preferred technology: " + activeAdapter.getName());
            }
        }
    }

    /**
     * 执行帧评估
     */
    public void evaluate(SuperResolutionAdapter.FrameData frameData) {
        if (activeAdapter != null && activeAdapter.isEnabled()) {
            activeAdapter.evaluate(frameData);
        }
    }

    /**
     * 更新分辨率
     */
    public void updateResolution(int renderWidth, int renderHeight,
                                  int displayWidth, int displayHeight) {
        if (activeAdapter != null) {
            activeAdapter.updateResolution(renderWidth, renderHeight, displayWidth, displayHeight);
        }
    }

    /**
     * 关闭所有适配器
     */
    public void shutdown() {
        for (SuperResolutionAdapter adapter : adapters.values()) {
            adapter.shutdown();
        }
        adapters.clear();
        activeAdapter = null;
    }

    // ==================== Getter ====================

    public SuperResolutionAdapter getActiveAdapter() { return activeAdapter; }
    public SuperResolutionAdapter.Quality getQuality() { return quality; }
    public boolean isEnabled() { return activeAdapter != null && activeAdapter.isEnabled(); }
    public boolean isAvailable() { return activeAdapter != null && activeAdapter.isAvailable(); }
    public Map<SuperResolutionAdapter.Technology, SuperResolutionAdapter> getAdapters() { return Map.copyOf(adapters); }
}
