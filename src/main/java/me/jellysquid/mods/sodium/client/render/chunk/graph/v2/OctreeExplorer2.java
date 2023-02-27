package me.jellysquid.mods.sodium.client.render.chunk.graph.v2;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.jellysquid.mods.sodium.common.util.DirectionUtil;

public class OctreeExplorer2 extends OctoTree {
    final short[] traversalData;
    int rx;
    int ry;
    int rz;
    public OctreeExplorer2(int width, int height) {
        super(width, height);
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new IntArrayList();
        }
        traversalData = new short[tree.length*8];
    }

    int getParentIndex(int srcLvl, int idx, int dstLvl) {
        srcLvl = layers-srcLvl;
        int msk = (1<<srcLvl)-1;
        int x = idx&msk;
        int z = (idx>>srcLvl)&msk;
        int y = (idx>>(srcLvl<<1))&msk;
        return getBaseIndex(dstLvl, x, y, z);
    }

    int findFullestUpperLevel(int level, int idx) {
        byte base = tree[idx + getOffsetForLevel(level)];
        if (base != -1) {
            return level;//base == 0?level-1:level;
        }
        for (int i = level + 1; i<layers; i++) {
            if (tree[getParentIndex(level, idx, i) + getOffsetForLevel(i)] != -1) {
                return i;
            }
        }
        return layers;
    }


    int getAdjNode(int lvl, int idx, int dir) {
        lvl = layers-lvl;
        int msk = (1<<lvl)-1;
        int x = idx&msk;
        int z = (idx>>lvl)&msk;
        int y = (idx>>(lvl<<1))&msk;
        x += dir == 4?-1:(dir==5?1:0);
        y += dir == 0?-1:(dir==1?1:0);
        z += dir == 2?-1:(dir==3?1:0);
        x &= widthMsk;
        y &= widthMsk;
        z &= widthMsk;
        return getBaseIndex(layers-lvl, x, y, z);
    }

    int computeManhattan(int lvl, int idx) {
        lvl = layers-lvl;
        int msk = (1<<lvl)-1;
        int x = idx&msk;
        int z = (idx>>lvl)&msk;
        int y = (idx>>(lvl<<1))&msk;
        //TODO: Compute the manhattan distance with respect to the origin chunk
        // taking into account wrapp around distance
        //that is, take the min manhattan distance between non wrapped and wrapped coordinate space

        x = Math.min(Math.abs(rx-x),Math.abs(rx-((1<<layers)-x)));
        y = Math.min(Math.abs(ry-y),Math.abs(ry-((1<<layers)-y)));
        z = Math.min(Math.abs(rz-z),Math.abs(rz-((1<<layers)-z)));

        return x+y+z;
    }

    boolean isRootAir(int idx) {
        return (tree[getParentIndex(0,idx, 1)+level0Offset] & (1<<getOctoIndex(0, idx))) != 0;
    }

    short getVisData(int idx, int dir) {
        return 1;
    }

    void expandInto(int lvl, int idx, int inbound, short srcDirMsk) {
        if (lvl == 0) {//It is a root node so do it normally
            short selfTraversalData = traversalData[idx + level0Offset];

            if (selfTraversalData == 0) {
                queues[computeManhattan(lvl, idx)].add(idx);
                selfTraversalData |= (short) (1 << 15) | srcDirMsk;
            }

            //int inboundDir = DirectionUtil.getOppositeId(outgoingDir);
            selfTraversalData |= isRootAir(idx)?0xFF:getVisData(idx, inbound);//this.getVisibilityData(neighborSectionIdx, inboundDir);
            selfTraversalData &= ~(1 << (8 + inbound)); // Un mark incoming direction
            traversalData[idx + level0Offset] = selfTraversalData;
        } else {
            //Its a big boi node, so tread carefully

        }
    }

    void expand(int idx) {
        short data = traversalData[idx + level0Offset];
        //Explore like the normal bfs
        data &= ((data >> 8) & 0xFF) | 0xFF00;//Upper bits represent traversable directions (directions not already traversed), lower bits represent visibilityBits (directions visible due to visibility)
        //traversalData[idx + level0Offset] = data;
        for (int dir = 0; dir < 6; dir++) {
            if ((data & (1 << dir)) == 0) {
                continue;
            }
            int adjIdx = getAdjNode(0, idx, dir);
            expandInto(0, adjIdx, DirectionUtil.getOpposite(dir), (short) (data&0xFF00));
        }
    }

    void expandLarge(int level, int idx, int manhattan) {
        short data = traversalData[idx+getOffsetForLevel(level+1)];//Note the +1 is to account for the added size of not being in bytes

    }


    IntArrayList[] queues = new IntArrayList[100];//Max manhattan distance
    void explore(int x, int y, int z) {
        rx = x;
        ry = y;
        rz = z;
        int baseLvl = findFullestUpperLevel(0, getBaseIndex(0, x, y, z));
        int baseIdx = getBaseIndex(baseLvl, x, y, z);
        traversalData[baseIdx+getOffsetForLevel(baseLvl)] = (short) (baseLvl==0?-1:0xFF);
        queues[0].add(baseIdx|(baseLvl<<29));

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
        /*
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 8; z++) {
                    e.set(x,y,z);
                }
            }
        }*/
        e.explore(0,0,0);
        //System.out.println(e.findFullestUpperLevel(0,0));
        //e.explore();
    }
}
