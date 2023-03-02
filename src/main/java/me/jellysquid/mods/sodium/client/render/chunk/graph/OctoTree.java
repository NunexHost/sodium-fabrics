package me.jellysquid.mods.sodium.client.render.chunk.graph;


//NOTE: NEEDS TO SUPPORT ROLLOVER/WRAPAROUND
public class OctoTree {
    //The way it works, each node is a single byte, each bit marking if the respective node is full or empty
    //The indexing of the tree is critical
    // it has the property such that when you divide the node index (relativly for that level) by 8 you get the relative
    // parent node index
    protected final byte[] tree;//contains the entire octree
    protected final int layers;
    protected final int level0Offset;
    public OctoTree(int width, int height) {
        layers = (int) Math.ceil(Math.max(Math.log(width)/Math.log(2), Math.log(height)/Math.log(2)));
        tree = new byte[(((1<<(layers)*3)-1)/7)];//(8^(levels)-1)/7
        level0Offset = getOffsetForLevel(0);
    }

    //Level 0 is the start of the leaf nodes, layers - 1 is the root node
    protected int getOffsetForLevel(int level) {//It might be faster to make this a const final array
        return getOffsetForLevelUndivided(level)/7;
    }

    private int getOffsetForLevelUndivided(int level) {
        return ((1<<(layers-level-1)*3)-1);
    }
    /*
    protected boolean nodeEmpty(int localIdx, int level, int x, int y, int z) {
        int max = 1<< (layers-level-1);
        if (max <= x) return false;
        if (max <= y) return false;
        if (max <= z) return false;
        return tree[localIdx] == 0;
    }
    protected boolean nodeFull(int localIdx, int level, int x, int y, int z) {
        int max = 1<< (layers-level-1);
        if (max <= x) return false;
        if (max <= y) return false;
        if (max <= z) return false;
        return tree[localIdx] == (byte) 0xff;
    }*/

    protected int getBaseIndex(int leafX, int leafY, int leafZ) {
        int index = 0;
        for (int i = layers-1; -1 < i; i--) {
            int localIdx = 0;
            localIdx |= ((leafX>>i)&1)<<0;
            localIdx |= ((leafY>>i)&1)<<1;
            localIdx |= ((leafZ>>i)&1)<<2;
            index = index<<3|localIdx;
        }
        return index;
    }

    public void set(int x, int y, int z) {
        int idx = getBaseIndex(x,y,z);
        for (int i = 0; i < layers; i++) {
            int parentIndex = idx/8;//FIXME: this can be optimized alot i believe
            byte parent = tree[parentIndex+getOffsetForLevel(i)] |= 1<<(idx&0b111);
            if (parent != (byte) 0xFF) {
                break;
            }
            idx = parentIndex;
        }
    }

    public void unset(int x, int y, int z) {
        int idx = getBaseIndex(x,y,z);
        for (int i = 0; i < layers; i++) {
            int parentIndex = idx/8;//FIXME: this can be optimized alot i believe
            byte parent = tree[parentIndex+getOffsetForLevel(i)];
            boolean shouldContinue = parent == (byte) 0xFF;
            parent &= ~(1<<(idx&0b111));
            tree[parentIndex+getOffsetForLevel(i)] = parent;
            if (!shouldContinue) {
                break;
            }
            idx = parentIndex;
        }
    }



    public static void main(String[] args) {
        OctoTree e = new OctoTree(8, 8);

        for (int z = 0; z < 8; z++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    e.set(x,y,z);
                }
            }
        }
        // System.out.println(e);
    }
}
