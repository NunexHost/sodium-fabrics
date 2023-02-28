package me.jellysquid.mods.sodium.client.render.chunk.graph.v4;

import java.util.Arrays;

public final class Octree {
    final byte[] tree;
    private final int layers;
    private final int widthMsk;
    public Octree(int width, int height) {
        layers = (int) Math.ceil(Math.max(Math.log(width)/Math.log(2), Math.log(height)/Math.log(2)));
        tree = new byte[(((1<<(layers)*3)-1)/7)];//(8^(levels)-1)/7
        widthMsk = (1<<layers)-1;
        //Arrays.fill(tree, (byte) -1);
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


    private int getIndex(int lvl, int leafX, int leafY, int leafZ) {
        int ilvl = layers - lvl;
        return (leafX&(widthMsk>>lvl)) |
                ((leafZ&(widthMsk>>lvl))<<ilvl) |
                ((leafY&(widthMsk>>lvl))<<(ilvl<<1))
                ;
    }

    protected int getOctoIndex(int x, int y, int z) {
        return (x&1)|((z&1)<<1)|((y&1)<<2);
    }

    public void set(int x, int y, int z) {
        for (int i = 0; i < layers; i++) {
            byte parent = tree[getIndex(i+1,x>>(i+1),y>>(i+1),z>>(i+1))+getOffsetForLevel(i)] |= 1<<getOctoIndex(x>>(i), y>>(i), z>>(i));
            if (parent != (byte) 0xFF) {
                break;
            }
        }
    }

    public void unset(int x, int y, int z) {
        for (int i = 0; i < layers; i++) {
            int parentIdx = getIndex(i+1,x>>(i+1),y>>(i+1),z>>(i+1))+getOffsetForLevel(i);
            byte parent = tree[parentIdx];
            boolean shouldContinue = parent == (byte) 0xFF;
            parent &= ~(1<<1<<getOctoIndex(x>>(i+1), y>>(i+1), z>>(i+1)));
            tree[parentIdx] = parent;
            if (!shouldContinue) {
                break;
            }
        }
    }

    public boolean isNodeSet(int level, int x, int y, int z) {
        if (level == layers) return tree[0] == -1;
        return (tree[getIndex(level+1, x>>1, y>>1, z>>1) + getOffsetForLevel(level)] & (1<<(getOctoIndex(x,y,z)&0b111))) != 0;
    }

    public int findHighestFilledLevel(int start, int x, int y, int z) {
        int lvl = Math.max(start-1, 0);
        for (int i = lvl; i<layers-1; i++) {
            int nlvl = i+1;
            if (!isNodeSet(i+1, x>>(nlvl-start),y>>(nlvl-start),z>>(nlvl-start))) {
                return i;
            }
        }
        return tree[0] == -1?layers:layers-1;
    }

    public static void main(String[] args) {
        Octree ot = new Octree(5, 3);
        ot.set(0,0,0);
        ot.set(1,0,0);
        ot.set(0,1,0);
        ot.set(1,1,0);
        ot.set(0,0,1);
        ot.set(1,0,1);
        ot.set(0,1,1);
        ot.set(1,1,1);
        System.out.println(ot.isNodeSet(1,0,0,0));
        System.out.println(ot.findHighestFilledLevel(0,1,0,0));
    }

}
