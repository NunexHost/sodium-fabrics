package me.jellysquid.mods.sodium.client.render.chunk.graph.v4;

public final class Octreebkup {
    final byte[] tree;
    private final int layers;
    public Octreebkup(int width, int height) {
        layers = (int) Math.ceil(Math.max(Math.log(width)/Math.log(2), Math.log(height)/Math.log(2)));
        tree = new byte[(((1<<(layers)*3)-1)/7)];//(8^(levels)-1)/7

        //Arrays.fill(tree, (byte) -1);
    }

    //Assume x,y,z are all preshifted
    private int getNodeIndex(int level, int x, int y, int z) {
        //Do morton number
        int index = 0;
        for (int i = 0; i < layers-level; i++) {
            int local = (x>>i)&1;
            local |= ((y>>i)&1)<<2;
            local |= ((z>>i)&1)<<1;
            index |= local<<(i*3);//Can replace i*3 with another count up by 3 counter
        }
        return index;
    }


    //TODO: OPTIMIZE THIS
    //Level 0 is the start of the leaf nodes, layers - 1 is the root node
    // Level -1 is the "offset" to leaf nodes
    protected int getOffsetForLevel(int level) {//It might be faster to make this a const final array
        return getOffsetForLevelUndivided(level)/7;
    }

    private int getOffsetForLevelUndivided(int level) {
        return ((1<<(layers-level-1)*3)-1);
    }


    public void set(int x, int y, int z) {
        int idx = getNodeIndex(0, x,y,z);
        for (int i = 0; i < layers; i++) {
            int parentIndex = idx>>3;//FIXME: this can be optimized alot i believe, that is, just need to add undivided thing
            byte parent = tree[parentIndex+getOffsetForLevel(i)] |= 1<<(idx&0b111);
            if (parent != (byte) 0xFF) {
                break;
            }
            idx = parentIndex;
        }
    }

    public void unset(int x, int y, int z) {
        int idx = getNodeIndex(0,x,y,z);
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

    /*
    //TODO:FIXME: CHECK THIS FULLY WORKS
    public int findHighestFilledLevel(int start, int x, int y, int z) {
        if (findHighestFilledLevelA(start, x, y, z) != findHighestFilledLevelB(start, x, y, z)) {
            System.out.println("EEEE");
            findHighestFilledLevelB(start, x, y, z);
            findHighestFilledLevelA(start, x, y, z);
        }
        return findHighestFilledLevelB(start, x, y, z);
    }*/
    public int findHighestFilledLevel(int start, int x, int y, int z) {
        int lvl = Math.max(start-1, 0);
        int idx = getNodeIndex(lvl+1, x>>(lvl+1-start),y>>(lvl+1-start),z>>(lvl+1-start));
        for (int i = lvl; i<layers-1; i++) {
            int nlvl = i+1;
            if ((tree[(idx>>3) + getOffsetForLevel(nlvl)] & (1<<((idx)&0b111))) == 0) {
                return i;
            }
            idx >>= 3;
        }
        return tree[0] == -1?layers:layers-1;
    }
    /*
    public int findHighestFilledLevelB(int start, int x, int y, int z) {
        int lvl = Math.max(start-1, 0);
        for (int i = lvl; i<layers-1; i++) {
            int nlvl = i+1;
            if (!isNodeSet(i+1, x>>(nlvl-start),y>>(nlvl-start),z>>(nlvl-start))) {
                return i;
            }
        }
        return tree[0] == -1?layers:layers-1;
    }*/

    public boolean isNodeSet(int level, int x, int y, int z) {
        if (level == layers) return tree[0] == -1;
        int idx = getNodeIndex(level, x, y, z);
        return (tree[(idx>>3) + getOffsetForLevel(level)] & (1<<(idx&0b111))) != 0;
    }

    public static void main(String[] args) {
        Octreebkup ot = new Octreebkup(5, 3);
        ot.unset(2,0,0);
        //System.out.println(ot.isNodeSet(1,0,0,0));
        System.out.println(ot.findHighestFilledLevel(0,1,0,0));
    }
}
