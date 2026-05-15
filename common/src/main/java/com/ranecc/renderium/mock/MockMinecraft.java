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
    }

    public static final class MockFrustum {
        public boolean isVisible(double x, double y, double z) { return true; }
        public boolean isVisible(Vec3 position) { return true; }
    }

    public static final class MockChunkSection {
        private final int x, y, z;
        public MockChunkSection(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
    }

    public static final class MockSectionRenderDispatcher {
        public List<MockChunkSection> getVisibleSections(MockFrustum frustum) {
            return List.of();
        }
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

    @Override
    public void close() {}
}
