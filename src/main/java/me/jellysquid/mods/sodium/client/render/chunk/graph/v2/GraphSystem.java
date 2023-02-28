package me.jellysquid.mods.sodium.client.render.chunk.graph.v2;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionFlags;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderData;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListBuilder;
import me.jellysquid.mods.sodium.common.util.DirectionUtil;

import java.util.Arrays;

public class GraphSystem {
    OctreeExplorer2 tree = new OctreeExplorer2(65,9);
    RenderSection[] renderSections;
    public GraphSystem() {
        tree.maxSquare = 32;
        Arrays.fill(tree.tree, (byte) -1);
        renderSections = new RenderSection[1<<(tree.layers*3)];
    }

    public void update(int x, int y, int z, ChunkRenderData data) {
        if (data == ChunkRenderData.ABSENT || data == ChunkRenderData.EMPTY) {
            tree.set(x, y, z);
        } else {
            int idx = tree.getBaseIndex(0, x, y, z);
            byte combined = (1<<6)-1;
            for (int from = 0; from < 6; from++) {
                byte msk = 0;
                for (int too = 0; too < 6; too++) {
                    if (data.getOcclusionData().isVisibleThrough(DirectionUtil.ALL_DIRECTIONS[from], DirectionUtil.ALL_DIRECTIONS[too])) {
                        msk |= 1<<too;
                    }
                }
                tree.visibilityData[idx*6+from] = msk;
                combined &= msk;
            }
            if (combined == (1<<6)-1) {
                tree.set(x,y,z);
            } else {
                tree.unset(x, y, z);
            }
        }
    }

    public void setSection(RenderSection section, int x, int y, int z) {
        renderSections[tree.getBaseIndex(0, x,y,z)] = section;
    }

    public void render(ChunkRenderListBuilder list) {
        for (int i : tree.visibleSections) {
            var section = renderSections[i];
            if (section != null && !section.isDisposed() && (section.getFlags() & RenderSectionFlags.HAS_BLOCK_GEOMETRY) != 0) {
                list.add(section);
            }
        }
    }

    public void tick(int x, int y, int z) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 20; i++)
            tree.explore(x,y,z);
        System.out.println(System.currentTimeMillis()-start);
    }

}
