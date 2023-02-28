package me.jellysquid.mods.sodium.client.render.chunk.graph.v2;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.jellysquid.mods.sodium.common.util.DirectionUtil;
import net.minecraft.util.math.Direction;

import java.util.Arrays;

//TODO: REDO THIS but with full resolution coordinates 16 bytes per queue entry
// this will simplify the maths _alot_ and should heavily accelerate the search
public class OctreeExplorer2 extends OctoTree {
    final short[] traversalData;
    int rx;
    int ry;
    int rz;
    int maxSquare;
    IntArrayList visibleSections = new IntArrayList();
    public OctreeExplorer2(int width, int height) {
        super(width, height);
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new IntArrayList();
        }
        traversalData = new short[tree.length+tree.length*8];
        visibilityData = new byte[(1<<(layers*3))*6];
    }

    int getParentIndex(int srcLvl, int idx, int dstLvl) {
        if (srcLvl == dstLvl) return idx;
        srcLvl = layers-srcLvl;
        int msk = (1<<srcLvl)-1;
        int x = idx&msk;
        int z = (idx>>srcLvl)&msk;
        int y = (idx>>(srcLvl<<1))&msk;
        srcLvl = layers-srcLvl;
        x <<= srcLvl;
        y <<= srcLvl;
        z <<= srcLvl;
        return getBaseIndex(dstLvl, x, y, z);
    }

    int getParentIdx(int lvl, int idx) {
        return getParentIndex(lvl, idx, lvl+1);
    }

    int findFullestUpperLevel(int level, int idx) {
        for (int i = Math.max(level-1, 0); i<layers-1; i++) {
            if (!nodeMarked(i+1, getParentIndex(level, idx, i+1))) {
                return i;
            }
        }
        return tree[0] == -1?layers:layers-1;
    }


    int getAdjNode(int lvl, int idx, int dir) {
        return getOffsettedNode(lvl, idx, dir == 4?-1:(dir==5?1:0), dir == 0?-1:(dir==1?1:0), dir == 2?-1:(dir==3?1:0));
    }

    int getOffsettedNode(int lvl, int idx, int dx, int dy, int dz) {
        lvl = layers-lvl;
        int msk = (1<<lvl)-1;
        int x = idx&msk;
        int z = (idx>>lvl)&msk;
        int y = (idx>>(lvl<<1))&msk;
        x += dx;
        y += dy;
        z += dz;
        lvl = layers-lvl;
        x <<= lvl;
        y <<= lvl;
        z <<= lvl;
        return getBaseIndex(lvl, x, y, z);
    }

    int getChildNode(int lvl, int idx, int octidx) {
        lvl = layers-lvl;
        int msk = (1<<lvl)-1;
        int x = idx&msk;
        int z = (idx>>lvl)&msk;
        int y = (idx>>(lvl<<1))&msk;
        x <<= 1;
        y <<= 1;
        z <<= 1;
        x += octidx&1;
        y += (octidx>>2)&1;
        z += (octidx>>1)&1;
        lvl = layers-lvl;
        x <<= lvl-1;
        y <<= lvl-1;
        z <<= lvl-1;
        return getBaseIndex(lvl - 1, x, y, z);
    }

    int innerComp(int a, int b) {
        return Math.min(Math.abs(a-b),Math.min(Math.abs(Math.abs(a-(1<<layers))+b),Math.abs(Math.abs(b-(1<<layers))+a)));
    }
    //TODO: Optimize the shit out of these
    int computeManhattan(int lvl, int idx) {
        lvl = layers-lvl;
        int msk = (1<<lvl)-1;
        int x = idx&msk;
        int z = (idx>>lvl)&msk;
        int y = (idx>>(lvl<<1))&msk;
        lvl = layers-lvl;

        x <<= lvl;
        y <<= lvl;
        z <<= lvl;

        x = Math.min(innerComp(rx, x), innerComp(rx, x|((1<<lvl)-1)));
        y = Math.min(innerComp(ry, y), innerComp(ry, y|((1<<lvl)-1)));
        z = Math.min(innerComp(rz, z), innerComp(rz, z|((1<<lvl)-1)));

        return (x+y+z);
    }

    boolean withinRDRange(int lvl, int idx) {
        lvl = layers-lvl;
        int msk = (1<<lvl)-1;
        int x = idx&msk;
        int z = (idx>>lvl)&msk;
        int y = (idx>>(lvl<<1))&msk;
        lvl = layers-lvl;

        x <<= lvl;
        y <<= lvl;
        z <<= lvl;

        x = Math.min(innerComp(rx, x), innerComp(rx, x|((1<<lvl)-1)));
        y = Math.min(innerComp(ry, y), innerComp(ry, y|((1<<lvl)-1)));
        z = Math.min(innerComp(rz, z), innerComp(rz, z|((1<<lvl)-1)));

        //TODO: FIXME: i got no idea if this is correct by any means
        //int max = Math.max(x, Math.max(y,z));
        //return max <= maxSquare;
        int dist = x*x+y*y+z*z;
        return dist<=maxSquare*maxSquare;
    }

    boolean isAir(int lvl, int idx) {
        return lvl != 0 || (tree[getParentIdx(0, idx) + level0Offset] & (1 << getOctoIndex(0, idx))) != 0;
    }

    boolean nodeMarked(int lvl, int idx) {
        return (tree[getParentIdx(lvl, idx) + getOffsetForLevel(lvl)] & (1 << getOctoIndex(lvl, idx))) != 0;
    }

    byte[] visibilityData;

    short getVisData(int idx, int dir) {
        return visibilityData[(idx * 6) + dir];
    }

    void expandInto(int lvl, int idx, int inbound, short srcDirMsk, int currentDistance) {
        /*
        if (findFullestUpperLevel(lvl, idx) != lvl) {//Validate
            System.out.println("ERROR");
        }*/
        if (!withinRDRange(lvl, idx)) return;
        if (computeManhattan(lvl, idx)<currentDistance) return;//TODO: CHECK THIS
        short selfTraversalData = traversalData[idx+getOffsetForLevel(lvl-1)];

        if (selfTraversalData == 0) {
            queues[Math.min(computeManhattan(lvl, idx), currentDistance)].add(idx|(lvl<<29));
            selfTraversalData |= (short) (1 << 15) | srcDirMsk;
        }

        //int inboundDir = DirectionUtil.getOppositeId(outgoingDir);
        selfTraversalData |= isAir(lvl, idx)?0xFF:getVisData(idx, inbound);//this.getVisibilityData(neighborSectionIdx, inboundDir);
        selfTraversalData &= ~(1 << (8 + inbound)); // Un mark incoming direction
        selfTraversalData &= srcDirMsk|0xFF;//TODO: CHECK THIS
        traversalData[idx+getOffsetForLevel(lvl-1)] = selfTraversalData;
    }

    private static final int[][] FACEOCTS= new int[6][4];

    static {
        for (int i = 0; i < 6; i++) {
            Direction dir = DirectionUtil.ALL_DIRECTIONS[i];
            int dx = dir.getOffsetX()==0?2:1;
            int dy = dir.getOffsetY()==0?2:1;
            int dz = dir.getOffsetZ()==0?2:1;
            int bx = dir.getOffsetX()>0?1:0;
            int by = dir.getOffsetY()>0?1:0;
            int bz = dir.getOffsetZ()>0?1:0;
            int j = 0;
            for (int x = bx; x < dx+bx; x++) {
                for (int y = by; y < dy+by; y++) {
                    for (int z = bz; z < dz+bz; z++) {
                        FACEOCTS[i][j++] = x|(z<<1)|(y<<2);
                        //System.out.println(dir+" " + x + ", " + y + ", " + z);
                    }
                }
            }
        }
    }

    //TODO: ALSO CHECK THAT THE currentDistance is less than the manhattan distance to idx and lvl
    void recurseExpandFace(int lvl, int idx, int face, short srcDirMsk, int currentDistance) {
        if (lvl == 0) {//At root level, enqueue
            expandInto(lvl, idx, face, srcDirMsk, currentDistance);
        } else if (nodeMarked(lvl, idx)) {//Its a full air node, enqueue
            expandInto(lvl, idx, face, srcDirMsk, currentDistance);
        } else {//Its a mix, or fully empty so must recurse
            for (int i : FACEOCTS[face]) {
                recurseExpandFace(lvl-1, getChildNode(lvl, idx, i), face, srcDirMsk, currentDistance);
            }
        }
    }

    //Expands along a face at a level, recursing down to level 0 if needed
    void expandAlongDir(int lvl, int idx, int dir, short srcDirMsk, int currentDistance) {
        int adjIdx = getAdjNode(lvl, idx, dir);
        //if (!withinRDRange(lvl, idx)) return;
        int bestLvl = findFullestUpperLevel(lvl, adjIdx);
        if (bestLvl >= lvl) {//Expanding into higher dimension or staying the same
            expandInto(bestLvl, getParentIndex(lvl, adjIdx, bestLvl), DirectionUtil.getOpposite(dir), srcDirMsk, currentDistance);
        } else {//Shrinking down lvl (cannot expand directly)
            recurseExpandFace(lvl, adjIdx, DirectionUtil.getOpposite(dir), srcDirMsk, currentDistance);
        }
    }

    void expand(int lvl, int idx, int currentDistance) {
        short data = traversalData[idx+getOffsetForLevel(lvl-1)];
        //Explore like the normal bfs
        data &= ((data >> 8) & 0xFF) | 0xFF00;//Upper bits represent traversable directions (directions not already traversed), lower bits represent visibilityBits (directions visible due to visibility)
        //traversalData[idx + level0Offset] = data;
        for (int dir = 0; dir < 6; dir++) {
            if ((data & (1 << dir)) == 0) {
                continue;
            }
            expandAlongDir(lvl, idx, dir, (short) (data&0xFF00), currentDistance);
        }
    }

    IntArrayList[] queues = new IntArrayList[100];//Max manhattan distance
    void explore(int x, int y, int z) {
        visibleSections.clear();
        Arrays.fill(traversalData, (short) 0);
        rx = x&widthMsk;
        ry = y&widthMsk;
        rz = z&widthMsk;
        int baseLvl = findFullestUpperLevel(0, getBaseIndex(0, x, y, z));
        int baseIdx = getBaseIndex(baseLvl, x, y, z);
        if (baseLvl == 0) {
            traversalData[baseIdx+levelBaseOffset] = -1;
        } else {
            traversalData[baseIdx+getOffsetForLevel(baseLvl-1)] = -1;
        }
        queues[0].add(baseIdx|(baseLvl<<29));

        int totalNodesVisited = 0;
        int totalNonLvlZeroVisited = 0;
        int solidVisited = 0;

        for (int distance = 0; distance < queues.length; distance++) {
            var queue = queues[distance];
            //NOTE: it must be done like this as nodes can be added to the current distance even while exploring
            for (int i = 0; i < queue.size(); i++) {
                int idx = queue.getInt(i);
                //NOTE: top 3 bits of idx are used to represent the level, this gives a maximum level of 8
                // or a max rd of 128
                int level = idx>>>29;
                idx &= ~(0b111<<29);

                if (!isAir(level, idx)) {
                    //System.out.println(idx);
                    solidVisited++;
                    visibleSections.add(idx);
                }
                /*
                if (findFullestUpperLevel(level, idx) != level) {//Validate
                    System.out.println("ERROR");
                }*/

                expand(level, idx, distance);
                totalNonLvlZeroVisited += level==0?0:1;
                totalNodesVisited++;//level==0?0:1;
            }
            queue.clear();
        }
        for (IntArrayList a : queues) {
            if (!a.isEmpty()) {
                throw new IllegalStateException();
            }
        }
        //System.out.println("T:"+totalNodesVisited+" To:"+totalNonLvlZeroVisited+" S:"+solidVisited);
    }

    public static void main(String[] args) {
        OctreeExplorer2 e = new OctreeExplorer2(65,9);
        Arrays.fill(e.tree, (byte) -1);

        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 24; z++) {
                    e.unset(x+1,y,z);
                }
            }
        }
        //e.unset(3,0,0);
        //System.out.println(e.findFullestUpperLevel(0, e.getBaseIndex(0, 4,0,0)));

        e.maxSquare = 32;//basicly the render distance
        e.explore(-1,0,0);
        //System.out.println(e.getChildNode(1, e.getBaseIndex(1, 2,0,0), 0b111));
        //System.out.println(e.getBaseIndex(0, 3,1,1));

        /*
        for (int i = 0; i < 100; i++)
            e.explore(0,0,0);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10000; i++)
            e.explore(0,0,0);
        long dt = System.currentTimeMillis() - start;
        System.out.println(dt);*/




        //System.out.println(e.findFullestUpperLevel(0,0));
        //e.explore();
    }
}
