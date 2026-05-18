package com.ranecc.renderium.application.core;

/**
 * Lifecycle state enumeration for RenderiumCore with state machine validation.
 *
 * <p>Defines 6 states and valid transitions between them:
 * <pre>
 * NOT_INITIALIZED ──→ INITIALIZING ──→ READY ──→ RUNNING
 *                        │              │          │
 *                        ↓              ↓          ↓
 *                      ERROR ────────→ SHUTDOWN
 * </pre>
 */
public enum CoreState {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    RUNNING,
    ERROR,
    SHUTDOWN;

    /**
     * Checks whether a transition from this state to the target state is valid.
     *
     * @param target the target state to transition to
     * @return true if the transition is allowed
     */
    public boolean canTransitionTo(CoreState target) {
        return switch (this) {
            case NOT_INITIALIZED -> target == INITIALIZING || target == ERROR;
            case INITIALIZING    -> target == READY || target == ERROR || target == SHUTDOWN;
            case READY           -> target == RUNNING || target == ERROR || target == SHUTDOWN;
            case RUNNING         -> target == ERROR || target == SHUTDOWN;
            case ERROR           -> target == SHUTDOWN;
            case SHUTDOWN        -> false;
        };
    }

    /**
     * Validates and performs a state transition.
     *
     * @param target the target state to transition to
     * @return the target state if the transition is valid
     * @throws IllegalStateException if the transition is invalid
     */
    public CoreState transitionTo(CoreState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                "Invalid state transition: " + this + " → " + target);
        }
        return target;
    }

    /**
     * Returns whether this state is terminal (no further transitions possible).
     */
    public boolean isTerminal() {
        return this == SHUTDOWN;
    }

    /**
     * Returns whether this state represents an error condition.
     */
    public boolean isError() {
        return this == ERROR;
    }
}
