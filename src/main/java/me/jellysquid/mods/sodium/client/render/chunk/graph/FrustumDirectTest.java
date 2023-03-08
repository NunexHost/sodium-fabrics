package me.jellysquid.mods.sodium.client.render.chunk.graph;

public class FrustumDirectTest extends FrustumTest {
    @Override
    public boolean isFrustumCulled(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return !this.frustum.isBoxVisible(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
