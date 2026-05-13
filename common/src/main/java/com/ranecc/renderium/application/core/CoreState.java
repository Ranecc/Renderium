// TODO: [REVIEW] Stub class - CoreState not yet implemented
// Design doc reference: 01-core-architecture.md (lifecycle state machine)
// Required by: RenderiumController.java, and 20+ other files

package com.ranecc.renderium.application.core;

/**
 * Lifecycle state enumeration for RenderiumCore.
 * <p>Tracks the initialization and runtime state of the rendering engine.
 *
 * TODO: [REVIEW] Replace with full state machine implementation including:
 * <ul>
 *   <li>State transition validation</li>
 *   <li>Thread-safe state access</li>
 *   <li>State change event publishing</li>
 * </ul>
 */
public enum CoreState {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    RUNNING,
    ERROR,
    SHUTDOWN
}
