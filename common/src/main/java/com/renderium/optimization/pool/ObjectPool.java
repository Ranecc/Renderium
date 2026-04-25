// Renderium - High-Performance Object Pool
// Thread-local pooling for zero-allocation hot paths

package com.renderium.optimization.pool;

import com.renderium.config.ConfigConstants;

import java.lang.ref.SoftReference;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * High-performance object pool for reducing GC pressure on hot paths.
 *
 * <p><b>Design Goals:</b></p>
 * <ul>
 *   <li>Zero allocation when pool has available objects</li>
 *   <li>ThreadLocal isolation to avoid contention</li>
 *   <li>SoftReference-based global overflow to prevent OOM</li>
 *   <li>O(1) acquire/release operations</li>
 * </ul>
 *
 * <h3>Concurrency Safety:</h3>
 * <ul>
 *   <li>Thread-local pool uses ArrayDeque per thread - no contention</li>
 *   <li>Global overflow uses ConcurrentLinkedQueue - thread-safe lock-free queue</li>
 *   <li>Statistics counters use AtomicLong - thread-safe atomic operations</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Create pool with factory
 * ObjectPool<float[]> arrayPool = new ObjectPool<>(() -> new float[16], 64);
 *
 * // Acquire from pool (returns existing or creates new)
 * float[] matrix = arrayPool.acquire();
 *
 * // Use the object...
 * System.arraycopy(source, 0, matrix, 0, 16);
 *
 * // Return to pool for reuse
 * arrayPool.release(matrix);
 * }</pre>
 *
 * @param <T> Type of pooled objects
 * @since 3.0.0
 */
public final class ObjectPool<T> {

    /** Factory for creating new instances */
    private final Supplier<T> factory;

    /** Maximum capacity per thread (prevents memory leak) */
    private final int maxPerThread;

    /**
     * Thread-local pool storage.
     * Each thread gets its own queue of pooled objects.
     * Thread-safety: ThreadLocal guarantees no cross-thread contention.
     */
    private final ThreadLocal<Queue<T>> threadLocalPool = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Global overflow pool (shared across threads, uses SoftReference).
     * Only used when local pool is empty and we want to avoid allocation.
     * Thread-safety: ConcurrentLinkedQueue provides lock-free thread-safe operations.
     */
    private final ConcurrentLinkedQueue<SoftReference<T>> globalOverflow = new ConcurrentLinkedQueue<>();

    /**
     * Statistics counters - use AtomicLong for thread-safe increments.
     * These may be accessed from multiple threads during acquire/release.
     */
    private final AtomicLong totalAcquires = new AtomicLong(0);
    private final AtomicLong totalHits = new AtomicLong(0);      // Pool hit (reused)
    private final AtomicLong totalMisses = new AtomicLong(0);    // Pool miss (created new)

    /**
     * Create an object pool with specified factory and capacity.
     *
     * @param factory     Factory function for creating new objects
     * @param maxPerThread Maximum objects to keep per thread (default 32)
     */
    public ObjectPool(Supplier<T> factory, int maxPerThread) {
        this.factory = factory;
        this.maxPerThread = maxPerThread;
    }

    /**
     * Create an object pool with default capacity (32 per thread).
     *
     * @param factory Factory function for creating new objects
     */
    public ObjectPool(Supplier<T> factory) {
        this(factory, ConfigConstants.DEFAULT_OBJECT_POOL_CAPACITY);
    }

    /**
     * Acquire an object from the pool.
     *
     * <p>Returns a previously released object if available,
     * otherwise creates a new one using the factory.</p>
     *
     * <p>Thread-safety: Safe to call from any thread.
     * Uses ThreadLocal for local pool and ConcurrentLinkedQueue for global pool.
     *
     * @return Pooled or newly created object (never null)
     */
    public T acquire() {
        totalAcquires.incrementAndGet();

        // Fast path: try thread-local pool first (no contention - ThreadLocal isolated)
        Queue<T> localPool = threadLocalPool.get();
        T obj = localPool.poll();

        if (obj != null) {
            totalHits.incrementAndGet();
            return obj;
        }

        // Slow path: try global overflow pool (thread-safe via ConcurrentLinkedQueue)
        SoftReference<T> ref;
        while ((ref = globalOverflow.poll()) != null) {
            obj = ref.get();
            if (obj != null) {
                totalHits.incrementAndGet();
                return obj;
            }
            // Reference was GC'd, try next
        }

        // Last resort: create new object
        totalMisses.incrementAndGet();
        return factory.get();
    }

    /**
     * Release an object back to the pool.
     *
     * <p>The object will be reused by future acquire() calls.
     * If the local pool is full, overflows to global pool.</p>
     *
     * <p>Thread-safety: Safe to call from any thread.
     *
     * @param obj Object to release (must not be null)
     */
    public void release(T obj) {
        if (obj == null) {
            throw new IllegalArgumentException("Cannot release null object");
        }

        Queue<T> localPool = threadLocalPool.get();

        if (localPool.size() < maxPerThread) {
            // Add to local pool (fast path, no contention - ThreadLocal isolated)
            localPool.offer(obj);
        } else {
            // Local pool full - add to global overflow (thread-safe via ConcurrentLinkedQueue)
            // Uses SoftReference so these can be GC'd under memory pressure
            globalOverflow.offer(new SoftReference<>(obj));
        }
    }

