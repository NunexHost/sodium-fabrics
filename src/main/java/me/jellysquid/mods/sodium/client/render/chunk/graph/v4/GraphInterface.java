package me.jellysquid.mods.sodium.client.render.chunk.graph.v4;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionFlags;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderData;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListBuilder;
import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import me.jellysquid.mods.sodium.common.util.DirectionUtil;
import net.minecraft.util.math.ChunkSectionPos;

public class GraphInterface {
    final GraphExplorer explorer;
    final int minHeight;
    final RenderSectionManager rsm;
    public GraphInterface(RenderSectionManager rsm, int rd, int minHeight, int maxHeight) {
        this.minHeight = minHeight;
        this.rsm = rsm;
        explorer = new GraphExplorer(rd, maxHeight - minHeight);
    }

    final Long2ObjectOpenHashMap<RenderSection> sections = new Long2ObjectOpenHashMap<>();
    ChunkRenderListBuilder list;
    private void addVisibleNode(RenderSection node) {
        int flags = node.getFlags();

        if ((flags & RenderSectionFlags.HAS_BLOCK_GEOMETRY) != 0) {
            list.add(node);
            rsm.addChunkToVisible(list, node);
        }

        if ((flags & RenderSectionFlags.HAS_BLOCK_ENTITIES) != 0) {
            rsm.addEntitiesToRenderLists(node);
        }
    }

    private void onNodeExplored(int x, int y, int z) {
        RenderSection section = sections.getOrDefault(ChunkSectionPos.asLong(x,y,z), null);
        if (section != null) {
            rsm.schedulePendingUpdates(section);
            addVisibleNode(section);
        }
    }

    private long t = 0;
    private int i = 0;
    public void render(Frustum frustum, int x, int y, int z, ChunkRenderListBuilder list) {
        this.list = list;
        explorer.frustum = frustum;
        long t1 = System.nanoTime();
        explorer.explore(x,y-minHeight,z, this::onNodeExplored);
        t += System.nanoTime() - t1;
        if (i++ == 100) {
            System.out.println(t/i);
            t = 0;
            i = 0;
        }
        //long t = System.currentTimeMillis();
        //for (int i = 0; i < 20; i++) {
        //    explorer.explore(x,y-minHeight,z);
        //}
        //System.out.println(System.currentTimeMillis()-t);
    }

    public void set(RenderSection section, ChunkRenderData data) {
        int x = section.getChunkX();
        int y = section.getChunkY() - minHeight;
        int z = section.getChunkZ();
        sections.put(ChunkSectionPos.asLong(x,y,z), section);
        if (data == ChunkRenderData.ABSENT)  {
            for (int i = 0; i < 6; i++) {
                explorer.setVisibilityData(x,y,z,i, (byte) 0);
            }
            explorer.unsetAir(x,y,z);
            return;
        }
        if (data == ChunkRenderData.EMPTY) {
            for (int i = 0; i < 6; i++) {
                explorer.setVisibilityData(x,y,z,i, (byte) 0);
            }
            explorer.setAir(x, y, z);
        } else {
            byte combined = (1<<6)-1;
            for (int from = 0; from < 6; from++) {
                byte msk = 0;
                for (int too = 0; too < 6; too++) {
                    if (data.getOcclusionData().isVisibleThrough(DirectionUtil.ALL_DIRECTIONS[from], DirectionUtil.ALL_DIRECTIONS[too])) {
                        msk |= 1<<too;
                    }
                }
                explorer.setVisibilityData(x,y,z,from,msk);
                combined &= msk;
            }

            if (combined == (1<<6)-1) {
                explorer.setAir(x,y,z);
            } else {
                explorer.unsetAir(x, y, z);
            }
            //explorer.unsetAir(x,y,z);
        }
    }

    public void delete(RenderSection section) {
        int x = section.getChunkX();
        int y = section.getChunkY() - minHeight;
        int z = section.getChunkZ();
        explorer.setAir(x, y, z);
        for (int i = 0; i < 6; i++) {
            explorer.setVisibilityData(x,y,z,i, (byte) 0);
        }
        sections.remove(ChunkSectionPos.asLong(x,y,z));
    }
}
