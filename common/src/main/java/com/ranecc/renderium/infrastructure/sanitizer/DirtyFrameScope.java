package com.ranecc.renderium.infrastructure.sanitizer;

/**
 * 脏帧作用域 — try-finally 模式保存/恢复渲染状态
 * <p>
 * 使用方式：
 * <pre>
 * DirtyFrameScope scope = DirtyFrameScope.enter();
 * try {
 *     // 渲染管线执行
 * } finally {
 *     scope.close();
 * }
 * </pre>
 * <p>
 * 干净帧：不进入 DirtyFrameScope，零开销
 * 脏帧：save + restore ≈ 100ns
 */
public final class DirtyFrameScope implements AutoCloseable {

    private final StateSnapshot snapshot;

    private DirtyFrameScope() {
        this.snapshot = StateSnapshot.capture();
    }

    /**
     * 进入脏帧作用域
     * @return DirtyFrameScope 实例，需在 finally 中调用 close()
     */
    public static DirtyFrameScope enter() {
        return new DirtyFrameScope();
    }

    /**
     * 退出脏帧作用域，恢复渲染状态
     */
    @Override
    public void close() {
        if (snapshot != null) {
            snapshot.restore();
        }
    }

    public StateSnapshot getSnapshot() {
        return snapshot;
    }
}
