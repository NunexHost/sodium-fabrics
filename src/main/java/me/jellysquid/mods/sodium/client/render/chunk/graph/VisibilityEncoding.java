package me.jellysquid.mods.sodium.client.render.chunk.graph;

import me.jellysquid.mods.sodium.client.render.chunk.GraphDirection;

/**
 * Encoding the 6x6 visibility matrix into a 15-bit integer. This makes use of
 * the commutative (V(a,b) = V(b, a)) and irreflexive (V(a,a)=0 for all
 * directions a) property of the visibility data. It indexes the upper right
 * diagonal half of the full matrix starting at the top right corner and
 * indexing down and then to the left.
 * 
 * @author douira
 */
public class VisibilityEncoding {
    private static int getIndex(int a, int b) {
        // returns the index in the upper right triangle in which a > b.
        // undefined for a == b.
        int max = Math.max(a, b);
        int min = Math.min(a, b);

        return (((5 - max) * 5) + min) - (0b1100 >> max);
    }

    public static int addConnection(int data, int a, int b) {
        return data | (1 << getIndex(a, b));
    }

    public static boolean isConnected(int data, int a, int b) {
        return (data & (1 << getIndex(a, b))) != 0;
    }

    public static boolean isConnected(int data, int index) {
        return (data & (1 << index)) != 0;
    }

    private static int[] outgoingIndexByIncoming = new int[6];

    static {
        for (int incoming = 0; incoming < 6; incoming++) {
            int indexSet = 0;
            for (int outgoing = 0; outgoing < 6; outgoing++) {
                if (outgoing == incoming) {
                    continue;
                }
                indexSet |= getIndex(incoming, outgoing) << (outgoing * 4);
            }
            outgoingIndexByIncoming[incoming] = indexSet;
        }
    }

    public static int getIndexesForIncoming(int incoming) {
        return incoming == GraphDirection.NONE ? 0 : outgoingIndexByIncoming[incoming];
    }

    public static boolean isConnectedIndexed(int data, int packedIndexes) {
        return isConnected(data, packedIndexes & 0b1111);
    }

    public static int stepPackedIndexes(int packedIndexes) {
        return packedIndexes >> 4;
    }
}
