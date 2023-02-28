package me.jellysquid.mods.sodium.client.render.chunk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

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
        Octree tree = new Octree(2, 0, 0, 0, 0);
        RenderSection rs1 = rs(0, 0, 0);
        tree.setSection(rs1);
        assertEquals(2, tree.ignoredBits);
        assertEquals(1, tree.ownChildCount);
        assertTrue(tree.children[0].isLeaf());
        assertEquals(rs1, tree.children[0].section);

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
        assertTrue(tree.children[2].isLeaf());

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
        Octree tree = new Octree(2, 0, 0, 0, 0);
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
        Octree tree = new Octree(2, 0, 0, 0, 0);
        tree.setSection(rs(0, 0, 0));
        tree.setSection(rs(1, 0, 0));
        tree.setSection(rs(0, 2, 0));

        assertEquals(2, tree.ownChildCount);
        assertEquals(2, tree.children[0].ownChildCount);
        assertEquals(1, tree.children[2].ownChildCount);
        assertEquals(1, tree.children[0].children[0].ownChildCount);
        assertEquals(1, tree.children[0].children[1].ownChildCount);
        assertTrue(tree.children[2].isLeaf());

        tree.removeSection(rs(0, 0, 0));
        assertEquals(2, tree.ownChildCount);
        assertEquals(1, tree.children[0].ownChildCount);
        assertEquals(1, tree.children[2].ownChildCount);
        assertTrue(tree.children[0].isLeaf());
        assertTrue(tree.children[2].isLeaf());

        tree.removeSection(rs(0, 2, 0));
        assertEquals(1, tree.ownChildCount);
        assertEquals(1, tree.children[0].ownChildCount);
        assertNull(tree.children[2]);
        assertTrue(tree.children[0].isLeaf());

        tree.removeSection(rs(1, 0, 0));
        assertEquals(0, tree.ownChildCount);
        assertNull(tree.children[0]);
        assertNull(tree.children[2]);
    }

    @ParameterizedTest
    @MethodSource("getSectionCases")
    void testGetFaceSectionsLeaf(int x, int y, int z) {
        Octree root = Octree.newRoot();
        RenderSection rs = rs(x, y, z);
        root.setSection(rs);

        assertEquals(rs, root.getSectionOctree(rs).section);
        root.removeSection(rs);
        assertNull(root.getSectionOctree(rs));
        root.setSection(rs);
        assertEquals(rs, root.getSectionOctree(rs).section);
    }

    @ParameterizedTest
    @MethodSource("getSectionCases")
    void testGetFaceAdjacent(int x, int y, int z) {
        Octree root = Octree.newRoot();
        RenderSection rs = rs(x, y, z);
        root.setSection(rs);
        Octree rsOct = root.getSectionOctree(rs);

        for (Direction dir : DirectionUtil.ALL_DIRECTIONS) {
            RenderSection adj = rs(x + dir.getOffsetX(), y + dir.getOffsetY(), z + dir.getOffsetZ());
            root.setSection(adj);
            assertEquals(root.getSectionOctree(adj), rsOct.getFaceAdjacent(dir, true, false));
            root.removeSection(adj);
        }
    }

    static Stream<Arguments> getSectionCases() {
        return Stream.of(
                Arguments.of(1, 2, 3),
                Arguments.of(0, 2, 3),
                Arguments.of(1, 1, 3),
                Arguments.of(-1, -1, -3),
                Arguments.of(-10, -1, -3),
                Arguments.of(100, 100, -100),
                Arguments.of(0, 0, 0),
                Arguments.of(-1, 2, 0));
    }

    private static Set<Octree> octsOf(RenderSection... sections) {
        return Arrays.stream(sections).map((rs) -> rs.octreeLeaf).collect(Collectors.toSet());
    }

    @Test
    void testGetFaceSections() {
        Octree tree = new Octree(2, 0, 0, 0, 0);
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
            assertFalse(tree.getFaceNodes(dir, false).contains(rs0.octreeLeaf));
        }

        assertEquals(octsOf(rs1, rs4), Set.copyOf(tree.getFaceNodes(0, -1, false)));
        assertEquals(octsOf(rs5), Set.copyOf(tree.getFaceNodes(0, 1, false)));
        assertEquals(octsOf(rs3), Set.copyOf(tree.getFaceNodes(1, -1, false)));
        assertEquals(octsOf(rs2), Set.copyOf(tree.getFaceNodes(1, 1, false)));
        assertEquals(octsOf(), Set.copyOf(tree.getFaceNodes(2, -1, false)));
        assertEquals(octsOf(rs1, rs2, rs5), Set.copyOf(tree.getFaceNodes(2, 1, false)));
    }

    @Test
    void testGetFaceAdjacentSections() {
        Octree tree = new Octree(2, 0, 0, 0, 0);
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

        Octree oct0 = rs0.octreeLeaf;
        Octree oct1 = rs1.octreeLeaf;
        Octree oct3 = rs3.octreeLeaf;
        Octree oct4 = rs4.octreeLeaf;
        Octree oct5 = rs5.octreeLeaf;

        assertEquals(octsOf(rs4), Set.copyOf(oct0.getFaceAdjacentNodes(0, -1, false)));
        assertEquals(octsOf(rs0), Set.copyOf(oct4.getFaceAdjacentNodes(0, 1, false)));
        assertEquals(octsOf(), Set.copyOf(tree.getFaceAdjacentNodes(1, -1, false)));
        assertEquals(octsOf(), Set.copyOf(tree.getFaceAdjacentNodes(0, 1, false)));
        assertEquals(octsOf(), Set.copyOf(oct1.getFaceAdjacentNodes(0, -1, false)));
        assertEquals(octsOf(), Set.copyOf(oct3.parent.getFaceAdjacentNodes(0, 1, false)));
        assertEquals(octsOf(rs1), Set.copyOf(oct3.parent.getFaceAdjacentNodes(2, 1, false)));
        // assertEquals(octsOf(rs3), Set.copyOf(oct1.parent.getFaceAdjacentNodes(2, -1, false)));
        assertNotEquals(octsOf(rs0), Set.copyOf(oct1.parent.getFaceAdjacentNodes(1, -1, false)));
        assertNotEquals(octsOf(rs0), Set.copyOf(oct1.parent.getFaceAdjacentNodes(1, 1, false)));

        // assertEquals(oct0.parent, oct1.parent.getFaceAdjacent(1, 1, true, false));
        // assertEquals(oct1.parent, oct0.parent.getFaceAdjacent(1, -1, true, false));
        assertEquals(oct1, oct4.getFaceAdjacent(1, -1, true, false));
        assertEquals(oct1, oct4.getFaceAdjacent(1, -1, false, false));
        assertEquals(oct0, oct4.getFaceAdjacent(0, 1, true, false));
        assertEquals(oct3.parent, oct1.getFaceAdjacent(2, -1, false, false));
        assertNull(oct1.getFaceAdjacent(2, -1, true, false));
        assertNull(oct5.getFaceAdjacent(0, 1, false, false));
        assertNull(oct5.getFaceAdjacent(0, 1, true, false));
    }

    @Test
    void testSkippableCount() {
        Octree tree = new Octree(2, 0, 0, 0, 0);
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

        assertEquals(tree, rs0.octreeLeaf.parent.parent);

        rs0.octreeLeaf.setLeafSkippable(false);
        assertEquals(0, rs0.octreeLeaf.skippableChildren);
        rs0.octreeLeaf.setLeafSkippable(true);
        assertEquals(1, rs0.octreeLeaf.skippableChildren);
        assertEquals(1, rs0.octreeLeaf.parent.skippableChildren);
        assertEquals(0, rs0.octreeLeaf.parent.parent.skippableChildren);
        rs1.octreeLeaf.setLeafSkippable(true);
        rs3.octreeLeaf.setLeafSkippable(true);
        rs4.octreeLeaf.setLeafSkippable(true);
        rs5.octreeLeaf.setLeafSkippable(true);
        assertEquals(3, rs0.octreeLeaf.parent.parent.skippableChildren);
        rs1.octreeLeaf.setLeafSkippable(false);
        rs3.octreeLeaf.setLeafSkippable(false);
        rs4.octreeLeaf.setLeafSkippable(false);
        rs5.octreeLeaf.setLeafSkippable(false);
        assertEquals(0, rs0.octreeLeaf.parent.parent.skippableChildren);
    }
}
