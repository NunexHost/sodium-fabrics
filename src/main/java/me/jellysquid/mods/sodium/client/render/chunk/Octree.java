package me.jellysquid.mods.sodium.client.render.chunk;

import java.util.*;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.jellysquid.mods.sodium.common.util.DirectionUtil;
import net.minecraft.util.math.Direction;

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
 * See OctreeTest: There appears to be no problem handling transitions between
 * positive and negative numbers in testGetFaceAdjacent.
 * 
 * TODO: ray culling acceleration: keep track of all the fully opaque (or later,
 * using more complicated visibility traversal) nodes (like skippable counter)
 * to quickly check if a ray from a chunk to to a the camera position is
 * blocked. This could even improve bfs because it would actually occlude not
 * visible sections. A line is blocked by an octree node if it intersects it and
 * is fully opaque and not if it's fully not-opaque. If the node contains both
 * opaque and not-opaque sections, the node's children should be checked in the
 * same way. Problem: each of the section's corners has to be tested, the ones
 * that are within the outline of the hexagonal (or with fewer corners) frustum
 * the chunk forms with the camera don't have to be tested since they are
 * redundant. (figuring out which ones these are may be difficult, but it's
 * probably possible)
 * 
 * // TODO: it seems the bfs is still iterating too many sections. Is the face adjacent calculation somehow broken or is the bfs broken?
 * 
 * @author douira
 */
public class Octree {
    // either children or section is null, the other is not null
    public RenderSection section;
    public final Octree[] children;
    public int firstChildIndex = 0; // index of the first child that is not null
    public int ownChildCount = 0;

    public Octree parent; // null for the root node

    public final int ignoredBits;
    public final int filter;
    public final int selector;
    public final int size; // size of the node in sections
    public final int offset;
    public final int origX, origY, origZ;
    public final int x, y, z;
    // last coordinate at which any child may have its origin, inside the node
    public final int maxX, maxY, maxZ;

    // the newest frame in which this node was visible as the root of a subtree. If
    // a parent was visible in a later frame, this is outdated.
    private int lastVisibleFrame = -1;
    // the newest lastVisibleFrame of all children
    private int childLastVisibleFrame = -1;
    public int skippableChildren = 0; // skippable meaning containing only empty sections

    public Octree(RenderSection section, int offset) {
        Objects.requireNonNull(section);

        this.ignoredBits = 0;
        this.filter = -1;
        this.selector = 0; // doesn't matter for leaf nodes
        this.size = 1;
        this.offset = offset;

        this.origX = section.getChunkX();
        this.origY = section.getChunkY();
        this.origZ = section.getChunkZ();
        this.x = processCoordinate(this.origX);
        this.y = processCoordinate(this.origY);
        this.z = processCoordinate(this.origZ);
        this.maxX = this.x + this.size - 1;
        this.maxY = this.y + this.size - 1;
        this.maxZ = this.z + this.size - 1;

        this.children = null;
        this.section = section;
        this.ownChildCount = 1;
        section.octreeLeaf = this;
    }

    public Octree(int ignoredBits, int x, int y, int z, int offset) {
        this.ignoredBits = ignoredBits;
        if (ignoredBits < 0 || ignoredBits > 32) {
            throw new IllegalArgumentException("ignoredBits must be between 0 and 32");
        }
        if (isLeaf()) {
            throw new IllegalArgumentException(
                    "ignoredBits is only 0 for leaf nodes which must be constructed as such");
        }

        this.filter = ignoredBits == 32 ? 0 : -1 << ignoredBits; // in case there are 32 bits
        this.selector = 1 << (ignoredBits - 1);
        this.size = selector << 1; // same as parent.selector
        this.offset = offset;

        this.x = x & filter;
        this.y = y & filter;
        this.z = z & filter;
        this.origX = this.x - offset;
        this.origY = this.y - offset;
        this.origZ = this.z - offset;
        this.maxX = this.x + this.size - 1;
        this.maxY = this.y + this.size - 1;
        this.maxZ = this.z + this.size - 1;

        this.children = new Octree[8];
        this.section = null;
    }

    public static Octree newRoot() {
        // default root octree settings:
        // 22 bits for +/- 30 million blocks, -> 22 bits for sections of 16 blocks.
        // offset of 30_000_000 >> 4 = 1_875_000 to bring the coordinates into the
        // positive since the sign bit is the last but we're not looking at it with just
        // 22 bits of coordinates.
        return new Octree(22, 0, 0, 0, 30_000_000 >> 4);
    }

