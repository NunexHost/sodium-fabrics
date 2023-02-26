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
 * TODO: acceleration structure to get from parent to children faster:
 * references to the highest/largest/closest child that has more than 1 child
 * anywhere in the subtree. Idea: since iterating over all children would be a
 * lot and doing it dynamically is also a lot of overhead because having a
 * packpointer would mean a collection at the pointed-to child, limiting when
 * and how often a downwards pointer is generated could make it still useful.
 * Before doing BFS, an "accelerate pointer now" function could be called that
 * does up to 4*32 iterations (more are likely unnecessary since this is mostly
 * just to skip the large singleton chain near the root or its direct children)
 * of generating down-links to the next relevant child. Problem: when sections
 * are added or removed, would these links being wrong cause problems for the
 * addition or removal process itself?
 * 
 * TODO: acceleration structure of indexing the own children array with a
 * separate index array or fields that contain indices for when there are very
 * few children (1 or 2, or more?) and they are always at a higher position.
 * This would avoid iterating more children than necessary when there are only
 * few children. Idea: just storing an index at which to start iterating (with a
 * completion counter that counts down ownChildCount) would maybe already be
 * effective. (do profiling to see if it's necessary)
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
    public final int size; // size of the node in sections
    public final int x, y, z;
    // last coordinate at which any child may have its origin, inside the node
    public final int maxX, maxY, maxZ;

    // the newest frame in which this node was visible as the root of a subtree. If
    // a parent was visible in a later frame, this is outdated.
    private int lastVisibleFrame = -1;
    // the newest lastVisibleFrame of all children
    private int childLastVisibleFrame = -1;
    public int skippableChildren = 0; // skippable meaning containing only empty sections

    public Octree(RenderSection section) {
        Objects.requireNonNull(section);

        this.ignoredBits = 0;
        this.filter = -1;
        this.selector = 0; // doesn't matter for leaf nodes
        this.size = 1;

        this.x = section.getChunkX();
        this.y = section.getChunkY();
        this.z = section.getChunkZ();
        this.maxX = this.x + this.size - 1;
        this.maxY = this.y + this.size - 1;
        this.maxZ = this.z + this.size - 1;

        this.children = null;
        this.section = section;
        this.ownChildCount = 1;
        section.octreeLeaf = this;
    }

    public Octree(int ignoredBits, int x, int y, int z) {
        this.ignoredBits = ignoredBits;
        if (ignoredBits < 0 || ignoredBits > 32) {
            throw new IllegalArgumentException("ignoredBits must be between 0 and 32");
        }
        if (isLeaf()) {
            throw new IllegalArgumentException(
                    "ignoredBits is only 0 for leaf nodes which must be constructed as such");
        }

        this.filter = ignoredBits == 32 ? 0 : -1 << ignoredBits;
        this.selector = 1 << (ignoredBits - 1);
        this.size = selector << 1; // same as parent.selector

        this.x = x & filter;
        this.y = y & filter;
        this.z = z & filter;
        this.maxX = this.x + this.size - 1;
        this.maxY = this.y + this.size - 1;
        this.maxZ = this.z + this.size - 1;

        this.children = new Octree[8];
        this.section = null;
    }

    public static Octree newRoot() {
        return new Octree(32, 0, 0, 0);
    }

    public void setParent(Octree parent, int indexInParent) {
        this.parent = parent;
        this.indexInParent = indexInParent;
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

    public static int manhattanDistance(Octree a, Octree b) {
        return Math.abs(a.x + (a.size >> 1) - b.x - (b.size >> 1))
                + Math.abs(a.y + (a.size >> 1) - b.y - (b.size >> 1))
                + Math.abs(a.z + (a.size >> 1) - b.z - (b.size >> 1));
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
        int rsX = toSet.getChunkX();
        int rsY = toSet.getChunkY();
        int rsZ = toSet.getChunkZ();

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

            // if there is already a child, add the section to it instead of directly
            if (existingChild != null) {
                existingChild.setSection(toSet);
            } else {
                // crewate new nested nodes until the section fits (reaches the correct level)
                Octree leaf = new Octree(toSet);
                Octree child = leaf;
                while (child.ignoredBits + 1 != this.ignoredBits) {
                    Octree newParent = new Octree(child.ignoredBits + 1, child.x, child.y, child.z);
                    int indexInParent = newParent.getIndexFor(child);
                    newParent.children[indexInParent] = child;
                    child.setParent(newParent, indexInParent);
                    newParent.ownChildCount = 1;
                    child = newParent;
                }
                this.children[index] = child;
                child.setParent(this, index);
                this.ownChildCount++;
                leaf.updateSectionSkippable();
            }
        }
    }

    public void removeSection(RenderSection toRemove) {
        if (toRemove == null) {
            return;
        }
        int rsX = toRemove.getChunkX();
        int rsY = toRemove.getChunkY();
        int rsZ = toRemove.getChunkZ();

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

                // and remove the child if it's now empty
                if (child.ownChildCount == 0) {
                    this.children[index] = null;
                    child.setParent(null, -1); // for safety, do we need this?
                    this.ownChildCount--;
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
        this.setLastVisibleFrameFromChild(frame);
    }

    private void setLastVisibleFrameFromChild(int frame) {
        // if not the same, set and also tell the parent.
        // will usually make less then even log n steps since most children will be near
        // eachother when they are marked, and they are all marked with the same frame
        // number
        if (this.childLastVisibleFrame != frame) {
            this.childLastVisibleFrame = frame;
            if (this.parent != null) {
                this.parent.setLastVisibleFrameFromChild(frame);
            }
        }
    }

    /**
     * Checks if this node or any of the parents were visible in the given frame. It
     * has to check the parents because parents don't set lastVisibleFrame on all
     * children (too much effort).
     */
    public boolean getSelfVisibleInFrame(int frame) {
        if (this.lastVisibleFrame == frame) {
            return true;
        }
        if (this.parent != null) {
            return this.parent.getSelfVisibleInFrame(frame);
        }
        return false;
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
        int childCount = this.ownChildCount;
        for (Octree child : this.children) {
            if (child != null && child.isBoxVisible(frame, minX, minY, minZ, maxX, maxY, maxZ)) {
                return true;
            }
            if (--childCount == 0) {
                break;
            }
        }
        return false;
    }

    public Octree getSectionOctree(RenderSection toFind) {
        if (toFind == null) {
            return null;
        }
        int rsX = toFind.getChunkX();
        int rsY = toFind.getChunkY();
        int rsZ = toFind.getChunkZ();

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
            int childCount = this.ownChildCount;
            for (Octree child : this.children) {
                if (child != null) {
                    child.iterateWholeTree(consumer);
                    if (--childCount == 0) {
                        break;
                    }
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
            int childCount = this.ownChildCount - this.skippableChildren;
            for (Octree child : this.children) {
                if (child != null && !child.isSkippable()) {
                    child.iterateUnskippableTree(consumer);
                    if (--childCount == 0) {
                        break;
                    }
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

        // TODO: what happens in the case of the second-level octree? (parent's selector
        // is 1 << 31)
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
        int nudge = axisSign > 0 ? 1 : -1; // only 1 needed for common parent
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
        for (int i = 0; i < 4; i++) {
            Octree child = this.children[indices & 0b111];
            if (child != null) {
                child.iterateFaceNodes(accumulator, axisIndex, axisSign, acceptSkippable);
            }
            indices >>= 8;
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
