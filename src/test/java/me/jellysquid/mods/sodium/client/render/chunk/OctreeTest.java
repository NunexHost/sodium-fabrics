package me.jellysquid.mods.sodium.client.render.chunk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class OctreeTest {
    private static RenderSection rs(int x, int y, int z) {
        return new RenderSection(null, x, y, z);
    }

    @Test
    void testSetSection() {
        Octree tree = new Octree(2, 0, 0, 0);
        RenderSection rs1 = rs(0, 0, 0);
        tree.setSection(rs1);
        assertEquals(2, tree.ignoredBits);
        assertEquals(1, tree.ownChildCount);
        assertEquals(1, tree.children[0].ignoredBits);
        assertEquals(1, tree.children[0].ownChildCount);
        assertEquals(0, tree.children[0].children[0].ignoredBits);
        assertEquals(1, tree.children[0].children[0].ownChildCount);
        assertEquals(rs1, tree.children[0].children[0].section);

        tree.setSection(rs(1, 0, 0));
        assertEquals(2, tree.ignoredBits);
        assertEquals(1, tree.ownChildCount);
        assertEquals(1, tree.children[0].ignoredBits);
        assertEquals(2, tree.children[0].ownChildCount);
        assertEquals(0, tree.children[0].children[0].ignoredBits);
        assertEquals(1, tree.children[0].children[0].ownChildCount);
        assertEquals(0, tree.children[0].children[1].ignoredBits);
        assertEquals(1, tree.children[0].children[1].ownChildCount);

        tree.setSection(rs(0, 2, 0));
        assertEquals(2, tree.ignoredBits);
        assertEquals(2, tree.ownChildCount);
        assertEquals(1, tree.children[2].ignoredBits);
        assertEquals(1, tree.children[2].ownChildCount);
        assertEquals(0, tree.children[2].children[0].ignoredBits);
        assertEquals(1, tree.children[2].children[0].ownChildCount);

        RenderSection rs2 = rs(0, 0, 0);
        tree.setSection(rs2);
        assertEquals(rs2, tree.children[0].children[0].section);
    }

    @Test
    void testSetLargeIndex() {
        Octree tree = Octree.newRoot();
        tree.setSection(rs(100, 100, 100));
    }

    @Test
    void testContains() {
        Octree tree = new Octree(2, 0, 0, 0);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    assertTrue(tree.contains(x, y, z));
                }
            }
        }
        assertFalse(tree.contains(4, 0, 0));
        assertFalse(tree.contains(0, 4, 0));
        assertFalse(tree.contains(0, 0, 4));
        assertFalse(tree.contains(0, 0, -1));
    }

    @Test
    void testRemoveSection() {
        Octree tree = new Octree(2, 0, 0, 0);
        tree.setSection(rs(0, 0, 0));
        tree.setSection(rs(1, 0, 0));
        tree.setSection(rs(0, 2, 0));

        assertEquals(2, tree.ownChildCount);
        assertEquals(2, tree.children[0].ownChildCount);
        assertEquals(1, tree.children[2].ownChildCount);
        assertEquals(1, tree.children[0].children[0].ownChildCount);
        assertEquals(1, tree.children[0].children[1].ownChildCount);
        assertEquals(1, tree.children[2].children[0].ownChildCount);

        tree.removeSection(rs(0, 0, 0));
        assertEquals(2, tree.ownChildCount);
        assertEquals(1, tree.children[0].ownChildCount);
        assertEquals(1, tree.children[2].ownChildCount);
        assertNull(tree.children[0].children[0]);
        assertEquals(1, tree.children[0].children[1].ownChildCount);
        assertEquals(1, tree.children[2].children[0].ownChildCount);

        tree.removeSection(rs(0, 2, 0));
        assertEquals(1, tree.ownChildCount);
        assertEquals(1, tree.children[0].ownChildCount);
        assertNull(tree.children[2]);
        assertNull(tree.children[0].children[0]);
        assertEquals(1, tree.children[0].children[1].ownChildCount);

        tree.removeSection(rs(1, 0, 0));
        assertEquals(0, tree.ownChildCount);
        assertNull(tree.children[0]);
        assertNull(tree.children[2]);
    }

    @Test
    void testRoot() {
        Octree root = Octree.newRoot();
        assertEquals(32, root.ignoredBits);
    }
}
