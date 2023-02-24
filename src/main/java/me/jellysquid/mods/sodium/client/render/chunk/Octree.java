package me.jellysquid.mods.sodium.client.render.chunk;

import java.util.*;
import java.util.stream.IntStream;

import me.jellysquid.mods.sodium.common.util.DirectionUtil;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Direction.Axis;

/**
 * An octree node.
 * 
 * Octree idea: The octree contains 1 render section or 8 children, each of
 * which can contain 8 more children. If a node isn't fully within the frustum,
 * the connectedness of its parts needs to be checked. If the node is fully
 * within the frustum, it can be rendered without checking its children.
 * 
 * When the connectedness of the children is checked because the parent isn't
 * fully in the frustum, then it's assumed that the search starts at in the
 * direction from which the paren't sibling wants to access the parent.
 * 
 * Problem: what happens when the parent's sibling is only connected to the
 * parent visibly through one of it's children to the parents children. Then one
 * of the parents children would be checked unnecessarily as it's not visibly
 * connected. Solution: only consider an octree node connected from one face to
 * another if all sections on that face connect to all sections of the other
 * face.
 *
 * TODO: acceleration structure to get from parent to children faster:
 * references to the highest/largest/closest child that has more than 1 child
 * anywhere in the subtree.
 * 
 * @author douira
 */
public class Octree {
    // either children or section is null, the other is not null
    public RenderSection section;
    public final Octree[] children;
    public int ownChildCount = 0;

    public Octree parent; // null for the root node
    public int indexInParent; // only valid for non-root nodes, TODO: necessary?

    public final int ignoredBits;
    public final int filter;
    public final int selector;
    public final int x, y, z;

    public Octree(RenderSection section) {
        Objects.requireNonNull(section);

        ignoredBits = 0;
        filter = -1;
        selector = 0; // doesn't matter for leaf nodes

        x = section.getChunkX();
        y = section.getChunkY();
        z = section.getChunkZ();

        children = null;
        this.section = section;
        ownChildCount = 1;
    }

    public Octree(int ignoredBits, int x, int y, int z) {
        if (ignoredBits < 0 || ignoredBits > 32) {
            throw new IllegalArgumentException("ignoredBits must be between 0 and 32");
        }
        if (ignoredBits == 0) {
            throw new IllegalArgumentException(
                    "ignoredBits is only 0 for leaf nodes which must be constructed as such");
        }
        this.ignoredBits = ignoredBits;
        filter = ignoredBits == 32 ? 0 : -1 << ignoredBits;
        selector = 1 << (ignoredBits - 1);

        this.x = x & filter;
        this.y = y & filter;
        this.z = z & filter;

        children = new Octree[8];
        section = null;
    }

    public static Octree newRoot() {
        return new Octree(32, 0, 0, 0);
    }

    public void setParent(Octree parent, int indexInParent) {
        this.parent = parent;
        this.indexInParent = indexInParent;
    }

    public boolean contains(int x, int y, int z) {
        return (x & filter) == this.x
                && (y & filter) == this.y
                && (z & filter) == this.z;
    }

    public boolean contains(Octree tree) {
        return contains(tree.x, tree.y, tree.z);
    }

    private int getIndexFor(int x, int y, int z) {
        // TODO: branchless?
        return ((x & selector) == 0 ? 0 : 1)
                | ((y & selector) == 0 ? 0 : 1) << 1
                | ((z & selector) == 0 ? 0 : 1) << 2;
    }

    private int getIndexFor(Octree tree) {
        return getIndexFor(tree.x, tree.y, tree.z);
    }

    public void setSection(RenderSection toSet) {
        if (toSet == null) {
            return;
        }
        int x = toSet.getChunkX();
        int y = toSet.getChunkY();
        int z = toSet.getChunkZ();

        if (!contains(x, y, z)) {
            throw new IllegalArgumentException("Section " + toSet + " is not contained in " + this);
        }

        if (ignoredBits == 0) {
            section = toSet;
            ownChildCount = 1;
        } else {
            // find the index for the section
            int index = getIndexFor(x, y, z);
            Octree existingChild = children[index];

            // if there is already a child, add the section to it instead of directly
            if (existingChild != null) {
                existingChild.setSection(toSet);
            } else {
                // crewate new nested nodes until the section fits (reaches the correct level)
                Octree child = new Octree(toSet);
                while (child.ignoredBits + 1 != ignoredBits) {
                    Octree newParent = new Octree(child.ignoredBits + 1, child.x, child.y, child.z);
                    int indexInParent = newParent.getIndexFor(child);
                    newParent.children[indexInParent] = child;
                    child.setParent(newParent, indexInParent);
                    newParent.ownChildCount = 1;
                    child = newParent;
                }
                children[index] = child;
                child.setParent(this, index);
                ownChildCount++;
            }
        }
    }

    public void removeSection(RenderSection toRemove) {
        if (toRemove == null) {
            return;
        }
        int x = toRemove.getChunkX();
        int y = toRemove.getChunkY();
        int z = toRemove.getChunkZ();

        if (!contains(x, y, z)) {
            throw new IllegalArgumentException("Section " + toRemove + " is not contained in " + this);
        }

        if (ignoredBits == 0) {
            section = null;
            ownChildCount = 0;
        } else {
            // find the index for the section
            int index = getIndexFor(x, y, z);
            Octree child = children[index];

            // if this child does exist, remove the section from it
            if (child != null) {
                child.removeSection(toRemove);

                // and remove the child if it's now empty
                if (child.ownChildCount == 0) {
                    children[index] = null;
                    child.setParent(null, -1); // for safety, do we need this?
                    ownChildCount--;
                }
            }
        }
    }

