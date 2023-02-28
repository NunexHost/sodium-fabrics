package me.jellysquid.mods.sodium.client.render.chunk.graph.v4;

import java.util.Arrays;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import me.jellysquid.mods.sodium.common.util.DirectionUtil;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

public final class GraphExplorer {
    final IntArrayList[] queues;
    final short[] traversalData;
    final int[] lvlTraversalBaseOffset;
    final Octree tree;
    final int widthBits;
    final int heightBits;
    final int widthMSK;
    final int heightMSK;

    final byte[] visibilityData;

    final int rd;
    final int maxHeight;
    public GraphExplorer(int renderDistance, int height) {
        int width = renderDistance*2+1;
        tree = new Octree(width, height);
        this.rd = renderDistance;
        this.maxHeight = height;
        widthBits = Integer.numberOfTrailingZeros(MathHelper.smallestEncompassingPowerOfTwo(width));
        heightBits = Integer.numberOfTrailingZeros(MathHelper.smallestEncompassingPowerOfTwo(height));
        widthMSK = (1<<widthBits)-1;
        heightMSK = (1<<heightBits)-1;
        lvlTraversalBaseOffset = new int[Math.max(widthBits, heightBits)];
        int nodeCount = 0;
        int q = lvlTraversalBaseOffset.length;
        for (int i = 0; i < q; i++) {
            lvlTraversalBaseOffset[i] = nodeCount;
            nodeCount += ((1<<heightBits)>>(q-i-1))*((1<<widthBits)>>(q-i-1))*((1<<widthBits)>>(q-i-1));
        }
        traversalData = new short[nodeCount];

        visibilityData = new byte[((1<<heightBits)*(1<<widthBits)*(1<<widthBits))*6];

        //FIXME
        queues = new IntArrayList[100];
        for (int i = 0; i < queues.length; i++) queues[i] = new IntArrayList();
    }

    int cx;
    int cy;
    int cz;

    //Assumes the input x,y,z are preshifted by lvl
    // lvl 0 is the leaf nodes
    private int getTraversalDataIndex(int lvl, int x, int y, int z) {
        //TODO: add an assertion check for debugging
        int base = (x&(widthMSK>>lvl)) | ((z&(widthMSK>>lvl))<<(widthBits-lvl)) | ((y&(heightMSK>>lvl))<<(((widthBits-lvl)<<1)));
        return base + lvlTraversalBaseOffset[lvlTraversalBaseOffset.length-lvl-1];
    }

    public void setVisibilityData(int x, int y, int z, int dir, byte data) {
        int idx = (x& widthMSK) | (z& widthMSK)<< widthBits | (y& heightMSK)<< (widthBits <<1);
        visibilityData[idx*6+dir] = data;
    }

    private short getVisibilityData(int x, int y, int z, int dir) {
        int idx = (x& widthMSK) | (z& widthMSK)<< widthBits | (y& heightMSK)<< (widthBits <<1);
        return visibilityData[idx*6+dir];
    }

    //TODO: optimize this
    private boolean isInRenderBounds(int lvl, int x, int y, int z) {
        int msk = (1<<lvl)-1;
        if ((y<<lvl)+msk < 0 || maxHeight < (y<<lvl)) return false;
        int dx = Math.min(Math.abs(cx - (x<<lvl)), Math.abs(cx - ((x<<lvl)+msk)));
        int dz = Math.min(Math.abs(cz - (z<<lvl)), Math.abs(cz - ((z<<lvl)+msk)));

        return dx*dx+dz*dz<=rd*rd;
    }

    public Frustum frustum;
    //TODO: make a frustum octree and pretest that, then just sample it, should be like 300% faster
    private boolean isInFrustum(int lvl, int x, int y, int z) {
        //y -= 4;//FIXME
        if (frustum == null) return true;
        int sx = x<<(lvl+4);
        int sy = y<<(lvl+4);
        int sz = z<<(lvl+4);
        int ex = (x+1)<<(lvl+4);
        int ey = (y+1)<<(lvl+4);
        int ez = (z+1)<<(lvl+4);
        sy -= 4<<4;
        ey -= 4<<4;
        return frustum.isBoxVisible(sx,sy,sz,ex,ey,ez);//TODO: FIXME
    }

    private int queryTreeHighestFilledLevel(int lvl, int x, int y, int z) {
        return tree.findHighestFilledLevel(lvl, x&(widthMSK>>lvl), y&(heightMSK>>lvl), z&(widthMSK>>lvl));
    }

