// Renderium - Mesh Build Task
// Thread-safe task ID generation using AtomicLong

package com.ranecc.renderium.feature.renderopt.mesh;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mesh build task for multi-threaded mesh construction.
 *
 * <p>Describes a section mesh build request with all required input data.
 * Tasks are immutable and can be safely passed between threads.</p>
 *
 * @see MeshBuildScheduler
 * @see MeshBuildResult
 * @since 1.0.0
 */
public final class MeshBuildTask {

    /** Task priority levels */
    public enum Priority {
        /** Low priority: distant sections, LOD meshes */
        LOW(0),
        /** Normal priority: regular section updates */
        NORMAL(1),
        /** High priority: near-player sections, first load */
        HIGH(2),
        /** Urgent: player-facing direction, forced rebuild */
        URGENT(3);

        public final int level;

        Priority(int level) {
            this.level = level;
        }
    }

    /** Section X index */
    public final int sectionX;

    /** Section Y index */
    public final int sectionY;

    /** Section Z index */
    public final int sectionZ;

    /** Chunk X coordinate */
    public final int chunkX;

    /** Chunk Z coordinate */
    public final int chunkZ;

    /** Block state ID array (16x16x16) */
    public final int[] blockStates;

    /** Task priority */
    public final Priority priority;

    /** Task creation timestamp (nanoseconds) */
    public final long createTimeNs;

    /** Task ID (globally unique) */
    public final long taskId;

    /**
     * Global task ID counter - thread-safe using AtomicLong.
     * Fixes race condition where multiple threads could get same ID.
     */
    private static final AtomicLong NEXT_TASK_ID = new AtomicLong(0);

    /**
     * Create a mesh build task with specified priority.
     *
     * @param sectionX  Section X index
     * @param sectionY  Section Y index
     * @param sectionZ  Section Z index
     * @param chunkX    Chunk X coordinate
     * @param chunkZ    Chunk Z coordinate
     * @param blockStates Block state array (4096 elements)
     * @param priority  Task priority level
     */
    public MeshBuildTask(int sectionX, int sectionY, int sectionZ,
                         int chunkX, int chunkZ,
                         int[] blockStates, Priority priority) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.blockStates = blockStates;
        this.priority = priority;
        this.createTimeNs = System.nanoTime();
        // Atomic increment ensures unique IDs even under heavy multi-threading
        this.taskId = NEXT_TASK_ID.incrementAndGet();
    }

    /**
     * Create a normal priority mesh build task.
     */
    public MeshBuildTask(int sectionX, int sectionY, int sectionZ,
                         int chunkX, int chunkZ, int[] blockStates) {
        this(sectionX, sectionY, sectionZ, chunkX, chunkZ, blockStates, Priority.NORMAL);
    }

    @Override
    public String toString() {
        return String.format(
            "MeshBuildTask{id=%d, pos=(%d,%d,%d), priority=%s}",
            taskId, sectionX, sectionY, sectionZ, priority.name()
        );
    }
}