    public void setParent(Octree parent) {
        this.parent = parent;
    }

    public boolean contains(int x, int y, int z) {
        return (x & this.filter) == this.x
                && (y & this.filter) == this.y
                && (z & this.filter) == this.z;
    }

    public boolean contains(Octree tree) {
        return contains(tree.x, tree.y, tree.z);
    }

    public boolean isLeaf() {
        return this.ignoredBits == 0;
    }

    public boolean isSkippable() {
        return this.skippableChildren == this.ownChildCount;
    }

    private int processCoordinate(int coord) {
        return coord + this.offset;
    }

    public static int manhattanDistance(Octree a, Octree b) {
        return Math.abs(a.x + (a.size >> 1) - b.x - (b.size >> 1))
                + Math.abs(a.y + (a.size >> 1) - b.y - (b.size >> 1))
                + Math.abs(a.z + (a.size >> 1) - b.z - (b.size >> 1));
    }

    public boolean isWithinDistance(int distance, int centerX, int centerZ) {
        centerX = processCoordinate(centerX);
        centerZ = processCoordinate(centerZ);
        int xMin = Math.abs(this.x - centerX);
        int xMax = Math.abs(this.maxX - centerX);
        int zMin = Math.abs(this.z - centerZ);
        int zMax = Math.abs(this.maxZ - centerZ);

        return (xMin <= distance || xMax <= distance)
                && (zMin <= distance || zMax <= distance);
    }

    private int getIndexFor(int x, int y, int z) {
        // TODO: branchless?
        return ((x & this.selector) == 0 ? 0 : 1)
                | ((y & this.selector) == 0 ? 0 : 1) << 1
                | ((z & this.selector) == 0 ? 0 : 1) << 2;
    }

    private int getIndexFor(Octree tree) {
        return getIndexFor(tree.x, tree.y, tree.z);
    }

    public void setSection(RenderSection toSet) {
        if (toSet == null) {
            return;
        }
        int rsX = processCoordinate(toSet.getChunkX());
        int rsY = processCoordinate(toSet.getChunkY());
        int rsZ = processCoordinate(toSet.getChunkZ());

        if (!contains(rsX, rsY, rsZ)) {
            throw new IllegalArgumentException("Section " + toSet + " is not contained in " + this);
        }

        if (isLeaf()) {
            this.section = toSet;
            this.ownChildCount = 1;
            toSet.octreeLeaf = this;
            updateSectionSkippable();
        } else {
            // find the index for the section
            int index = getIndexFor(rsX, rsY, rsZ);
            Octree existingChild = children[index];

            /**
             * 1. identify the child the new node has to be put in. if there is none, it can
             * just go there.
             * 2. with the existing child, check if the new leaf is contained within it. if
             * it is, recurse into that node and add it there.
             * 3. if it isn't contained within the existing child, find the lowest level
             * that can contain both the existing node and the new leaf.
             * 4. create this new branch node and insert it in the place of the existing
             * child.
             * 5. add the existing child and the new leaf to the new branch node. (make sure
             * to set parent pointers correctly)
             */

            // if there is already a child, check if it can contain the new section
            if (existingChild != null) {
                if (existingChild.contains(rsX, rsY, rsZ)) {
                    // recurse into the existing child
                    existingChild.setSection(toSet);
                    return;
                } else {
                    // the existing child is skipping some intermediary nodes, it's replaced with a
                    // branching node that contains both it and a new leaf for the new section
                    Octree leaf = new Octree(toSet, this.offset);

                    // find the number of ignored bits at which the leaf and the existing child are
                    // both contained.
                    // it will be lower than the own ignored bits of this node
                    int branchIgnoredBits = this.ignoredBits - 1;
                    while (true) {
                        int branchFilter = -1 << branchIgnoredBits;
                        if ((rsX & branchFilter) != (existingChild.x & branchFilter)
                                || (rsY & branchFilter) != (existingChild.y & branchFilter)
                                || (rsZ & branchFilter) != (existingChild.z & branchFilter)) {
                            break;
                        }
                        branchIgnoredBits--;
                    }

                    // adjust the ignored bits for the branch node back up since once the level at
                    // which two nodes differ is found, the branch node is a level higher so that it
                    // can contain both nodes
                    branchIgnoredBits++;

                    // creating the new branch will generate the right origin coordinates
                    Octree branch = new Octree(branchIgnoredBits, rsX, rsY, rsZ, this.offset);
                    this.children[index] = branch;
                    branch.setParent(this);

                    // add the existing child and the new leaf to the new branch
                    int existingInBranchIndex = branch.getIndexFor(existingChild);
                    branch.children[existingInBranchIndex] = existingChild;
                    existingChild.setParent(branch);
                    int newInBranchIndex = branch.getIndexFor(leaf);
                    branch.children[newInBranchIndex] = leaf;
                    leaf.setParent(branch);
                    branch.ownChildCount = 2;
                    if (existingInBranchIndex == newInBranchIndex) {
                        throw new IllegalStateException("Two children with the same index in a branch node");
                    }
                    branch.firstChildIndex = Math.min(existingInBranchIndex, newInBranchIndex);

                    // copy skippable to the branch node, then propagate the leaf to update it
                    branch.skippableChildren = this.skippableChildren;
                    // TODO: just switch to full visibility data merging instead.
                    leaf.updateSectionSkippable();

                    // TODO: why does the root node sometimes only have a single child?
                }
            } else {
                // there is no existing child, so we can just add the new leaf to this node
                // directly
                Octree leaf = new Octree(toSet, this.offset);
                this.children[index] = leaf;
                leaf.setParent(this);
                this.ownChildCount++;
                leaf.updateSectionSkippable();

                // move the first child index down if necessary,
                // only expand the first child index downards, upwards makes no difference
                if (index < this.firstChildIndex) {
                    this.firstChildIndex = index;
                }
            }
        }
    }

