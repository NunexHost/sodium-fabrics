package me.jellysquid.mods.sodium.client.render.chunk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import me.jellysquid.mods.sodium.common.util.DirectionUtil;
import net.minecraft.util.math.Direction;

/**
 * Tests for the {@link Octree} class to make sure the logic is correct.
 * 
 * @author douira
 */
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

    @Test
    void testGetFaceSectionsLeaf() {
        Octree root = Octree.newRoot();
        RenderSection rs = rs(1, 2, 3);
        root.setSection(rs);

        assertEquals(rs, root.getSectionOctree(rs).section);
        root.removeSection(rs);
        assertNull(root.getSectionOctree(rs));
        root.setSection(rs);
        assertEquals(rs, root.getSectionOctree(rs).section);
    }

    @Test
    void testGetFaceAdjacent() {
        Octree root = Octree.newRoot();
        int x = 1;
        int y = 2;
        int z = 3;
        RenderSection rs = rs(x, y, z);
        root.setSection(rs);
        Octree rsOct = root.getSectionOctree(rs);

        for (Direction dir : DirectionUtil.ALL_DIRECTIONS) {
            RenderSection adj = rs(x + dir.getOffsetX(), y + dir.getOffsetY(), z + dir.getOffsetZ());
            root.setSection(adj);
            assertEquals(root.getSectionOctree(adj), rsOct.getFaceAdjacent(dir, true));
            root.removeSection(adj);
        }
    }

    @Test
    void testGetFaceSections() {
        Octree tree = new Octree(2, 0, 0, 0);
        RenderSection rs0 = rs(1, 2, 2);
        RenderSection rs1 = rs(0, 1, 3);
        RenderSection rs2 = rs(1, 3, 3);
        RenderSection rs3 = rs(1, 0, 1);
        RenderSection rs4 = rs(0, 2, 2);
        RenderSection rs5 = rs(3, 2, 3);

        tree.setSection(rs0);
        tree.setSection(rs1);
        tree.setSection(rs2);
        tree.setSection(rs3);
        tree.setSection(rs4);
        tree.setSection(rs5);

        for (Direction dir : DirectionUtil.ALL_DIRECTIONS) {
        assertFalse(tree.getFaceSections(dir).contains(rs0));
        }

        assertEquals(Set.of(rs1, rs4), Set.copyOf(tree.getFaceSections(0, -1)));
        assertEquals(Set.of(rs5), Set.copyOf(tree.getFaceSections(0, 1)));
        assertEquals(Set.of(rs3), Set.copyOf(tree.getFaceSections(1, -1)));
        assertEquals(Set.of(rs2), Set.copyOf(tree.getFaceSections(1, 1)));
        assertEquals(Set.of(), Set.copyOf(tree.getFaceSections(2, -1)));
        assertEquals(Set.of(rs1, rs2, rs5), Set.copyOf(tree.getFaceSections(2, 1)));
    }

    @Test
    void testGetFaceAdjacentSections() {
        Octree tree = new Octree(2, 0, 0, 0);
        RenderSection rs0 = rs(1, 2, 2);
        RenderSection rs1 = rs(0, 1, 2);
        RenderSection rs3 = rs(1, 0, 1);
        RenderSection rs4 = rs(0, 2, 2);
        RenderSection rs5 = rs(0, 0, 0);

        tree.setSection(rs0);
        tree.setSection(rs1);
        tree.setSection(rs3);
        tree.setSection(rs4);
        tree.setSection(rs5);

        Octree oct0 = tree.getSectionOctree(rs0);
        Octree oct1 = tree.getSectionOctree(rs1);
        Octree oct3 = tree.getSectionOctree(rs3);
        Octree oct4 = tree.getSectionOctree(rs4);
        Octree oct5 = tree.getSectionOctree(rs5);

        assertEquals(Set.of(rs4), Set.copyOf(oct0.getFaceAdjacentSections(0, -1)));
        assertEquals(Set.of(rs0), Set.copyOf(oct4.getFaceAdjacentSections(0, 1)));
        assertEquals(Set.of(), Set.copyOf(tree.getFaceAdjacentSections(1, -1)));
        assertEquals(Set.of(), Set.copyOf(tree.getFaceAdjacentSections(0, 1)));
        assertEquals(Set.of(), Set.copyOf(oct1.getFaceAdjacentSections(0, -1)));
        assertEquals(Set.of(), Set.copyOf(oct3.parent.getFaceAdjacentSections(0, 1)));
        assertEquals(Set.of(rs1), Set.copyOf(oct3.parent.getFaceAdjacentSections(2, 1)));
        assertEquals(Set.of(rs3), Set.copyOf(oct1.parent.getFaceAdjacentSections(2, -1)));
        assertNotEquals(Set.of(rs0), Set.copyOf(oct1.parent.getFaceAdjacentSections(1, -1)));
        assertNotEquals(Set.of(rs0), Set.copyOf(oct1.parent.getFaceAdjacentSections(1, 1)));

        assertEquals(oct0.parent, oct1.parent.getFaceAdjacent(1, 1, true));
        assertEquals(oct1.parent, oct0.parent.getFaceAdjacent(1, -1, true));
        assertEquals(oct1, oct4.getFaceAdjacent(1, -1, true));
        assertEquals(oct1, oct4.getFaceAdjacent(1, -1, false));
        assertEquals(oct0, oct4.getFaceAdjacent(0, 1, true));
        assertEquals(oct3.parent, oct1.getFaceAdjacent(2, -1, false));
        assertNull(oct1.getFaceAdjacent(2, -1, true));
        assertNull(oct5.getFaceAdjacent(0, 1, false));
        assertNull(oct5.getFaceAdjacent(0, 1, true));
    }

    @Test
    void testAdjacentNegativeHandling() {
        // TODO: test to verify/find out if the adjacency features work across the positive/negative boundary
    }
}