    private boolean queryTreeNodeSet(int lvl, int x, int y, int z) {
        return tree.isNodeSet(lvl, x&(widthMSK>>lvl), y&(heightMSK>>lvl), z&(widthMSK>>lvl));
    }


    void enqueue(int lvl, int x, int y, int z, int cdist) {
        //if (queryTreeHighestFilledLevel(lvl, x, y, z) != lvl) {
        //    throw new IllegalStateException();
        //}

        int msk = (1<<lvl)-1;
        int dx = Math.min(Math.abs(cx-(x<<lvl)),Math.abs(cx-((x<<lvl)+msk)));
        int dy = Math.min(Math.abs(cy-(y<<lvl)),Math.abs(cy-((y<<lvl)+msk)));
        int dz = Math.min(Math.abs(cz-(z<<lvl)),Math.abs(cz-((z<<lvl)+msk)));

        int distance = dx+dy+dz;

        //TODO: check that distance >= currentDistance
        var queue = queues[Math.max(distance, cdist)];//TODO: idk if it should be cdist or cdist+1
        queue.add(x);
        queue.add(z);
        queue.add(y|(lvl<<24));
    }

    void expandDirection(int lvl, int x, int y, int z, int inbound, short srcDirMsk, int cdist) {
        int idx = getTraversalDataIndex(lvl, x, y, z);
        short selfTraversalData = traversalData[idx];

        if (selfTraversalData == 0) {
            //queues[Math.min(computeManhattan(lvl, idx), currentDistance)].add(idx|(lvl<<29));
            enqueue(lvl, x, y, z, cdist);
            selfTraversalData |= (short) (1 << 15) | srcDirMsk;
        }

        //int inboundDir = DirectionUtil.getOppositeId(outgoingDir);
        selfTraversalData |= queryTreeNodeSet(lvl, x, y, z)?0xFF:getVisibilityData(x, y, z, inbound);//this.getVisibilityData(neighborSectionIdx, inboundDir);
        selfTraversalData &= ~(1 << (8 + inbound)); // Un mark incoming direction
        selfTraversalData &= srcDirMsk|0xFF;
        traversalData[idx] = selfTraversalData;
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

    private void expandAlongFaceRecursively(int lvl, int x, int y, int z, int face, short srcDirMsk, int cdist) {
        //if (computeManhattan(lvl, idx)<currentDistance) return;//TODO: THIS
        if (!(isInRenderBounds(lvl, x, y, z) && isInFrustum(lvl, x, y, z))) return;
        if (lvl == 0) {//At root level, enqueue
            expandDirection(lvl, x, y, z, face, srcDirMsk, cdist);
        } else if (queryTreeNodeSet(lvl, x, y, z)) {//Its a full air node, enqueue
            expandDirection(lvl, x, y, z, face, srcDirMsk, cdist);
        } else {//Its a mix, or fully solid so must recurse
            for (int i : FACEOCTS[face]) {
                expandAlongFaceRecursively(lvl-1, (x<<1)+(i&1), (y<<1)+((i>>2)&1), (z<<1)+((i>>1)&1), face, srcDirMsk, cdist);
            }
        }
    }

    void expand(int lvl, int x, int y, int z, int cdist) {
        int idx = getTraversalDataIndex(lvl, x, y, z);
        short data = traversalData[idx];
        //Explore like the normal bfs
        data &= ((data >> 8) & 0xFF) | 0xFF00;//Upper bits represent traversable directions (directions not already traversed), lower bits represent visibilityBits (directions visible due to visibility)
        //traversalData[idx + level0Offset] = data;
        for (int dir = 0; dir < 6; dir++) {
            if ((data & (1 << dir)) == 0) {
                continue;
            }

            int nx = x;
            int ny = y;
            int nz = z;
            switch (dir) {
                case 0 -> ny--;
                case 1 -> ny++;
                case 2 -> nz--;
                case 3 -> nz++;
                case 4 -> nx--;
                case 5 -> nx++;
            }


            //if (!withinRDRange(lvl, idx)) return;
            //if (computeManhattan(lvl, idx)<currentDistance) return;//TODO: CHECK if should do this here
            if (!(isInRenderBounds(lvl, nx, ny, nz)&&isInFrustum(lvl, nx, ny, nz))) {
                continue;
            }

            int nlvl = queryTreeHighestFilledLevel(lvl, nx, ny, nz);
            if (nlvl >= lvl) {//We either grow or stay on the same lvl
                int deltaLvl = nlvl - lvl;

                //Need to adjust our coordinate space
                nx >>= deltaLvl;
                ny >>= deltaLvl;
                nz >>= deltaLvl;
                expandDirection(nlvl, nx, ny, nz, DirectionUtil.getOpposite(dir), (short) (data&0xFF00), cdist);
            } else {//We go down a lvl, need to enumerate over faces
                expandAlongFaceRecursively(lvl, nx, ny, nz, DirectionUtil.getOpposite(dir), (short) (data&0xFF00), cdist);
            }
        }
    }

    public interface IResultConsumer {void accept(int x, int y, int z);}
    public void explore(int x, int y, int z) {
        explore(x, y, z, (a,b,c)->{});
    }
    public void explore(int x, int y, int z, IResultConsumer consumer) {
        Arrays.fill(traversalData, (short) 0);
        cx = x;
        cy = y;
        cz = z;
        {
            int lvl = queryTreeHighestFilledLevel(0, x, y, z);
            int bx = x >> lvl;
            int by = y >> lvl;
            int bz = z >> lvl;
            enqueue(lvl, bx, by, bz, 0);
            traversalData[getTraversalDataIndex(lvl, bx, by, bz)] = -1;
        }

        int nodeVisitedCount = 0;
        int nodeVisitedSolidCount = 0;
        int nodeVisitedBigCount = 0;
        int skippedAirCount = 0;
        for (int distance = 0; distance < queues.length; distance++) {
            var queue = queues[distance];
            for (int i = 0; i < queue.size(); i += 3) {
                int nx = queue.getInt(i);
                int nz = queue.getInt(i+1);
                int ny = queue.getInt(i+2);
                int lvl = (ny>>24)&0xFF;
                ny &= 0xFFFFFF;

                //if (queryTreeHighestFilledLevel(lvl, nx, ny, nz) != lvl) {
                //    throw new IllegalStateException();
                //}
                if (lvl == 0 && !queryTreeNodeSet(0, nx, ny, nz)) {
                    consumer.accept(nx, ny, nz);
                    nodeVisitedSolidCount++;
                }
                expand(lvl, nx, ny, nz, distance);
                nodeVisitedCount++;
                nodeVisitedBigCount+=lvl!=0?1:0;
                skippedAirCount+=lvl!=0?(1<<(lvl*3))-1:0;
            }
            queue.clear();
            //for (int i = 0; i < distance; i++) {
            //    if (!queues[i].isEmpty()) {
            //        throw new IllegalStateException();
            //    }
            //}
        }

        //for (int i = 0; i < queues.length; i++) {
        //    if (!queues[i].isEmpty()) {
        //        throw new IllegalStateException();
        //    }
        //}

        //System.out.println("T:" + nodeVisitedCount + " B:"+nodeVisitedBigCount+" S:"+nodeVisitedSolidCount+" s:"+skippedAirCount);
    }

    public void setAir(int x, int y, int z) {
        tree.set(x&widthMSK, y&heightMSK, z&widthMSK);//TODO: check this is correct with the masks
    }

    public void unsetAir(int x, int y, int z) {
        tree.unset(x&widthMSK, y&heightMSK, z&widthMSK);//TODO: check this is correct with the masks
    }

    public static void main(String[] args) {
        GraphExplorer ge = new GraphExplorer(32, 24);
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                ge.unsetAir(x,0,z);
                ge.unsetAir(x,1,z);
            }
        }
        //Arrays.fill(ge.tree.tree, (byte) 0);
        //System.out.println(ge.queryTreeHighestFilledLevel(0,1,0,0));
        //System.out.println(ge.queryTreeNodeSet(1,0,0,0));
        for (int x = -32; x < 32; x++) {
            for (int z = -32; z < 32; z++) {
                for (int y = 0; y < 7; y++) {
                    ge.explore(x,y,z);
                }
            }
        }
        if (true) {
            for (int i = 0; i < 100; i++) {
                ge.explore(0, 0, 0);
            }
            long t = System.currentTimeMillis();
            for (int i = 0; i < 1000; i++) {
                ge.explore(0, 10, 0);
            }
            System.out.println(System.currentTimeMillis() - t);
        }
        //System.out.println(ge);
    }
}
