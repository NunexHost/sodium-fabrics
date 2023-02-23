package me.jellysquid.mods.sodium.client.render.chunk;

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
    Octree[] children; // children can be null
    int ownChildCount = 0;
    RenderSection section; // section can be null

    final int ignoredBits;
    final int filter;
    final int selector;
    final int x, y, z;

    Octree(RenderSection section) {
        this(0, section.getChunkX(), section.getChunkY(), section.getChunkZ());
        this.section = section;
        ownChildCount = 1;
    }

    Octree(int ignoredBits, int x, int y, int z) {
        this.ignoredBits = ignoredBits;
        filter = ignoredBits == 32 ? 0 : -1 << ignoredBits;
        selector = 1 << (ignoredBits - 1); // may only be used if ignoredBits > 0

        this.x = x & filter;
        this.y = y & filter;
        this.z = z & filter;
    }

    public static Octree root() {
        return new Octree(0, 0, 0, 0);
    }

    boolean contains(int x, int y, int z) {
        return (x & filter) == this.x
                && (y & filter) == this.y
                && (z & filter) == this.z;
    }

    boolean contains(Octree tree) {
        return contains(tree.x, tree.y, tree.z);
    }

    int getIndexFor(int x, int y, int z) {
        return (x & selector) | (y & selector) << 1 | (z & selector) << 2;
    }

    int getIndexFor(Octree tree) {
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
        } else {
            if (children == null) {
                children = new Octree[8];
            }

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
                    newParent.children = new Octree[8];
                    newParent.children[newParent.getIndexFor(child)] = child;
                    newParent.ownChildCount = 1;
                    child = newParent;
                }
                children[index] = child;
                ownChildCount++;
            }
        }

    }
}
