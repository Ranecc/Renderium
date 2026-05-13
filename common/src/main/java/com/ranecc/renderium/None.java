package com.ranecc.renderium;

/**
 * 空类型标记 — 占位存根
 *
 * <p>用于泛型占位、Void 替代或"无返回值"场景的类型安全标记。
 * 在实际实现中应替换为具体的业务类型或删除。</p>
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>泛型类型参数占位（避免使用原始类型）</li>
 *   <li>API 设计中的"无操作"标记</li>
 *   <li>Builder 模式中的终止节点</li>
 * </ul>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 3.0.0
 */
public final class None {

    /** 单例实例（None 是无状态的） */
    public static final None INSTANCE = new None();

    /**
     * 私有构造方法（强制使用单例）
     */
    private None() {
        // 防止实例化
    }

    /**
     * 获取 None 单例实例
     *
     * @return None 全局唯一实例
     */
    public static None getInstance() {
        return INSTANCE;
    }

    @Override
    public String toString() {
        return "None{}";
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
