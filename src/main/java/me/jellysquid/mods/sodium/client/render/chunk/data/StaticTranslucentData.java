package me.jellysquid.mods.sodium.client.render.chunk.data;

import java.util.EnumMap;
import java.util.Map;

import org.joml.Vector3f;

import com.mojang.blaze3d.systems.VertexSorter;

import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.util.sorting.VertexSorters;

public class StaticTranslucentData implements TranslucentData {
    private static final Map<ModelQuadFacing, VertexSorter> SORTERS = new EnumMap<>(ModelQuadFacing.class);

    static {
        for (ModelQuadFacing facing : ModelQuadFacing.DIRECTIONS) {
            SORTERS.put(facing, VertexSorters.sortByAxis(facing));
        }
    }

    private final Map<ModelQuadFacing, IndexedPrimitives> data = new EnumMap<>(ModelQuadFacing.class);

    public StaticTranslucentData(ChunkVertexType vertexType, ChunkMeshData meshData) {
        var buffer = meshData.getVertexData().getDirectBuffer();
        int primitiveCount = meshData.getVertexCount() / 4; // TODO: is it quads or triangles?

        for (ModelQuadFacing facing : ModelQuadFacing.DIRECTIONS) {
            var vertexRange = meshData.getPart(facing);
            if (vertexRange != null) {
                this.data.put(facing, new IndexedPrimitives(vertexType, buffer, vertexRange, primitiveCount));
            }
        }
    }

    @Override
    public Vector3f[] getCenters(ModelQuadFacing facing) {
        if (facing == ModelQuadFacing.UNASSIGNED) {
            throw new UnsupportedOperationException("Cannot get centers for UNASSIGNED facing");
        }
        return this.data.get(facing).centers;
    }

    @Override
    public int[] getIndexes(ModelQuadFacing facing) {
        if (facing == ModelQuadFacing.UNASSIGNED) {
            throw new UnsupportedOperationException("Cannot get indexes for UNASSIGNED facing");
        }
        return this.data.get(facing).indexes;
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public void sort(Vector3f cameraPos) {
        for (ModelQuadFacing facing : ModelQuadFacing.DIRECTIONS) {
            var dataForFacing = this.data.get(facing);
            if (dataForFacing != null) {
                dataForFacing.indexes = SORTERS.get(facing).sort(dataForFacing.centers);
            }
        }
    }
}