    public void removeSection(RenderSection toRemove) {
        if (toRemove == null) {
            return;
        }
        int rsX = processCoordinate(toRemove.getChunkX());
        int rsY = processCoordinate(toRemove.getChunkY());
        int rsZ = processCoordinate(toRemove.getChunkZ());

        if (!contains(rsX, rsY, rsZ)) {
            throw new IllegalArgumentException("Section " + toRemove + " is not contained in " + this);
        }

        if (isLeaf()) {
            setLeafSkippable(false); // false because of removal
            this.section = null;
            this.ownChildCount = 0;
            toRemove.octreeLeaf = null;
        } else {
            // find the index for the section
            int index = getIndexFor(rsX, rsY, rsZ);
            Octree child = this.children[index];

            // if this child does exist, remove the section from it
            if (child != null) {
                child.removeSection(toRemove);

                // if the child is a singleton, remove it and replace it with its only child
                if (child.ownChildCount == 1) {
                    Octree onlySubchild = child.children[child.firstChildIndex];
                    this.children[index] = onlySubchild;
                    onlySubchild.setParent(this);

                    // don't need to update skippable state here, the state of child and subchild is
                    // the same
                }

                // remove the child if it is now empty
                else if (child.ownChildCount == 0) {
                    // when this reaches zero the caller can remove this node
                    this.children[index] = null;
                    this.ownChildCount--;

                    // find the new first child if it was pointing to the removed child
                    if (this.ownChildCount > 0 && index == this.firstChildIndex) {
                        firstChildIndex++;

                        // increment until we find a non-null child, if there is no child the
                        // firstChildIndex will be 8 but this node is deleted anyways by the parent so
                        // it doesn't matter
                        while (this.firstChildIndex < 8 && this.children[this.firstChildIndex] == null) {
                            this.firstChildIndex++;
                        }
                    }
                }
            }
        }
    }

    public void updateSectionSkippable() {
        setLeafSkippable(this.section.hasEmptyData());
    }

    void setLeafSkippable(boolean skippable) {
        if (ignoredBits != 0) {
            throw new IllegalStateException(
                    "Skippable status of a non-leaf should be changed with changeSkippableCount");
        }

        // check if there was any change in skippable status and increment/decrement the
        // parent if necessary
        int newSkippableChildren = skippable ? 1 : 0;
        if (this.skippableChildren != newSkippableChildren) {
            this.skippableChildren = newSkippableChildren;
            if (this.parent != null) {
                this.parent.changeSkippableCount(skippable ? 1 : -1);
            }
        }
    }

