package com.renderium.module.impl.blaze3d.memory;

import java.lang.foreign.MemorySegment;

public class VmaMemoryPools {
    public enum PoolType { RENDER_TARGET, STAGING, UNIFORM, UNIFORM_BUFFER }

    public static class PoolAllocation {
        public final MemorySegment buffer;
        public final long size;
        public final boolean valid;

        public PoolAllocation(MemorySegment buffer, long size) {
            this.buffer = buffer;
            this.size = size;
            this.valid = true;
        }

        public PoolAllocation() {
            this.buffer = null;
            this.size = 0;
            this.valid = false;
        }

        public boolean isValid() { return valid; }
        public MemorySegment getBuffer() { return buffer; }
        public long getSize() { return size; }
    }

    private static final VmaMemoryPools INSTANCE = new VmaMemoryPools();

    public static VmaMemoryPools getInstance() { return INSTANCE; }

    public void initialize() {}

    public PoolAllocation allocateFromPool(PoolType poolType, long size) {
        return new PoolAllocation();
    }

    public void deallocate(PoolAllocation allocation) {}

    public boolean isInitialized() { return false; }
}