    public Octree getSectionOctree(RenderSection toFind) {
        if (toFind == null) {
            return null;
        }
        int x = toFind.getChunkX();
        int y = toFind.getChunkY();
        int z = toFind.getChunkZ();

        if (!contains(x, y, z)) {
            return null;
        } else if (ignoredBits == 0) {
            return section == toFind ? this : null;
        } else {
            // find the index for the section
            int index = getIndexFor(x, y, z);
            Octree child = children[index];
            return child == null ? null : child.getSectionOctree(toFind);
        }
    }

    /**
     * Returns the octree node adjacent to the given face of this node of the same
     * size. The returned node may be sparse. Null is returned if there is no such
     * node either because there are no sections adjacent to the given face, or
     * because the given face is on the edge of entire octree.
     * 
     * @param axisIndex the axis index of the face (0 = x, 1 = y, 2 = z)
     * @param axisSign  the sign of the normal of the face
     * @return The same-size octree node adjacent to the given face of this node, or
     *         null if there is no such node
     */
    public Octree getFaceAdjacent(int axisIndex, int axisSign) {
        if (parent == null) {
            // nothing is adjacent to the root node
            return null;
        }

        // TODO: what happens in the case of the second-level octree? (parent's selector
        // is 1 << 31)
        int offset = axisSign > 0 ? parent.selector : -parent.selector;

        // compute the origin of the mirrored volume. Looking for an octree node of the
        // same size as this node with these coordinates as its origin will give the
        // correct result because if the octree with this origin was larger than
        // this octree node, it would intersect with this node which is impossible.
        // Furthermore, if it was smaller than this node, it would be contained within
        // another node that is the same size as this octree node. Thus such a node
        // exists and can be found with these coordinates.
        int targetX = x;
        int targetY = y;
        int targetZ = z;
        switch (axisIndex) {
            case 0:
                targetX += offset;
                break;
            case 1:
                targetY += offset;
                break;
            case 2:
                targetZ += offset;
                break;
        }

        // find the next parent that contains the volume of this octree but mirrored
        // along the face we're interested in
        Octree mirrorVolume = parent;
        while (!mirrorVolume.contains(targetX, targetY, targetZ)) {
            mirrorVolume = mirrorVolume.parent;
            if (mirrorVolume == null) {
                // the mirrored volume is outside of the world since not parent contains it
                return null;
            }
        }

        // step downwards until the node of exactly the mirrored volume is found or
        // there is no such node
        while (mirrorVolume.ignoredBits > ignoredBits) {
            int index = mirrorVolume.getIndexFor(targetX, targetY, targetZ);
            mirrorVolume = mirrorVolume.children[index];
            if (mirrorVolume == null) {
                // the mirrored volume is outside of the world since not parent contains it
                return null;
            }
        }

        return mirrorVolume;
    }

    public Octree getFaceAdjacent(Direction direction) {
        return getFaceAdjacent(DirectionUtil.getAxisIndex(direction), DirectionUtil.getAxisSign(direction));
    }

    private static final int[] FACE_INDICES = new int[] {
            0x00020406, 0x01030507, 0x00010405, 0x02030607, 0x00010203, 0x04050607 };

    // TODO: cache this result?
    // TODO: if not, make it easier to iterate render sections without allocating a
    // list. maybe pass in a Consumer<RenderSection>?
    private void getFaceSections(Collection<RenderSection> accumulator, int axisIndex, int axisSign) {
        if (ignoredBits == 0) {
            // this is a leaf node, return the section because it touches all faces
            accumulator.add(section);
            return;
        }

        // iterate over all children that touch the given face. They have indices in
        // which the bit at the axis index is equal to the axis sign
        // convert the axis sign into either 0 (if negative) or 1 (if positive)
        int indices = FACE_INDICES[(axisIndex << 1) + (axisSign > 0 ? 1 : 0)];
        for (int i = 0; i < 4; i++) {
            Octree child = children[indices & 0b111];
            if (child != null) {
                child.getFaceSections(accumulator, axisIndex, axisSign);
            }
            indices >>= 8;
        }
    }

    /**
     * Returns all sections contained within this octree node that are adjacent to
     * the given face. This traverses the octree recursively.
     * 
     * @param axisIndex the axis index of the face (0 = x, 1 = y, 2 = z)
     * @param axisSign  the sign of the normal of the face
     * @return a list of sections adjacent to the given face
     */
    public Collection<RenderSection> getFaceSections(int axisIndex, int axisSign) {
        Collection<RenderSection> accumulator = new ArrayList<>();
        getFaceSections(accumulator, axisIndex, axisSign);
        return accumulator;
    }

    public Collection<RenderSection> getFaceSections(Direction direction) {
        return getFaceSections(DirectionUtil.getAxisIndex(direction), DirectionUtil.getAxisSign(direction));
    }

    public Collection<RenderSection> getFaceAdjacentSections(int axisIndex, int axisSign) {
        Octree faceAdjacent = getFaceAdjacent(axisIndex, axisSign);
        if (faceAdjacent == null) {
            return Collections.emptyList();
        }
        return faceAdjacent.getFaceSections(axisIndex, -axisSign);
    }

    public Collection<RenderSection> getFaceAdjacentSections(Direction direction) {
        return getFaceAdjacentSections(DirectionUtil.getAxisIndex(direction), DirectionUtil.getAxisSign(direction));
    }
}
