package me.jellysquid.mods.sodium.client.render.chunk.graph;

import java.util.*;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
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
 * // TODO: it seems the bfs is still iterating too many sections. Is the face
 * adjacent calculation somehow broken or is the bfs broken?
 * 
 * @author douira
 */
public abstract class Octree {
    public InnerNode parent; // null for the root node

    // the offset applied to the internal coordinates to get a compacter tree,
    // avoiding top-level branches that occur when the real coordinates are around
    // the origin
    public final int offset;

    // size of the node in sections
    public final int size;

    // origin of the node in sections, with offset applied
    public final int internalX, internalY, internalZ;

    // the oldest lastVisibleFrame of all children including this node
    int lowerVisibleFrameBound = -1;

    Octree(int offset, int size, int internalX, int internalY, int internalZ) {
        this.offset = offset;
        this.size = size;
        this.internalX = internalX;
        this.internalY = internalY;
        this.internalZ = internalZ;
    }

    Octree(int offset, int size, int filter, int internalX, int internalY, int internalZ) {
        this(offset, size, internalX & filter, internalY & filter, internalZ & filter);
    }

    public static InnerNode newRoot() {
        // default root octree settings:
        // 22 bits for +/- 30 million blocks, -> 22 bits for sections of 16 blocks.
        // offset of 30_000_000 >> 4 = 1_875_000 to bring the coordinates into the
        // positive since the sign bit is the last but we're not looking at it with just
        // 22 bits of coordinates.
        InnerNode root = new InnerNode(30_000_000 >> 4, 22, 0, 0, 0);
        // root.contained = new HashSet<>();
        return root;
    }

    public int getSectionX() {
        return this.internalX - this.offset;
    }

    public int getSectionY() {
        return this.internalY - this.offset;
    }

    public int getSectionZ() {
        return this.internalZ - this.offset;
    }

    public int getSectionMaxX() {
        return this.getSectionX() + this.size;
    }

    public int getSectionMaxY() {
        return this.getSectionY() + this.size;
    }

    public int getSectionMaxZ() {
        return this.getSectionZ() + this.size;
    }

    public int getBlockX() {
        return this.getSectionX() << 4;
    }

    public int getBlockY() {
        return this.getSectionY() << 4;
    }

    public int getBlockZ() {
        return this.getSectionZ() << 4;
    }

    public int getBlockMaxX() {
        return this.getSectionMaxX() << 4;
    }

    public int getBlockMaxY() {
        return this.getSectionMaxY() << 4;
    }

    public int getBlockMaxZ() {
        return this.getSectionMaxZ() << 4;
    }

    /**
     * Checks if the given coordinates in internal section-space are inside this
     * node.
     */
    public abstract boolean contains(int internalX, int internalY, int internalZ);

    public abstract boolean isLeaf();

    abstract InnerNode asInnerNode();

    abstract LeafNode asLeafNode();

    public abstract boolean isSkippable();

    public abstract void iterateWholeTree(Consumer<LeafNode> consumer);

    public abstract void iterateUnskippableTree(Consumer<LeafNode> consumer);

    /**
     * Checks if a box was visible in the given frame. It looks for a child
     * intersecting with the box of which the own or a parent's last visible frame
     * matches the given frame.
     * 
     * Uses real section-space coordinates.
     */
    public abstract boolean isBoxVisible(int frame, int minX, int minY, int minZ, int maxX, int maxY, int maxZ);

    abstract LeafNode getSectionOctree(RenderSection toFind);

    public abstract void iterateFaceNodes(Consumer<Octree> accumulator, int axisIndex, int axisSign,
            boolean acceptSkippable);

    public abstract int getEffectiveIgnoredBits();

    public void setParent(InnerNode parent) {
        this.parent = parent;
    }

    public boolean contains(Octree tree) {
        return contains(tree.internalX, tree.internalY, tree.internalZ);
    }

