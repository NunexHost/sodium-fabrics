package me.jellysquid.mods.sodium.client.render.chunk.graph;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.jellysquid.mods.sodium.common.util.DirectionUtil;

public class OctreeExplorer2 extends OctoTree {
    final short[] traversalData;
    public OctreeExplorer2(int width, int height) {
        super(width, height);
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new IntArrayList();
        }
        traversalData = new short[tree.length*8];
    }

    int findFullestUpperLevel(int level, int idx) {
        for (int i = level + 1; i<layers; i++) {
            if (tree[(idx>>((i-level)*3)) + getOffsetForLevel(i)] != -1) {
                return i;
            }
        }
        return layers;
    }


    int getAdjNode(int level, int idx, int dir) {
        return idx + 0;
    }

    int computeManhattan(int lvl, int idx) {
        return 0;
    }


    void expandInto(int lvl, int idx, int inbound) {
        if (lvl == 0) {//It is a root node so do it normally
            short neighborTraversalData = traversalData[idx + level0Offset];

            if (neighborTraversalData == 0) {
                queues[computeManhattan(lvl, idx)].add((lvl<<29)|idx);
                neighborTraversalData |= (short) (1 << 15);// | (traversalData & 0xFF00);
            }

            //int inboundDir = DirectionUtil.getOppositeId(outgoingDir);
            //neighborTraversalData |= this.getVisibilityData(neighborSectionIdx, inboundDir);
            //neighborTraversalData &= ~(1 << (8 + inboundDir)); // Un mark incoming direction
            traversalData[idx + level0Offset] = neighborTraversalData;
        } else {

        }
    }

    void expand(int idx) {
        short data = traversalData[idx + level0Offset];
        //Explore like the normal bfs
        data &= ((data >> 8) & 0xFF) | 0xFF00;
        //traversalData[idx + level0Offset] = data;
        for (int dir = 0; dir < 6; dir++) {
            if ((data & (1 << dir)) == 0) {
                continue;
            }
            int adjIdx = getAdjNode(0, idx, dir);
            expandInto(0, adjIdx, dir);
        }
    }

    void expandLarge(int level, int idx, int manhattan) {
        short data = traversalData[idx+getOffsetForLevel(level+1)];//Note the +1 is to account for the added size of not being in bytes

    }


    IntArrayList[] queues = new IntArrayList[100];//Max manhattan distance
    void explore(int x, int y, int z) {
        int baseIdx = getBaseIndex(x, y, z);
        traversalData[baseIdx+level0Offset] = -1;
        queues[0].add(baseIdx);

        for (int distance = 0; distance < queues.length; distance++) {
            var queue = queues[distance];
            //NOTE: it must be done like this as nodes can be added to the current distance even while exploring
            for (int i = 0; i < queue.size(); i++) {
                int idx = queue.getInt(i);
                //NOTE: top 3 bits of idx are used to represent the level, this gives a maximum level of 8
                // or a max rd of 128
                int level = idx>>>29;
                idx &= ~(0b111<<29);
                if (level == 0) {
                    expand(idx);
                } else {
                    expandLarge(level, idx, distance);
                }
                System.out.println("D:"+distance+" L:"+level+" I:"+idx);
            }
            queue.clear();
        }
    }

    public static void main(String[] args) {
        OctreeExplorer2 e = new OctreeExplorer2(65,24);

        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 8; z++) {
                    e.set(x,y,z);
                }
            }
        }
        e.explore(0,0,0);
        //System.out.println(e.findFullestUpperLevel(0,0));
        //e.explore();
    }
}
