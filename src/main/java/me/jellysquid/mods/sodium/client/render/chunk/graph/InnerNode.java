package me.jellysquid.mods.sodium.client.render.chunk.graph;

import java.util.*;
import java.util.function.Consumer;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;

public class InnerNode extends Octree {
    public final Octree[] children = new Octree[8];
    public int firstChildIndex = 0; // index of the first child that is not null
    public int ownChildCount = 0;

    public final int ignoredBits;
    public final int filter;
    public final int selector;
    // public Set<String> contained; // TODO: remove

    // the newest lastVisibleFrame of all children including this node
    private int upperVisibleFrameBound = -1;

    // skippable meaning containing only empty sections
    public int skippableChildren = 0;

    public InnerNode(int offset, int ignoredBits, int x, int y, int z) {
        // size is same as parent.selector
        super(offset, 1 << ignoredBits, ignoredBits == 32 ? 0 : -1 << ignoredBits, x, y, z);

        this.ignoredBits = ignoredBits;
        if (ignoredBits < 0 || ignoredBits > 32) {
            throw new IllegalArgumentException("ignoredBits must be between 0 and 32");
        }

        this.filter = ignoredBits == 32 ? 0 : -1 << ignoredBits; // in case there are 32 bits
        this.selector = 1 << (ignoredBits - 1);
    }

    @Override
    public boolean contains(int internalX, int internalY, int internalZ) {
        return (internalX & this.filter) == this.internalX
                && (internalY & this.filter) == this.internalY
                && (internalZ & this.filter) == this.internalZ;
    }

    @Override
    public boolean isLeaf() {
        return true;
    }

    @Override
    InnerNode asInnerNode() {
        return this;
    }

    @Override
    LeafNode asLeafNode() {
        throw new UnsupportedOperationException("This is an inner node");
    }

    @Override
    public boolean isSkippable() {
        return this.skippableChildren == this.ownChildCount;
    }

    /**
     * Sets the upper bound visible frame bound of this node and all parents. Is
     * called by a child when it becomes visible.
     */
    void setChildVisibleNow(int frame) {
        InnerNode node = this;
        while (node != null && node.upperVisibleFrameBound != frame) {
            node.upperVisibleFrameBound = frame;
            node = node.parent;
        }
    }

    int getIndexFor(int internalX, int internalY, int internalZ) {
        // TODO: branchless?
        return ((internalX & this.selector) == 0 ? 0 : 1)
                | ((internalY & this.selector) == 0 ? 0 : 1) << 1
                | ((internalZ & this.selector) == 0 ? 0 : 1) << 2;
    }

    private int getIndexFor(Octree tree) {
        return getIndexFor(tree.internalX, tree.internalY, tree.internalZ);
    }

