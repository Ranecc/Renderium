package com.ranecc.renderium.infrastructure.sanitizer;

import java.util.logging.Logger;

/**
 * 实体洪泛预算 — 防止大量实体导致渲染/碰撞爆炸
 * <p>
 * 三级预算：
 * <ul>
 *   <li>绿色 (&lt; 256): 不触发，无开销</li>
 *   <li>黄色 (256-1024): 触发 LazyGuard，远距实体 LOD 降级</li>
 *   <li>红色 (&gt; 1024): 触发 LazyGuard + 实体数量硬截断至 1024</li>
 * </ul>
 */
public final class EntityBudget {

    private static final Logger LOGGER = Logger.getLogger("Renderium|EntityBudget");

    static final int YELLOW_THRESHOLD = LazyGuard.ENTITY_YELLOW_THRESHOLD;
    static final int RED_THRESHOLD = LazyGuard.ENTITY_RED_THRESHOLD;
    static final int BUDGET_MAX = 1024;

    /** 预算级别 */
    public enum Level { GREEN, YELLOW, RED }

    /**
     * 检查实体数量并返回预算级别
     *
     * @param entityCount 当前实体数量
     * @return 预算级别
     */
    public static Level check(int entityCount) {
        if (entityCount > RED_THRESHOLD) {
            LOGGER.warning("EntityBudget: RED — entityCount=" + entityCount +
                          " 截断至 " + BUDGET_MAX);
            LazyGuard.markDirty("EntityBudget RED: " + entityCount);
            return Level.RED;
        } else if (entityCount > YELLOW_THRESHOLD) {
            LOGGER.fine("EntityBudget: YELLOW — entityCount=" + entityCount);
            return Level.YELLOW;
        }
        return Level.GREEN;
    }

    /**
     * 截断实体数量到预算上限
     *
     * @param entityCount 原始实体数量
     * @return 截断后的实体数量
     */
    public static int clamp(int entityCount) {
        return Math.min(entityCount, BUDGET_MAX);
    }

    /**
     * 黄色级别是否应该 LOD 降级
     */
    public static boolean shouldDegradeLOD(int entityCount) {
        return entityCount > YELLOW_THRESHOLD;
    }
}
