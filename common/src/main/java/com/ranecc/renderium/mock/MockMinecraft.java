package com.ranecc.renderium.mock;

import java.util.List;

public class MockMinecraft implements AutoCloseable {

    public static final class Vec3 {
        public final double x, y, z;
        public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

        public double length() { return Math.sqrt(x * x + y * y + z * z); }
        public Vec3 normalize() {
            double len = length();
            return len < 1e-10 ? new Vec3(0, 0, 1) : new Vec3(x / len, y / len, z / len);
        }
    }

    public static final class MockCamera {
        public Vec3 getPosition() { return new Vec3(0, 0, 0); }
        public Vec3 getDirection() { return new Vec3(0, 0, -1); }
        public float[] getViewMatrix() { return new float[16]; }
        public float[] getProjectionMatrix() { return new float[16]; }
    }

    public static final class MockFrustum {
        public boolean isVisible(double x, double y, double z) { return true; }
        public boolean isVisible(Vec3 position) { return true; }
        public float[] getPlanes() { return new float[24]; }
    }

    public static final class MockChunkSection {
        private final int x, y, z;
        public MockChunkSection(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public float[] toSectionData(int index) { return new float[]{index, 0.0f, 13.85640646f}; }
        public float[] toPositionData() { return new float[]{x * 16.0f, y * 16.0f, z * 16.0f}; }
    }

    public static final class MockSectionRenderDispatcher {
        public List<MockChunkSection> getVisibleSections(MockFrustum frustum) {
            return List.of();
        }
        public List<MockChunkSection> getVisibleSections() {
            return List.of();
        }
    }

    public static final class MockLevelRenderer {
        public int getCurrentFov() { return 90; }
    }

    public static final class MockClientLevel {
        public int getMinBuildHeight() { return -64; }
        public int getMaxBuildHeight() { return 320; }
    }

    public static final class MockRenderSystem {
        public int getCurrentFBOId() { return 0; }
        public int[] getResolution() { return new int[]{1920, 1080}; }
        public long getFrameIndex() { return 0L; }
        public void advanceFrame() {}
    }

    public static final class GameRenderer {
        public int getCurrentFov() { return 90; }
    }

    private static final MockMinecraft INSTANCE = new MockMinecraft();

    private MockMinecraft() {}

    public static MockMinecraft createDefaultScene() {
        return new MockMinecraft();
    }

    public static MockMinecraft getMinecraftInstance() {
        return INSTANCE;
    }

    public MockCamera getCamera() { return new MockCamera(); }
    public MockFrustum getFrustum() { return new MockFrustum(); }
    public MockSectionRenderDispatcher getSectionRenderDispatcher() { return new MockSectionRenderDispatcher(); }
    public GameRenderer getGameRenderer() { return new GameRenderer(); }

    public MockCamera getMockCamera() { return new MockCamera(); }
    public MockFrustum getMockFrustum() { return new MockFrustum(); }
    public MockLevelRenderer getMockLevelRenderer() { return new MockLevelRenderer(); }
    public MockClientLevel getMockClientLevel() { return new MockClientLevel(); }
    public MockSectionRenderDispatcher getMockSectionDispatcher() { return new MockSectionRenderDispatcher(); }
    public MockRenderSystem getMockRenderSystem() { return new MockRenderSystem(); }

    @Override
    public void close() {}
}
