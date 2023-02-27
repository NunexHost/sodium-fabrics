package me.jellysquid.mods.sodium.client.render.chunk.graph.v2;

//Changed the type of storage indexing to be simple non interpolating
public class OctoTree {
    protected final byte[] tree;//contains the entire octree
    protected final int layers;
    protected final int level0Offset;
    protected final int widthMsk;

    public OctoTree(int width, int height) {
        int widthBits = (int)Math.ceil(Math.log(width)/Math.log(2));
        int heightBits = (int)Math.ceil(Math.log(height)/Math.log(2));
        layers = Math.max(widthBits, heightBits);
        tree = new byte[(((1<<(layers)*3)-1)/7)];//(8^(levels)-1)/7
        level0Offset = getOffsetForLevel(0);
        widthMsk = (1<<layers)-1;
    }

    //Level 0 is the start of the leaf nodes, layers - 1 is the root node
    protected int getOffsetForLevel(int level) {//It might be faster to make this a const final array
        return getOffsetForLevelUndivided(level)/7;
    }

    private int getOffsetForLevelUndivided(int level) {
        return ((1 << (layers - level - 1) * 3) - 1);
    }

    protected int getBaseIndex(int level, int leafX, int leafY, int leafZ) {
        int level2 = layers-level;
        int msk = (1<<level2)-1;
        return ((leafX>>level)&msk)|(((leafZ>>level)&msk)<<level2)|(((leafY>>level)&msk)<<(level2<<1));
    }

    protected int getOctoIndex(int lvl, int idx) {
        lvl = layers-lvl;
        return (idx&1)|(((idx>>lvl)&1)<<1)|(((idx>>(lvl<<1))&1)<<2);
    }

    public void set(int x, int y, int z) {
        int idx = getBaseIndex(0, x, y, z);
        for (int i = 0; i < layers; i++) {
            int parentIndex = getBaseIndex(i+1,x,y,z);
            byte parent = tree[parentIndex+getOffsetForLevel(i)] |= 1<<getOctoIndex(i, idx);
            if (parent != (byte) 0xFF) {
                break;
            }
            idx = parentIndex;
        }
    }

    public void unset(int x, int y, int z) {
        int idx = getBaseIndex(0, x, y, z);
        for (int i = 0; i < layers; i++) {
            int parentIndex = getBaseIndex(i+1,x,y,z);
            byte parent = tree[parentIndex+getOffsetForLevel(i)];
            boolean shouldContinue = parent == (byte) 0xFF;
            parent &= ~(1<<getOctoIndex(i, idx));
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
        System.out.println(e);
    }
}