    /**
     * Clear all pooled objects (for testing or shutdown).
     *
     * <p>Note: Only clears the current thread's local pool.
     * Other threads' local pools are unaffected due to ThreadLocal isolation.
     */
    public void clear() {
        threadLocalPool.get().clear();
        globalOverflow.clear();
        totalAcquires.set(0);
        totalHits.set(0);
        totalMisses.set(0);
    }

    /**
     * Get current pool statistics.
     *
     * @return String describing hit rate and pool sizes
     */
    public String getStats() {
        long total = totalAcquires.get();
        if (total == 0) return "ObjectPool: no operations yet";

        double hitRate = (double) totalHits.get() / total * 100;
        return String.format(
            "ObjectPool[hitRate=%.1f%%, hits=%d, misses=%d, local=%d, global=%d]",
            hitRate, totalHits.get(), totalMisses.get(),
            threadLocalPool.get().size(), globalOverflow.size()
        );
    }

    /**
     * Get pool hit rate (percentage).
     */
    public double getHitRate() {
        long total = totalAcquires.get();
        return total > 0 ? (double) totalHits.get() / total * 100 : 0;
    }

    // ==================== Predefined Common Pools ====================

    /** Pool for 2-element float arrays (jitter offsets, UV coords) */
    private static final ObjectPool<float[]> FLOAT_2_POOL =
        new ObjectPool<>(() -> new float[ConfigConstants.VECTOR_2D_SIZE], ConfigConstants.SMALL_OBJECT_POOL_CAPACITY);

    /** Pool for 4-element float vectors (RGBA colors, XYZW positions) */
    private static final ObjectPool<float[]> FLOAT_4_POOL =
        new ObjectPool<>(() -> new float[ConfigConstants.VECTOR_4D_SIZE], ConfigConstants.SMALL_OBJECT_POOL_CAPACITY);

    /** Pool for 16-element float matrices (4x4 transformation matrices) */
    private static final ObjectPool<float[]> MATRIX_4X4_POOL =
        new ObjectPool<>(() -> new float[ConfigConstants.MATRIX_4X4_SIZE], ConfigConstants.MATRIX_POOL_CAPACITY);

    /** Pool for 12-element float matrices (3x4 matrices) */
    private static final ObjectPool<float[]> MATRIX_3X4_POOL =
        new ObjectPool<>(() -> new float[ConfigConstants.MATRIX_3X4_SIZE], ConfigConstants.MATRIX_POOL_CAPACITY);

    /** Pool for StringBuilder instances (string concatenation) */
    private static final ObjectPool<StringBuilder> STRING_BUILDER_POOL =
        new ObjectPool<>(StringBuilder::new, ConfigConstants.STRING_BUILDER_POOL_CAPACITY);

    /**
     * Acquire a 2-element float array from shared pool.
     *
     * @return Reusable or new float[2] array
     */
    public static float[] acquireFloat2() {
        return FLOAT_2_POOL.acquire();
    }

    /**
     * Release a 2-element float array to shared pool.
     */
    public static void releaseFloat2(float[] arr) {
        if (arr != null && arr.length == 2) {
            FLOAT_2_POOL.release(arr);
        }
    }

    /**
     * Acquire a 4x4 matrix (16 floats) from shared pool.
     *
     * @return Reusable or new float[16] array
     */
    public static float[] acquireMatrix4x4() {
        return MATRIX_4X4_POOL.acquire();
    }

    /**
     * Release a 4x4 matrix to shared pool.
     */
    public static void releaseMatrix4x4(float[] arr) {
        if (arr != null && arr.length == 16) {
            MATRIX_4X4_POOL.release(arr);
        }
    }

    /**
     * Acquire a 3x4 matrix (12 floats) from shared pool.
     */
    public static float[] acquireMatrix3x4() {
        return MATRIX_3X4_POOL.acquire();
    }

    /**
     * Release a 3x4 matrix to shared pool.
     */
    public static void releaseMatrix3x4(float[] arr) {
        if (arr != null && arr.length == 12) {
            MATRIX_3X4_POOL.release(arr);
        }
    }

    /**
     * Acquire a StringBuilder from shared pool.
     */
    public static StringBuilder acquireStringBuilder() {
        return STRING_BUILDER_POOL.acquire();
    }

    /**
     * Release a StringBuilder to shared pool.
     */
    public static void releaseStringBuilder(StringBuilder sb) {
        if (sb != null) {
            sb.setLength(0); // Clear before returning
            STRING_BUILDER_POOL.release(sb);
        }
    }

    /**
     * Get combined stats for all predefined pools.
     */
    public static String getGlobalStats() {
        return String.format(
            "GlobalPools[float2=%s, mat4x4=%s, mat3x4=%s, sb=%s]",
            FLOAT_4_POOL.getStats(), MATRIX_4X4_POOL.getStats(),
            MATRIX_3X4_POOL.getStats(), STRING_BUILDER_POOL.getStats()
        );
    }
}
