package me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.data;

import org.joml.Vector3fc;

import me.jellysquid.mods.sodium.client.gl.util.VertexRange;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.TQuad;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.TranslucentSorting;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.BSPNode;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.BSPResult;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.TimingRecorder;
import me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.TimingRecorder.Counter;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import net.minecraft.util.math.ChunkSectionPos;

public class BSPDynamicData extends DynamicData {
    // /tp ~ ~-100 ~
    public static final TimingRecorder sortInitialRecorder = new TimingRecorder("BSP sort initial");
    public static final TimingRecorder sortTriggerRecorder = new TimingRecorder("BSP sort trigger");
    public static final TimingRecorder buildRecorder = new TimingRecorder("BSP build");
    public static final TimingRecorder partialUpdateRecorder = new TimingRecorder("BSP partial update", 10, true);

    private static final int NODE_REUSE_MIN_GENERATION = 1;

    private final BSPNode rootNode;
    private final int generation;

    private BSPDynamicData(ChunkSectionPos sectionPos,
            NativeBuffer buffer, VertexRange range, BSPResult result, int generation) {
        super(sectionPos, buffer, range, result);
        this.rootNode = result.getRootNode();
        this.generation = generation;
    }

    @Override
    public void sortOnTrigger(Vector3fc cameraPos) {
        var start = System.nanoTime();
        this.sort(cameraPos);
        sortTriggerRecorder.recordNow(this.getLength(), start);
    }

    private void sort(Vector3fc cameraPos) {
        this.unsetReuseUploadedData();

        this.rootNode.collectSortedQuads(getBuffer(), cameraPos);
    }

    public static BSPDynamicData fromMesh(BuiltSectionMeshParts translucentMesh,
            Vector3fc cameraPos, TQuad[] quads, ChunkSectionPos sectionPos,
            NativeBuffer buffer, TranslucentData oldData) {
        BSPNode oldRoot = null;
        int generation = 0;
        boolean prepareNodeReuse = false;
        if (oldData instanceof BSPDynamicData oldBSPData) {
            generation = oldBSPData.generation + 1;
            oldRoot = oldBSPData.rootNode;

            // only enable partial updates after a certain number of generations
            // (times the section has been built)
            prepareNodeReuse = generation >= NODE_REUSE_MIN_GENERATION;
        }

        var start = System.nanoTime();
        var result = BSPNode.buildBSP(quads, sectionPos, oldRoot, prepareNodeReuse);
        if (oldRoot == null) {
            buildRecorder.recordNow(quads.length, start);
        } else {
            partialUpdateRecorder.recordNow(quads.length, start);
        }

        VertexRange range = TranslucentData.getUnassignedVertexRange(translucentMesh);
        buffer = PresentTranslucentData.nativeBufferForQuads(buffer, quads);

        var dynamicData = new BSPDynamicData(sectionPos, buffer, range, result, generation);

        start = System.nanoTime();
        dynamicData.sort(cameraPos);
        sortInitialRecorder.recordNow(quads.length, start);

        if (TranslucentSorting.DEBUG_TRIGGER_STATS) {
            TimingRecorder.incrementBy(Counter.UNIQUE_TRIGGERS, result.getUniqueTriggers());
        }
        TimingRecorder.incrementBy(Counter.QUADS, quads.length);
        TimingRecorder.incrementBy(Counter.BSP_SECTIONS, 1);

        // prepare accumulation groups for integration into GFNI triggering
        var aligned = result.getAlignedDistances();
        if (aligned != null) {
            for (var accGroup : aligned) {
                if (accGroup != null) {
                    accGroup.prepareIntegration();
                }
            }
        }
        var unaligned = result.getUnalignedDistances();
        if (unaligned != null) {
            for (var accGroup : unaligned) {
                if (accGroup != null) {
                    accGroup.prepareIntegration();
                }
            }
        }

        return dynamicData;
    }
}
