package me.jellysquid.mods.sodium.client.render.chunk.graph;

import java.util.Objects;
import java.util.function.Consumer;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;

public class LeafNode extends Octree {
    public RenderSection section;
    public boolean skippable;

    LeafNode(RenderSection section, int offset) {
        super(offset, 1,
                section.getChunkX() + offset,
                section.getChunkY() + offset,
                section.getChunkZ() + offset);

        Objects.requireNonNull(section);

        this.section = section;
        section.octreeLeaf = this;
        this.skippable = section.hasEmptyData();
    }

    @Override
    public boolean contains(int internalX, int internalY, int internalZ) {
        return internalX == this.internalX
                && internalY == this.internalY
                && internalZ == this.internalZ;
    }

    @Override
    public boolean isLeaf() {
        return true;
    }

    @Override
    InnerNode asInnerNode() {
        throw new UnsupportedOperationException("This is a leaf node");
    }

    @Override
    LeafNode asLeafNode() {
        return this;
    }

    @Override
    public boolean isSkippable() {
        return skippable;
    }

    void setSection(RenderSection toSet) {
        this.section = toSet;
        toSet.octreeLeaf = this;
        updateSectionSkippable();
    }

    @Override
    public void setLastVisibleFrame(int frame) {
        this.lastVisibleFrame = frame;
        if (this.parent != null) {
            this.parent.setLastVisibleFrame(frame);
        }
    }

    @Override
    public boolean isBoxVisible(int frame, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return this.lastVisibleFrame == frame && intersectsBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    LeafNode getSectionOctree(RenderSection toFind) {
        Objects.requireNonNull(toFind);
        return this.section == toFind ? this : null;
    }

    @Override
    public void iterateWholeTree(Consumer<LeafNode> consumer) {
        consumer.accept(this);
    }

    @Override
    public void iterateUnskippableTree(Consumer<LeafNode> consumer) {
        if (isSkippable()) {
            return;
        }
        consumer.accept(this);
    }

    @Override
    public void iterateFaceNodes(Consumer<Octree> accumulator, int axisIndex, int axisSign, boolean acceptSkippable) {
        accumulator.accept(this);
    }

    @Override
    public int getEffectiveIgnoredBits() {
        return 0;
    }

    public void updateSectionSkippable() {
        setLeafSkippable(section.hasEmptyData());
    }

    void setLeafSkippable(boolean newSkippable) {
        if (this.skippable != newSkippable) {
            this.skippable = newSkippable;
            if (this.parent != null) {
                this.parent.changeSkippableCount(skippable ? 1 : -1);
            }
        }
    }
}
