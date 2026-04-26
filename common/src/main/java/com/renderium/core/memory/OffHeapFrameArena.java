package com.renderium.core.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.foreign.SegmentAllocator;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OffHeapFrameArena implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(OffHeapFrameArena.class.getName());

    public static final int DEFAULT_MAX_FRAMES = 3;
    public static final int FRAME_HEADER_SIZE = 64;
    public static final int BYTES_PER_PIXEL = 4;
    public static final int ALIGNMENT = 8;

    private final Arena arena;
    private final SegmentAllocator allocator;
    private final FrameRegion[] regions;
    private final int maxFrames;
    private final int singleFrameSize;
    private final int frameWidth;
    private final int frameHeight;
    private volatile int currentWriteIndex;

    public OffHeapFrameArena(int width, int height) {
        this(width, height, DEFAULT_MAX_FRAMES);
    }

    public OffHeapFrameArena(int width, int height, int maxFrames) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive: " + width + "x" + height);
        }
        if (maxFrames < 1 || maxFrames > 16) {
            throw new IllegalArgumentException("maxFrames must be in [1,16]: " + maxFrames);
        }

        this.frameWidth = width;
        this.frameHeight = height;
        this.maxFrames = maxFrames;

        int pixelDataSize = width * height * BYTES_PER_PIXEL;
        this.singleFrameSize = alignUp(FRAME_HEADER_SIZE + pixelDataSize, ALIGNMENT);

        long totalSize = (long) singleFrameSize * maxFrames;
        this.arena = Arena.ofConfined();
        this.allocator = SegmentAllocator.prefixAllocator(arena.allocate(totalSize));

        this.regions = new FrameRegion[maxFrames];
        long offset = 0;
        for (int i = 0; i < maxFrames; i++) {
            regions[i] = new FrameRegion(i, allocator.allocate(singleFrameSize), singleFrameSize);
            offset += singleFrameSize;
        }
        this.currentWriteIndex = 0;

        LOGGER.info(String.format(
            "OffHeapFrameArena initialized: %dx%d, maxFrames=%d, frameSize=%d, total=%.2fMB (native/off-heap)",
            width, height, maxFrames, singleFrameSize, totalSize / (1024.0 * 1024.0)
        ));
    }

    public void beginFrame(int frameNumber) {
        currentWriteIndex = frameNumber % maxFrames;
        FrameRegion region = regions[currentWriteIndex];
        region.beginFrame(frameNumber);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format(
                "beginFrame: frameNumber=%d, regionIndex=%d",
                frameNumber, currentWriteIndex
            ));
        }
    }

    public FrameRegion getCurrentRegion() {
        return regions[currentWriteIndex];
    }

    public FrameRegion getRegion(int index) {
        if (index < 0 || index >= maxFrames) {
            throw new IndexOutOfBoundsException("Region index out of range: " + index);
        }
        return regions[index];
    }

    public int getCurrentWriteIndex() { return currentWriteIndex; }
    public int getMaxFrames() { return maxFrames; }
    public int getSingleFrameSize() { return singleFrameSize; }
    public int getFrameWidth() { return frameWidth; }
    public int getFrameHeight() { return frameHeight; }
    public long getTotalNativeBytes() { return (long) singleFrameSize * maxFrames; }
    public boolean isAlive() { return arena.scope().isAlive(); }

    public String getStatusReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== OffHeapFrameArena Status ===\n");
        sb.append(String.format("Resolution: %dx%d\n", frameWidth, frameHeight));
        sb.append(String.format("Mode: NATIVE OFF-HEAP (FFM Arena)\n"));
        sb.append(String.format("Max frames: %d\n", maxFrames));
        sb.append(String.format("Frame size: %d bytes (%.2f KB)\n",
            singleFrameSize, singleFrameSize / 1024.0));
        sb.append(String.format("Total native: %.2f MB\n",
            getTotalNativeBytes() / (1024.0 * 1024.0)));
        sb.append(String.format("Current write index: %d\n", currentWriteIndex));
        sb.append(String.format("Arena alive: %b\n", isAlive()));
        sb.append("\n[Regions]\n");
        for (int i = 0; i < maxFrames; i++) {
            FrameRegion r = regions[i];
            sb.append(String.format("  [%d] frameNum=%d active=%s header={timestamp=%d qualityScore=%.4f}\n",
                i, r.frameNumber, r.active,
                r.getTimestamp(), r.getQualityScore()
            ));
        }
        sb.append("==============================\n");
        return sb.toString();
    }

    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
            LOGGER.info("OffHeapFrameArena closed: native memory released");
        }
    }

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    public static final class FrameRegion {

        private static final long OFFSET_TIMESTAMP = 0L;
        private static final long OFFSET_QUALITY_SCORE = 8L;
        private static final long OFFSET_FLAGS = 12L;
        private static final long OFFSET_PAYLOAD = FRAME_HEADER_SIZE;

        final int regionIndex;
        final MemorySegment segment;
        final int regionSize;
        final int payloadCapacity;
        int frameNumber;
        boolean active;
        long bumpOffset;

        FrameRegion(int regionIndex, MemorySegment segment, int regionSize) {
            this.regionIndex = regionIndex;
            this.segment = segment;
            this.regionSize = regionSize;
            this.payloadCapacity = regionSize - FRAME_HEADER_SIZE;
            this.frameNumber = -1;
            this.active = false;
            this.bumpOffset = OFFSET_PAYLOAD;
        }

        void beginFrame(int frameNumber) {
            this.frameNumber = frameNumber;
            this.active = true;
            this.bumpOffset = OFFSET_PAYLOAD;
            segment.set(ValueLayout.JAVA_LONG_UNALIGNED, OFFSET_TIMESTAMP, 0L);
            segment.set(ValueLayout.JAVA_FLOAT_UNALIGNED, OFFSET_QUALITY_SCORE, 0.0f);
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_FLAGS, 0);
        }

        void endFrame() {
            this.active = false;
        }

        public MemorySegment segment() { return segment; }

        public MemorySegment headerSlice() {
            return segment.asSlice(0, FRAME_HEADER_SIZE);
        }

        public MemorySegment payloadSlice() {
            return segment.asSlice(OFFSET_PAYLOAD, payloadCapacity);
        }

        public long allocateFromPayload(long size, long alignment) {
            long alignedBump = alignUp(bumpOffset, alignment);
            long newOffset = alignedBump + size;
            if (newOffset > OFFSET_PAYLOAD + payloadCapacity) {
                throw new OutOfMemoryError(String.format(
                    "FrameRegion[%d] exhausted: request=%d+%d, available=%d",
                    regionIndex, alignedBump - OFFSET_PAYLOAD, size, payloadCapacity
                ));
            }
            bumpOffset = newOffset;
            return alignedBump;
        }

        public long getPayloadUsed() { return bumpOffset - OFFSET_PAYLOAD; }
        public int getRegionIndex() { return regionIndex; }
        public int getFrameNumber() { return frameNumber; }
        public boolean isActive() { return active; }
        public int getPayloadCapacity() { return payloadCapacity; }

        public void setTimestamp(long nanos) {
            checkActive();
            segment.set(ValueLayout.JAVA_LONG_UNALIGNED, OFFSET_TIMESTAMP, nanos);
        }

        public long getTimestamp() {
            return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, OFFSET_TIMESTAMP);
        }

        public void setQualityScore(float score) {
            checkActive();
            segment.set(ValueLayout.JAVA_FLOAT_UNALIGNED, OFFSET_QUALITY_SCORE, score);
        }

        public float getQualityScore() {
            return segment.get(ValueLayout.JAVA_FLOAT_UNALIGNED, OFFSET_QUALITY_SCORE);
        }

        public void setFlags(int flags) {
            checkActive();
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_FLAGS, flags);
        }

        public int getFlags() {
            return segment.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_FLAGS);
        }

        public MemorySegment pixelSlice(int width, int height) {
            long pixelBytes = (long) width * height * BYTES_PER_PIXEL;
            if (pixelBytes > payloadCapacity) {
                throw new IllegalArgumentException(
                    String.format("Pixel slice %dx%d (%d bytes) exceeds payload capacity %d",
                        width, height, pixelBytes, payloadCapacity)
                );
            }
            return segment.asSlice(OFFSET_PAYLOAD, pixelBytes);
        }

        public int[] readPixelData(int width, int height) {
            MemorySegment pixels = pixelSlice(width, height);
            int count = width * height;
            int[] result = new int[count];
            MemorySegment roi = pixels.asSlice(0, (long) count * Integer.BYTES);
            for (int i = 0; i < count; i++) {
                result[i] = roi.get(ValueLayout.JAVA_INT_UNALIGNED, (long) i * Integer.BYTES);
            }
            return result;
        }

        public void writePixelData(int[] rgbaPixels, int width, int height) {
            MemorySegment pixels = pixelSlice(width, height);
            int count = Math.min(rgbaPixels.length, width * height);
            for (int i = 0; i < count; i++) {
                pixels.set(ValueLayout.JAVA_INT_UNALIGNED, (long) i * Integer.BYTES, rgbaPixels[i]);
            }
        }

        private void checkActive() {
            if (!active) {
                throw new IllegalStateException(
                    String.format("FrameRegion[%d] is not active (frameNumber=%d). Call beginFrame() first.",
                        regionIndex, frameNumber)
                );
            }
        }

        private static long alignUp(long value, long alignment) {
            return (value + alignment - 1L) & ~(alignment - 1L);
        }
    }
}
