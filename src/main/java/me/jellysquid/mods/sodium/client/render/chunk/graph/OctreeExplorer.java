package me.jellysquid.mods.sodium.client.render.chunk.graph;

import it.unimi.dsi.fastutil.ints.IntArrayList;

public class OctreeExplorer extends OctoTree {
    short[] traversalData;
    public OctreeExplorer(int width, int height) {
        super(width, height);
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new IntArrayList();
        }
        traversalData = new short[tree.length*8];
    }

    //Returns if a point is in a range (even if partially) at a given level
    private boolean pointInRange(int level, int point, int min, int max) {
        //Check if its within range of the entire octree
        if (point<0 || (1<<((layers-level)*3))<point) return false;
        min >>= level;
        max >>= level;
        if (max < min) {
            return !(max < point && point < min);
        }
        return min <= point && point <= max;
    }

    //Assumes that explorable areas are marked (that is, air nodes are marked while non air nodes arnt)
    //NOTE: min>max, this is to support wraparounds at the edge of pow2 aligned sections
    //NOTE: level can be 1 greater than the number of layers, this is done to support wrapping
    // mins and maxs are in root level space
    //TODO: CAN TECHNICALLY FIT THE BOUNDS INTO 3 parts, definatly in maybe in definatly out by using index ranges
    // can then do more expensive computation do double check
    /*
    void explore(int level, int localIdx, int x, int y, int z, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        if (level == 0) {
            System.out.println(x+", "+y+", "+z);
            return;//TODO: special case this
        }
        int base = getOffsetForLevel(level-1);
        for (int i = 0; i < 8; i++) {
            int nx = (x<<1)|(i>>0 & 1);
            int ny = (y<<1)|(i>>1 & 1);
            int nz = (z<<1)|(i>>2 & 1);
            int nextIdx = localIdx<<3|i;
            if (nodeFull(nextIdx + base, level-1, nx, ny, nz)) {
                continue;
            }
            if (nodeEmpty(nextIdx + base, level-1, nx, ny, nz)) {
                continue;
            }
            if (pointInRange(level - 1, nx, minX, maxX) &&
                pointInRange(level - 1, ny, minY, maxY) &&
                pointInRange(level - 1, nz, minZ, maxZ)) {
                explore(level - 1, nextIdx, nx, ny, nz, minX, maxX, minY, maxY, minZ, maxZ);
            }
        }
    }

    public static void main(String[] args) {
        OctreeExplorer e = new OctreeExplorer(8,8);
        e.explore(3, 0,0,0,0,7,0,7,0,7,0);
    }*/

    int findFullestUpperLevel(int level, int idx) {
        for (int i = level; i<layers; i++) {
            if (tree[(idx>>((i-level)*3)) + getOffsetForLevel(i)] != -1) {
                return i;
            }
        }
        return layers;
    }

    int getAdjNode(int level, int idx, int dir) {
        return 0;
    }

    //idx is a root level index, checks if the bit is set
    boolean isMarked(int idx) {
        return (tree[idx>>3 + getOffsetForLevel(0)]&(1<<(idx&3))) == 0;
    }

    //NOTE: there are 2 different handles, one if lvl is 0 and we are at the leafs
    // another is when lvl > 0
    //when lvl == 0, the data in traversalData contains possible outbound directions and explored inbound directions
    // when lvl > 0 the traversalData contains explored directions and inbound directions (since lvls>0 can be explored multiple times as long as its for different resulting explored directions)
    void possiblyEnqueueNode(int lvl, int idx, int inboundDir) {

    }

    //This recursively marks nodes on a specific face, does some shortcuts by testing emptyness
    //TODO: must take into account current manhattin distance for a specific node
    void enqueueNodesOnFace(int level, int idx, int face) {
        if (level == 0) {//At root, must enqueue
            //TODO: THIS
            return;
        }
        for (int i = 0; i < 4; i++) {//The 4 nodes of the face for idx
            int nidx = 0;
            byte node = tree[nidx];
            if (node == -1) {//Full of air
                continue;
            }
            if (node == 0) {//Full of blocking chunks
                //TODO: batch enqueue
            } else {//mixed, must recurse
                enqueueNodesOnFace(level-1, nidx, face);
            }
        }
    }

    void enqueueNodesOnFace(int idx, int face) {
        enqueueNodesOnFace(0, idx, face);
    }

    void expandAlongFace(int lvl, int sourceIdx, int face) {
        int adjIdx = getAdjNode(lvl, sourceIdx, face);

        int lvl2 = findFullestUpperLevel(lvl, adjIdx);
        if (lvl == lvl2) {//We are on the same level node so can just enqueue NOTE: THIS ISNT RIGHT as it could be empty or other

        } else {//Node is bigger

        }
    }

    //If expanding onto an already explored node (that hasnt been explored in the specific direction),
    // just enqueue it at the current manhattan distance, there shouldent however
    // ever be a cascade effect, that node shouldent be able to enqueue other nodes for the current distance afaik

    //NOTE: when exploring, can actually test if a nodes max manhatten distance is more (or less) than the currently
    // explored distance, this means we can do some nice illegal things to accelerate searching

    void expand(int level, int idx, int manhattan) {
        if (level == 0) {
             if (isMarked(idx)) {
                 //TODO: explore a normal section
             } else {
                 //Is a single sized air chunk, enumerate through it manually
             }
        } else {//Its a big air node, enumate through it respectfully

        }
    }

    IntArrayList[] queues = new IntArrayList[100];//Max manhattan distance
    void explore(int x, int y, int z) {
        int baseIdx = getBaseIndex(x,y,z);
        int baseLvl = findFullestUpperLevel(0, baseIdx);
        queues[0].add(baseLvl<<29|(baseIdx>>(baseLvl*3)));

        for (int distance = 0; distance < queues.length; distance++) {
            var queue = queues[distance];
            //NOTE: it must be done like this as nodes can be added to the current distance even while exploring
            for (int i = 0; i < queue.size(); i++) {
                int idx = queue.getInt(i);
                //NOTE: top 3 bits of idx are used to represent the level, this gives a maximum level of 8
                // or a max rd of 128
                expand(idx>>>29, idx&(~(0b111<<29)), distance);
            }
            queue.clear();
        }
    }

    public static void main(String[] args) {
        OctreeExplorer e = new OctreeExplorer(65,24);

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
