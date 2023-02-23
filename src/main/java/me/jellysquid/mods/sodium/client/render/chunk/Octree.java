package me.jellysquid.mods.sodium.client.render.chunk;

import java.util.Objects;

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
 */
public class Octree {
    public final Octree[] children; // children can be null
    public int ownChildCount = 0;
    public RenderSection section; // section can be null

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

    public static Octree root() {
        return new Octree(0, 0, 0, 0);
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
        return (x & selector) | (y & selector) << 1 | (z & selector) << 2;
    }

    private int getIndexFor(Octree tree) {
        return getIndexFor(tree.x, tree.y, tree.z);
    }

    void setSection(RenderSection toSet) {
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
                    newParent.children[newParent.getIndexFor(child)] = child;
                    newParent.ownChildCount = 1;
                    child = newParent;
                }
                children[index] = child;
                ownChildCount++;
            }
        }
    }

    void removeSection(RenderSection toRemove) {
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
            Octree existingChild = children[index];

            // if this child does exist, remove the section from it
            if (existingChild != null) {
                existingChild.removeSection(toRemove);

                // and remove the child if it's now empty
                if (existingChild.ownChildCount == 0) {
                    children[index] = null;
                    ownChildCount--;
                }
            }
        }
    }
}