    public static int manhattanDistance(Octree a, Octree b) {
        return Math.abs((a.internalX + a.size / 2) - (b.internalX + b.size / 2))
                + Math.abs((a.internalY + a.size / 2) - (b.internalY + b.size / 2))
                + Math.abs((a.internalZ + a.size / 2) - (b.internalZ + b.size / 2));
    }

    public boolean isWithinDistance(int distance, int centerX, int centerZ) {
        return (Math.abs(this.getSectionX() - centerX) <= distance
                || Math.abs(this.getSectionMaxX() - centerX) <= distance)
                && (Math.abs(this.getSectionZ() - centerZ) <= distance
                        || Math.abs(this.getSectionMaxZ() - centerZ) <= distance);
    }

    /**
     * Marks a whole subtree as visible at the given frame. Also sets the upper
     * bound on all parents but not the lower bound on all children. Instead, the
     * getAncestorsLowerBound method will automatically increase the lower bound on
     * the way downards from a node with a lower bound. Lower bounds are propagated
     * downwards since knowing a lower bound of a parent implies that all children
     * must have the same lower bound. Inversely, an upper bound is propagated
     * downwards, since if a child has a higher upper bound, the parent must have
     * the same higher upper bound.
     */
    public void setSubtreeVisibleNow(int frame) {
        this.lowerVisibleFrameBound = frame;
        if (this.parent != null) {
            this.parent.setChildVisibleNow(frame);
        }
    }

    /**
     * A node is visible at the given frame if the lower visible frame bound of the
     * node itself or that of any parent is at least this frame. Updates the lower
     * visible frame bound on all parents to the given frame if a parent with the
     * given frame as its lower visible frame bound is found.
     */
    public boolean isWholeSubtreeVisibleAt(int frame) {
        return this.getAncestorsLowerBound(frame) == frame;
    }

    int getAncestorsLowerBound(int frame) {
        if (this.lowerVisibleFrameBound == frame) {
            return this.lowerVisibleFrameBound;
        }
        if (this.parent != null) {
            int result = this.parent.getAncestorsLowerBound(frame);
            if (result == frame) {
                this.lowerVisibleFrameBound = frame;
            }
            return result;
        }
        return this.lowerVisibleFrameBound;
    }

    public boolean intersectsBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        // even if the box is inclusive of both the min and max, but the max coordinates
        // don't contain any of the node's space so they are compared with >
        return this.getSectionX() <= maxX && this.getSectionMaxX() > minX
                && this.getSectionY() <= maxY && this.getSectionMaxY() > minY
                && this.getSectionZ() <= maxZ && this.getSectionMaxZ() > minZ;
    }

    /**
     * Does a recursive check if the ray from the center of the node to the camera
     * is occluded by an entirely non-skippable node.
     */
    // public boolean isOccluded(float cameraX, float cameraY, float cameraZ) {
    // }

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
        int targetX = this.internalX;
        int targetY = this.internalY;
        int targetZ = this.internalZ;
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
        InnerNode mirrorVolume = getAdjacentCommonParent(targetX, targetY, targetZ);
        if (mirrorVolume == null) {
            // there is no adjacent volume
            return null;
        }

        // step downwards until the node of exactly the mirrored volume is found or
        // there is no such node
        while (mirrorVolume.ignoredBits > this.getEffectiveIgnoredBits()) {
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
            } else if (child instanceof InnerNode innerNodeChild) {
                mirrorVolume = innerNodeChild;

                // return early if we found a skippable node and we're allowed to do so
                if (largestSkippable && mirrorVolume.isSkippable()) {
                    break;
                }
            } else {
                // we found a leaf node, return it. the loop will exit anyway
                return child;
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
    public InnerNode getAdjacentCommonParent(int targetX, int targetY, int targetZ) {
        if (this.parent == null) {
            // nothing is adjacent to the root node
            return null;
        }

        // find the next parent that contains the volume of this octree but mirrored
        // along the face we're interested in
        InnerNode mirrorVolume = this.parent;
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
        int targetX = this.internalX;
        int targetY = this.internalY;
        int targetZ = this.internalZ;
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
