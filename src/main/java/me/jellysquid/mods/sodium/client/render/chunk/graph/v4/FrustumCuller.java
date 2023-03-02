package me.jellysquid.mods.sodium.client.render.chunk.graph.v4;

import java.util.*;

import org.joml.Vector3f;

import me.jellysquid.mods.sodium.client.util.frustum.Frustum;


public final class FrustumCuller {
    final int heightOffset;
    final int layers;
    final byte[] tree;
    final int widthMsk;

    private static final int EXPAND_BOX_WAIT = 1000;
    private static final int BOX_PURGE_INTERVAL = 30;
    private static final float PURGE_BOX_THRESHOLD = 0.05f;
    public Vector3f[][] visibleBoxes;
    public int[] visibleBoxHits;
    private int maxVisibleBoxes = 5;
    public int visibleBoxCount = 0;
    private int expandBoxWaitCount = 0;

    public int frustumCheckActualCount = 0;
    public int frustumCheckPotentialCount = 0;
    public int frustumCheckBoxCount = 0;
    public int boxTestCount = 0;
    public int clearedBoxes = 0;

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

    private int updateCount = 0;

    private static class AcceptableBox {
        Vector3f[] box;
        int hits;

        private AcceptableBox(Vector3f[] box, int hits) {
            this.box = box;
            this.hits = hits;
        }
    }

     /**
     * Tests if the given box is acceptable for box testing with the given frustum.
     * It tests if the box that is 16 blocks smaller in each of the 6 directions is
     * inside the frustum. Since box tests only need to ensure they contain only
     * boxes that intersect with the frustum this is sufficient. Testing a smaller
     * box allows the boxes to become bigger and intersect with the frustum, thus
     * covering more sections that can be efficiently included. Testing if the box
     * intersects with the frustum doesn't work, since it could then be for example
     * mostly outside the frustum and just touch it, allowing sections outside the
     * frustum to be wrongly included.
     */
    private boolean boxIsAcceptable(Frustum frustum, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return frustum.testBox(minX + 16f, minY + 16f, minZ + 16f, maxX - 16f, maxY - 16f, maxZ - 16f) == Frustum.Visibility.INSIDE;
    }

    private void compactBoxes() {
        // find the boxes that are within the frustum and only keep those
        List<AcceptableBox> acceptableBoxes = new ArrayList<>();
        for (int i = 0; i < visibleBoxCount; i++) {
            Vector3f bominX = visibleBoxes[i][0];
            Vector3f bomaxX = visibleBoxes[i][1];
            if (boxIsAcceptable(frustum, bominX.x, bominX.y, bominX.z, bomaxX.x, bomaxX.y, bomaxX.z)) {
                acceptableBoxes.add(new AcceptableBox(visibleBoxes[i], visibleBoxHits[i]));
            }
        }

        // sort by hits descending
        acceptableBoxes.sort(Comparator.comparingInt(o -> -o.hits));

        // update the number of actually present visible boxes,
        // and make sure it doesn't exceed the number of allowed visible boxes
        visibleBoxCount = Math.min(acceptableBoxes.size(), maxVisibleBoxes);

        // clear boxes that contribute too few hits relative to the best boxes
        if (visibleBoxCount >= 2 && ++updateCount >= BOX_PURGE_INTERVAL) {
            int firstSecondAverage = visibleBoxCount >= 3 
                ? (acceptableBoxes.get(0).hits + acceptableBoxes.get(1).hits) / 2
                : acceptableBoxes.get(0).hits;
            int hitThreshold = (int)(PURGE_BOX_THRESHOLD * firstSecondAverage);
            int previousVisibleBoxCount = visibleBoxCount;
            while ((float)acceptableBoxes.get(visibleBoxCount - 1).hits < hitThreshold) {
                visibleBoxCount--;
                if (visibleBoxCount == 0) break;
            }
            clearedBoxes = previousVisibleBoxCount - visibleBoxCount;

            // reset the hit counters
            for (AcceptableBox box : acceptableBoxes) {
                box.hits = 0;
            }

            updateCount = 0;
        }
        for (int i = visibleBoxCount; i < maxVisibleBoxes; i++) {
            visibleBoxHits[i] = 0;
        }

        // copy over as many sorted boxes as allowed
        for (int i = 0; i < visibleBoxCount; i++) {
            AcceptableBox box = acceptableBoxes.get(i);
            visibleBoxes[i][0].set(box.box[0]);
            visibleBoxes[i][1].set(box.box[1]);
            visibleBoxHits[i] = box.hits;
        }
    }