    public void setSection(RenderSection toSet) {
        Objects.requireNonNull(toSet);
        // if (contained != null && !contained.add(toSet.getChunkPos().toShortString()))
        // {
        // throw new IllegalArgumentException("Section " + toSet + " is already
        // contained in " + this);
        // }

        int rsX = toSet.getChunkX() + this.offset;
        int rsY = toSet.getChunkY() + this.offset;
        int rsZ = toSet.getChunkZ() + this.offset;

        if (!contains(rsX, rsY, rsZ)) {
            throw new IllegalArgumentException("Section " + toSet + " is not contained in " + this);
        }

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
                if (existingChild instanceof InnerNode existingInnerNode) {
                    // recurse into the existing child
                    existingInnerNode.setSection(toSet);
                } else if (existingChild instanceof LeafNode existingLeafNode) {
                    existingLeafNode.setSection(toSet);
                } else {
                    throw new IllegalStateException("Unknown child type: " + existingChild.getClass().getName());
                }
            } else {
                // the existing child is skipping some intermediary nodes, it's replaced with a
                // branching node that contains both it and a new leaf for the new section
                LeafNode leaf = new LeafNode(toSet, this.offset);

                // find the number of ignored bits at which the leaf and the existing child are
                // both contained.
                // it will be lower than the own ignored bits of this node
                int branchIgnoredBits = this.ignoredBits - 1;
                while (true) {
                    int branchFilter = -1 << branchIgnoredBits;
                    if ((rsX & branchFilter) != (existingChild.internalX & branchFilter)
                            || (rsY & branchFilter) != (existingChild.internalY & branchFilter)
                            || (rsZ & branchFilter) != (existingChild.internalZ & branchFilter)) {
                        break;
                    }
                    branchIgnoredBits--;
                }

                // adjust the ignored bits for the branch node back up since once the level at
                // which two nodes differ is found, the branch node is a level higher so that it
                // can contain both nodes
                branchIgnoredBits++;

                // creating the new branch will generate the right origin coordinates
                InnerNode branch = new InnerNode(this.offset, branchIgnoredBits, rsX, rsY, rsZ);
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
            LeafNode leaf = new LeafNode(toSet, this.offset);
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

    public void removeSection(RenderSection toRemove) {
        Objects.requireNonNull(toRemove);

        // if (contained != null &&
        // !contained.remove(toRemove.getChunkPos().toShortString())) {
        // throw new IllegalArgumentException("Section " + toRemove + " is not contained
        // in " + this);
        // }

        int rsX = toRemove.getChunkX() + this.offset;
        int rsY = toRemove.getChunkY() + this.offset;
        int rsZ = toRemove.getChunkZ() + this.offset;

        if (!contains(rsX, rsY, rsZ)) {
            throw new IllegalArgumentException("Section " + toRemove + " is not contained in " + this);
        }

        // find the index for the section
        int index = getIndexFor(rsX, rsY, rsZ);
        Octree child = this.children[index];

        // if this child does exist, remove the section from it
        if (child == null) {
            return;
        }
        if (child instanceof InnerNode innerNodeChild) {
            innerNodeChild.removeSection(toRemove);

            // if the child is a singleton, remove it and replace it with its only child
            if (innerNodeChild.ownChildCount == 1) {
                Octree onlySubchild = innerNodeChild.children[innerNodeChild.firstChildIndex];
                this.children[index] = onlySubchild;
                onlySubchild.setParent(this);

                // don't need to update skippable state here, the state of child and subchild is
                // the same
                return;
            }
        }

        // remove the child if it is now empty or it's a leaf node being removed.
        // there is nothing on a leaf node that can actually be removed, so the inner
        // node takes care of removing it when it should be removed.
        if (child instanceof InnerNode innerNodeChild && innerNodeChild.ownChildCount == 0 || child.isLeaf()) {
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

    @Override
    public boolean isBoxVisible(int frame, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        // TODO: problem: some but not all children appear to be gone from the root and are missiing a upper visible frame bound when this method is run but not otherwise. (this method is run to check if entities should be rendered)


        // skip if this frame doesn't appear as a visible frame in any of the children
        // or if the box doesn't even intersect this node.
        // no child could be visible if the upper bound doesn't even match the frame
        // since then all children and the node itself, must have been visible only
        // earlier.
        if (this.upperVisibleFrameBound != frame || !intersectsBox(minX, minY, minZ, maxX, maxY, maxZ)) {
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

    @Override
    LeafNode getSectionOctree(RenderSection toFind) {
        Objects.requireNonNull(toFind);

        int rsX = toFind.getChunkX() + this.offset;
        int rsY = toFind.getChunkY() + this.offset;
        int rsZ = toFind.getChunkZ() + this.offset;

        if (contains(rsX, rsY, rsZ)) {
            // find the index for the section
            int index = getIndexFor(rsX, rsY, rsZ);
            Octree child = this.children[index];
            return child == null ? null : child.getSectionOctree(toFind);
        }
        return null;
    }

    @Override
    public void iterateWholeTree(Consumer<LeafNode> consumer) {
        for (int i = this.firstChildIndex, childCount = this.ownChildCount; i < 8 && childCount > 0; i++) {
            Octree child = this.children[i];
            if (child != null) {
                child.iterateWholeTree(consumer);
                childCount--;
            }
        }
    }

    @Override
    public void iterateUnskippableTree(Consumer<LeafNode> consumer) {
        if (isSkippable()) {
            return;
        }

        for (int i = this.firstChildIndex, childCount = this.ownChildCount - this.skippableChildren; i < 8
                && childCount > 0; i++) {
            Octree child = this.children[i];
            if (child != null && !child.isSkippable()) {
                child.iterateUnskippableTree(consumer);
                childCount--;
            }
        }
    }

    @Override
    public int getEffectiveIgnoredBits() {
        return this.ignoredBits;
    }

    // indexes for each of the following 4-item bit masks (1 each direction)
    // 10101010, 01010101,
    // 11001100, 00110011,
    // 11110000, 00001111,
    private static final int[] FACE_INDICES = new int[] {
            0x00020406, 0x01030507,
            0x00010405, 0x02030607,
            0x00010203, 0x04050607 };

    /**
     * Iterates over all octree leaf nodes that touch the given face of this node.
     *
     * @param accumulator     the consumer that will be called for each leaf node
     * @param axisIndex       the axis index of the face
     * @param axisSign        the axis sign of the face
     * @param acceptSkippable whether to consume skippable non-leaf nodes and don't
     *                        consider their children
     */
    @Override
    public void iterateFaceNodes(Consumer<Octree> accumulator, int axisIndex, int axisSign, boolean acceptSkippable) {
        if (acceptSkippable && isSkippable()) { // TODO: test without acceptSkippable
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
                if (child.getEffectiveIgnoredBits() + 1 < this.ignoredBits) {
                    switch (axisIndex) {
                        case 0:
                            if (axisSign > 0 ? child.internalX + child.size != this.internalX + this.size
                                    : child.internalX != this.internalX) {
                                continue;
                            }
                            break;
                        case 1:
                            if (axisSign > 0 ? child.internalY + child.size != this.internalY + this.size
                                    : child.internalY != this.internalY) {
                                continue;
                            }
                            break;
                        case 2:
                            if (axisSign > 0 ? child.internalZ + child.size != this.internalZ + this.size
                                    : child.internalZ != this.internalZ) {
                                continue;
                            }
                            break;
                    }
                }
                child.iterateFaceNodes(accumulator, axisIndex, axisSign, acceptSkippable);
            }
        }
    }

    void changeSkippableCount(int change) {
        // decrement or increment skippable count
        this.skippableChildren += change;

        // send increment or decrement to parent if the skippable status changed
        if (this.parent != null && (change == 1
                ? this.skippableChildren == this.ownChildCount
                : this.skippableChildren + 1 == this.ownChildCount)) {
            this.parent.changeSkippableCount(change);
        }
    }
}
