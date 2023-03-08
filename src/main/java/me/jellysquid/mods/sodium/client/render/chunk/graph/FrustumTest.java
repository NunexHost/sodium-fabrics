package me.jellysquid.mods.sodium.client.render.chunk.graph;

import java.util.*;

import me.jellysquid.mods.sodium.client.util.frustum.Frustum;

public abstract class FrustumTest {
    protected Frustum frustum;

    public abstract boolean isFrustumCulled(
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ);

    public boolean isFrustumCulled(int chunkX, int chunkY, int chunkZ) {
        float posX = (chunkX << 4);
        float posY = (chunkY << 4);
        float posZ = (chunkZ << 4);

        return isFrustumCulled(posX, posY, posZ, posX + 16.0f, posY + 16.0f, posZ + 16.0f);
    }

    public void initWithFrustum(Frustum frustum) {
        this.frustum = frustum;
    }

    public void collectDebugStrings(List<String> list) {
    }
}