    private void initBoxTestState() {
        // when the allowed number of boxes increases, recreate the array and copy over the boxes
        int prevLength = visibleBoxes == null ? 0 : visibleBoxes.length;
        if (maxVisibleBoxes > prevLength) {
            Vector3f[][] newVisibleBoxes = new Vector3f[maxVisibleBoxes][2];
            int[] newVisibleBotHits = new int[maxVisibleBoxes];
            if (prevLength > 0) {
                System.arraycopy(visibleBoxes, 0, newVisibleBoxes, 0, prevLength);
                System.arraycopy(visibleBoxHits, 0, newVisibleBotHits, 0, prevLength);
            }
            visibleBoxes = newVisibleBoxes;
            visibleBoxHits = newVisibleBotHits;
            for (int i = prevLength; i < maxVisibleBoxes; i++) {
                visibleBoxes[i][0] = new Vector3f();
                visibleBoxes[i][1] = new Vector3f();
                visibleBoxHits[i] = 0;
            }
        }

        // update boxes with the frustum, combine if possible, sort by size,
        // and update the number of visible boxes
        compactBoxes();

        // reset performance counters
        frustumCheckActualCount = 0;
        frustumCheckBoxCount = 0;
        boxTestCount = 0;
        frustumCheckPotentialCount = 0;

        // reset the expand box wait count with random value up the maximum count,
        // since the box expansion happens at a regular interval this ensures
        // roughly random sections are considered for box expansion each frame
        expandBoxWaitCount = (int)(Math.random() * EXPAND_BOX_WAIT);
    }

    private boolean isVisibleInBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int i = 0; i < visibleBoxCount; i++) {
            Vector3f[] corners = visibleBoxes[i];
            Vector3f min = corners[0];
            Vector3f max = corners[1];
            boxTestCount++;
            if (min.x <= minX && maxX < max.x && min.y <= minY && maxY < max.y && min.z <= minZ && maxZ < max.z) {
                visibleBoxHits[i]++;
                return true;
            }
        }
        return false;
    }

    private void expandBoxWithSection(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean expanded = false;
        for (int i = 0; i < visibleBoxCount; i++) {
            Vector3f[] corners = visibleBoxes[i];

            // combine with this box
            float newBominXX = Math.min(corners[0].x, minX);
            float newBominXY = Math.min(corners[0].y, minY);
            float newBominXZ = Math.min(corners[0].z, minZ);
            float newBomaxXX = Math.max(corners[1].x, maxX);
            float newBomaxXY = Math.max(corners[1].y, maxY);
            float newBomaxXZ = Math.max(corners[1].z, maxZ);

            // check that the box is still within the frustum
            frustumCheckBoxCount++;
            if (boxIsAcceptable(frustum, newBominXX, newBominXY, newBominXZ, newBomaxXX, newBomaxXY, newBomaxXZ)) {
                // replace the box
                corners[0].set(newBominXX, newBominXY, newBominXZ);
                corners[1].set(newBomaxXX, newBomaxXY, newBomaxXZ);
                visibleBoxHits[i]++;
                expanded = true;
                break;
            }
        }

        // didn't combine with any box, add a new one if possible
        if (!expanded && visibleBoxCount < maxVisibleBoxes) {
            visibleBoxes[visibleBoxCount][0].set(minX, minY, minZ);
            visibleBoxes[visibleBoxCount][1].set(maxX, maxY, maxZ);
            visibleBoxHits[visibleBoxCount] = 1;
            visibleBoxCount++;
        }
    }

    private boolean isFrustumCulled(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        frustumCheckPotentialCount++;

        // float x = render.getOriginX();
        // float y = render.getOriginY();
        // float z = render.getOriginZ();

        // check if within a visible box to avoid checking the frustum
        if (!isVisibleInBox(minX, minY, minZ, maxX, maxY, maxZ)) {
            frustumCheckActualCount++;
            if(!frustum.isBoxVisible(minX, minY, minZ, maxX, maxY, maxZ)) {
                return true;
            }

            // visible, add to visible boxes
            // iterate the existing boxes to check if we can add to them
            if (++expandBoxWaitCount > EXPAND_BOX_WAIT) {
                expandBoxWaitCount = 0;
                expandBoxWithSection(minX, minY, minZ, maxX, maxY, maxZ);
            }
        }
        return false;
    }

    private Frustum frustum;
    public boolean isInFrustum(int sx, int sy, int sz, int ex, int ey, int ez) {
        // if (true) {
            return !isNodeFrustumCulled(frustum, sx, sy, sz, ex, ey, ez);
        // }


        // if (lvl == layers) return tree[0] == -1;
        // return (tree[getIndex(lvl+1, x>>1, y>>1, z>>1) + getOffsetForLevel(lvl)] & (1<<(getOctoIndex(x,y,z)&0b111))) != 0;

    }

    private boolean isNodeFrustumCulled(Frustum frustum, int sx, int sy, int sz, int ex, int ey, int ez) {
        // int sx = x<<(lvl+4);
        // int sy = ((y<<lvl)+heightOffset)<<4;
        // int sz = z<<(lvl+4);
        // int ex = (x+1)<<(lvl+4);
        // int ey = (((y+1)<<lvl)+heightOffset)<<4;
        // int ez = (z+1)<<(lvl+4);
        //TEMPORARY UNTIL parent CAN BE IMPLEMENTED
        return isFrustumCulled(sx, sy, sz, ex, ey, ez);
        // Frustum.Visibility res = frustum.testBox(sx, sy, sz, ex, ey, ez);
        // return switch (res) {
        //     case INSIDE -> CFrust.INSIDE;
        //     case INTERSECT -> 1;
        //     case OUTSIDE -> CFrust.OUTSIDE;
        // };
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
        // int result = checkNode(frustum, lvl, x, y, z, parentResult);
    }

    public void cull(Frustum frustum, int x, int y, int z) {
        Arrays.fill(tree, (byte) 0);
        this.frustum = frustum;
        initBoxTestState();
        //recurseCull();
    }
}