    private void changeSkippableCount(int change) {
        if (isLeaf()) {
            throw new IllegalStateException("Skippable status of a leaf should be changed with setLeafSkippable");
        }

        // decrement or increment skippable count
        this.skippableChildren += change;

        // send increment or decrement to parent if the skippable status changed
        if (this.parent != null && (change == 1
                ? this.skippableChildren == this.ownChildCount
                : this.skippableChildren + 1 == this.ownChildCount)) {
            this.parent.changeSkippableCount(change);
        }
    }

    public void setLastVisibleFrame(int frame) {
        // the two are separate because the real lastVisible frame is only updated if
        // the node itself or a parent (parent unimplemented for now) was visited
        this.lastVisibleFrame = frame;
        Octree node = this;
        while (node != null && node.childLastVisibleFrame != frame) {
            node.childLastVisibleFrame = frame;
            node = node.parent;
        }
    }

    /**
     * Checks if this node or any of the parents were visible in the given frame. It
     * has to check the parents because parents don't set lastVisibleFrame on all
     * children (too much effort).
     */
    public boolean getSelfVisibleInFrame(int frame) {
        return this.stepSelfVisibleInFrame(frame) == frame;
    }

    private int stepSelfVisibleInFrame(int frame) {
        if (this.lastVisibleFrame == frame) {
            return frame;
        }
        if (this.parent != null) {
            int result = this.parent.stepSelfVisibleInFrame(frame);
            if (result == frame) {
                this.lastVisibleFrame = frame;
            }
            return result;
        }
        return this.lastVisibleFrame;
    }

