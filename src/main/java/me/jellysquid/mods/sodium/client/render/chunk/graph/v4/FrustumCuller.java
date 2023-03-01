package me.jellysquid.mods.sodium.client.render.chunk.graph.v4;

import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import net.minecraft.util.math.MathHelper;

import java.util.Arrays;


public final class FrustumCuller {
    final int heightOffset;
    final int layers;
    final byte[] tree;
    final int widthMsk;
    public FrustumCuller(int rd, int height, int heightOffset) {
        this.heightOffset = heightOffset;

        layers = (int) Math.ceil(Math.max(Math.log(rd*2+1)/Math.log(2), Math.log(height)/Math.log(2)));
        tree = new byte[(((1<<(layers)*3)-1)/7)];//(8^(levels)-1)/7
        widthMsk = (1<<layers)-1;
    }

    private int getOffsetForLevel(int level) {//It might be faster to make this a const final array
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

    private int getOctoIndex(int x, int y, int z) {
        return (x&1)|((z&1)<<1)|((y&1)<<2);
    }

    private Frustum frustum;
    public boolean isInFrustum(int lvl, int x, int y, int z) {
        if (true) {
            return checkNode(frustum, lvl, x, y, z, 0) != CFrust.OUTSIDE;
        }


        if (lvl == layers) return tree[0] == -1;
        return (tree[getIndex(lvl+1, x>>1, y>>1, z>>1) + getOffsetForLevel(lvl)] & (1<<(getOctoIndex(x,y,z)&0b111))) != 0;

    }

    private int checkNode(Frustum frustum, int lvl, int x, int y, int z, int parent) {
        int sx = x<<(lvl+4);
        int sy = ((y<<lvl)+heightOffset)<<4;
        int sz = z<<(lvl+4);
        int ex = (x+1)<<(lvl+4);
        int ey = (((y+1)<<lvl)+heightOffset)<<4;
        int ez = (z+1)<<(lvl+4);
        //TEMPORARY UNTIL parent CAN BE IMPLEMENTED
        Frustum.Visibility res = frustum.testBox(sx, sy, sz, ex, ey, ez);
        return switch (res) {
            case INSIDE -> CFrust.INSIDE;
            case INTERSECT -> 1;
            case OUTSIDE -> CFrust.OUTSIDE;
        };
    }

    //Recursivly mark all nodes as being within the frustum
    private void recurseMarkInside(int lvl, int x, int y, int z) {
        //TODO: check if within render distance, if not, abort
    }

    //FIXME: need to take into account the section that we are in, that is player is in a specific point
    // to fill in the tree correctyl the surrounding 9 (technically 4) tree level nodes must be tested
    // however this could mean that they are not within rd range anymore which is incorrect
    // can do what burger did an pass in min/max rd range
    private void recurseCull(Frustum frustum, int lvl, int x, int y, int z, int parentResult) {
        if (parentResult == CFrust.OUTSIDE) {
            return;
        } else if (parentResult == CFrust.INSIDE) {
            recurseMarkInside(lvl, x, y, z);
            return;
        }
        //TODO: check if within render distance, if not, abort

        //Need to extend outwards
        x <<= 1;
        y <<= 1;
        z <<= 1;
        int result = checkNode(frustum, lvl, x, y, z, parentResult);
    }

    public void cull(Frustum frustum, int x, int y, int z) {
        Arrays.fill(tree, (byte) 0);
        this.frustum = frustum;
        //recurseCull();
    }
}