    public boolean intersectsBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        // even if the box is inclusive of both the min and max, xMax is still inside
        // the octree so it can be included
        return this.x <= maxX && this.maxX >= minX
                && this.y <= maxY && this.maxY >= minY
                && this.z <= maxZ && this.maxZ >= minZ;
    }

    /**
     * Checks if a box was visible in the given frame. It looks for a child
     * intersecting with the box of which the own or a parent's last visible frame
     * matches the given frame.
     */
    public boolean isBoxVisible(int frame, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        // childLastVisibleFrame and lastVisibleFrame are the same for leaf nodes
        boolean matchesChildLastVisibleFrame = this.childLastVisibleFrame == frame;
        boolean intersectsBox = intersectsBox(minX, minY, minZ, maxX, maxY, maxZ);

        // a leaf node is visible if it was visited in the given frame and intersects
        // with the box
        if (isLeaf()) {
            return matchesChildLastVisibleFrame && intersectsBox;
        }

        // skip if this frame doesn't appear as a visible frame in any of the children
        // or if the box doesn't even intersect this node
        if (!matchesChildLastVisibleFrame || !intersectsBox) {
            return false;
        }

        // check if the box is visible in any of the children as the box may not
        // intersect any actual render section, even if it does intersect this node,
        // parts of which may be empty.
        for (int i = this.firstChildIndex, childCount = this.ownChildCount; i < 8 && childCount > 0; i++) {
            Octree child = this.children[i];
            if (child != null) {
                if (child.isBoxVisible(frame, minX, minY, minZ, maxX, maxY, maxZ)) {
                    return true;
                }
                childCount--;
            }
        }
        return false;
    }

    public Octree getSectionOctree(RenderSection toFind) {
        if (toFind == null) {
            return null;
        }
        int rsX = processCoordinate(toFind.getChunkX());
        int rsY = processCoordinate(toFind.getChunkY());
        int rsZ = processCoordinate(toFind.getChunkZ());

        if (!contains(rsX, rsY, rsZ)) {
            return null;
        } else if (isLeaf()) {
            return this.section == toFind ? this : null;
        } else {
            // find the index for the section
            int index = getIndexFor(rsX, rsY, rsZ);
            Octree child = this.children[index];
            return child == null ? null : child.getSectionOctree(toFind);
        }
    }

    public void iterateWholeTree(Consumer<Octree> consumer) {
        if (isLeaf()) {
            consumer.accept(this);
        } else {
            for (int i = this.firstChildIndex, childCount = this.ownChildCount; i < 8 && childCount > 0; i++) {
                Octree child = this.children[i];
                if (child != null) {
                    child.iterateWholeTree(consumer);
                    childCount--;
                }
            }
        }
    }

    public void iterateUnskippableTree(Consumer<Octree> consumer) {
        if (isSkippable()) {
            return;
        }
        if (isLeaf()) {
            consumer.accept(this);
        } else {
            for (int i = this.firstChildIndex, childCount = this.ownChildCount - this.skippableChildren; i < 8
                    && childCount > 0; i++) {
                Octree child = this.children[i];
                if (child != null && !child.isSkippable()) {
                    child.iterateUnskippableTree(consumer);
                    childCount--;
                }
            }
        }
    }

    /**
     * Returns the octree node adjacent to the given face of this node of the same
     * size. The returned node may be sparse. Null is returned if there is no such
     * node either because there are no sections adjacent to the given face, or
     * because the given face is on the edge of entire octree. Never returns a node
     * that contains this node or a smaller node.
     * 
     * @param axisIndex        the axis index of the face (0 = x, 1 = y, 2 = z)
     * @param axisSign         the sign of the normal of the face
     * @param sameSize         whether to return a node of the same size as this
     *                         node or also allow returning a node of a larger size.
     * @param largestSkippable whether to return a skippable node even if it is
     *                         larger than this node
     * @return The same-size octree node adjacent to the given face of this node, or
     *         null if there is no such node
     */
    public Octree getFaceAdjacent(int axisIndex, int axisSign, boolean sameSize, boolean largestSkippable) {
        if (this.parent == null) {
            // nothing is adjacent to the root node
            return null;
        }

        int offset = axisSign > 0 ? this.size : -this.size;

        // compute the origin of the mirrored volume. Looking for an octree node of the
        // same size as this node with these coordinates as its origin will give the
        // correct result because if the octree with this origin was larger than
        // this octree node, it would intersect with this node which is impossible.
        // Furthermore, if it was smaller than this node, it would be contained within
        // another node that is the same size as this octree node. Thus such a node
        // exists and can be found with these coordinates.
        int targetX = this.x;
        int targetY = this.y;
        int targetZ = this.z;
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

        // get the adjacent common parent of this node and the mirrored volume
        Octree mirrorVolume = getAdjacentCommonParent(targetX, targetY, targetZ);
        if (mirrorVolume == null) {
            // there is no adjacent volume
            return null;
        }

        // step downwards until the node of exactly the mirrored volume is found or
        // there is no such node
        while (mirrorVolume.ignoredBits > this.ignoredBits) {
            int index = mirrorVolume.getIndexFor(targetX, targetY, targetZ);
            Octree child = mirrorVolume.children[index];
            if (child == null) {
                // check if the mirror volume contains this node, only happens in case there are
                // zero downards steps until the child is null. If the mirror volume does
                // contain this node, it must be the same as the adjacent common parent from
                // above and we aren't interested in that.
                if (sameSize || mirrorVolume.contains(this)) {
                    return null;
                } else {
                    break;
                }
            } else {
                mirrorVolume = child;

                // return early if we found a skippable node and we're allowed to do so
                if (largestSkippable && mirrorVolume.isSkippable()) {
                    break;
                }
            }
        }

        return mirrorVolume;
    }

    /**
     * Returns the octree node that is the common parent of this node and the node
     * specified by the given coordinates.
     * 
     * @param targetX the x coordinate of the target node
     * @param targetY the y coordinate of the target node
     * @param targetZ the z coordinate of the target node
     * @return The common parent of this node and the specified node, or null if
     *         there is none
     */
    public Octree getAdjacentCommonParent(int targetX, int targetY, int targetZ) {
        if (this.parent == null) {
            // nothing is adjacent to the root node
            return null;
        }

        // find the next parent that contains the volume of this octree but mirrored
        // along the face we're interested in
        Octree mirrorVolume = this.parent;
        while (!mirrorVolume.contains(targetX, targetY, targetZ)) {
            mirrorVolume = mirrorVolume.parent;
            if (mirrorVolume == null) {
                // the mirrored volume is outside of the world since not parent contains it
                return null;
            }
        }
        return mirrorVolume;
    }

    public Octree getAdjacentCommonParent(int axisIndex, int axisSign) {
        int nudge = axisSign > 0 ? 1 : -1;
        int targetX = this.x;
        int targetY = this.y;
        int targetZ = this.z;
        switch (axisIndex) {
            case 0:
                targetX += nudge;
                break;
            case 1:
                targetY += nudge;
                break;
            case 2:
                targetZ += nudge;
                break;
        }
        return getAdjacentCommonParent(targetX, targetY, targetZ);
    }

    public Octree getFaceAdjacent(Direction direction, boolean sameSize, boolean largestSkippable) {
        return getFaceAdjacent(DirectionUtil.getAxisIndex(direction), DirectionUtil.getAxisSign(direction), sameSize,
                largestSkippable);
    }

    // indexes for each of the following 4-item bit masks (1 each direction)
    // 10101010, 01010101,
    // 11001100, 00110011,
    // 11110000, 00001111,
    private static final int[] FACE_INDICES = new int[] {
            0x00020406, 0x01030507, 0x00010405, 0x02030607, 0x00010203, 0x04050607 };

    /**
     * Iterates over all octree leaf nodes that touch the given face of this node.
     *
     * @param accumulator     the consumer that will be called for each leaf node
     * @param axisIndex       the axis index of the face
     * @param axisSign        the axis sign of the face
     * @param acceptSkippable whether to consume skippable non-leaf nodes and don't
     *                        consider their children
     */
    public void iterateFaceNodes(Consumer<Octree> accumulator, int axisIndex, int axisSign, boolean acceptSkippable) {
        if (isLeaf() || acceptSkippable && isSkippable()) {
            // this is a leaf node, return the section because it touches all faces
            accumulator.accept(this);
            return;
        }

        // iterate over all children that touch the given face. They have indices in
        // which the bit at the axis index is equal to the axis sign
        // convert the axis sign into either 0 (if negative) or 1 (if positive)
        int indices = FACE_INDICES[(axisIndex << 1) + (axisSign > 0 ? 1 : 0)];
        for (int i = 0; i < 4; i++, indices >>= 8) {
            Octree child = this.children[indices & 0b111];
            if (child != null) {
                // if the child is not an immediate child, check that it actually touches the
                // face
                if (child.ignoredBits + 1 < this.ignoredBits) {
                    switch (axisIndex) {
                        case 0:
                            if (axisSign > 0 ? child.x + child.size != this.x + this.size : child.x != this.x) {
                                continue;
                            }
                            break;
                        case 1:
                            if (axisSign > 0 ? child.y + child.size != this.y + this.size : child.y != this.y) {
                                continue;
                            }
                            break;
                        case 2:
                            if (axisSign > 0 ? child.z + child.size != this.z + this.size : child.z != this.z) {
                                continue;
                            }
                            break;
                    }
                }
                child.iterateFaceNodes(accumulator, axisIndex, axisSign, acceptSkippable);
            }
        }
    }

    public void iterateFaceNodes(Consumer<Octree> accumulator, Direction direction, boolean acceptSkippable) {
        iterateFaceNodes(accumulator,
                DirectionUtil.getAxisIndex(direction), DirectionUtil.getAxisSign(direction),
                acceptSkippable);
    }

    public void iterateFaceAdjacentNodes(Consumer<Octree> accumulator, Direction direction, boolean acceptSkippable) {
        int axisIndex = DirectionUtil.getAxisIndex(direction);
        int axisSign = DirectionUtil.getAxisSign(direction);
        Octree faceAdjacent = getFaceAdjacent(axisIndex, axisSign, true, acceptSkippable);
        if (faceAdjacent != null) {
            faceAdjacent.iterateFaceNodes(accumulator, axisIndex, -axisSign, acceptSkippable);
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
    public Collection<Octree> getFaceNodes(int axisIndex, int axisSign, boolean acceptSkippable) {
        Collection<Octree> accumulator = new ObjectArrayList<>();
        iterateFaceNodes(accumulator::add, axisIndex, axisSign, acceptSkippable);
        return accumulator;
    }

    public Collection<Octree> getFaceNodes(Direction direction, boolean acceptSkippable) {
        Collection<Octree> accumulator = new ObjectArrayList<>();
        iterateFaceNodes(accumulator::add, direction, acceptSkippable);
        return accumulator;
    }

    public Collection<Octree> getFaceAdjacentNodes(int axisIndex, int axisSign, boolean acceptSkippable) {
        Octree faceAdjacent = getFaceAdjacent(axisIndex, axisSign, true, false);
        if (faceAdjacent == null) {
            return Collections.emptyList();
        }
        return faceAdjacent.getFaceNodes(axisIndex, -axisSign, acceptSkippable);
    }

    public Collection<Octree> getFaceAdjacentNodes(Direction direction, boolean acceptSkippable) {
        return getFaceAdjacentNodes(
                DirectionUtil.getAxisIndex(direction), DirectionUtil.getAxisSign(direction), acceptSkippable);
    }
}
