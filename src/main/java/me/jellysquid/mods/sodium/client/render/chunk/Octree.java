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
    RenderSection section; // section can be null

    int ignoredBits;
    int filter;
    int selector;
    int x, y, z;

    Octree(RenderSection section) {
        addSection(section);
    }

    Octree() {
    }

    private void setIgnoredBits(int bits) {
        ignoredBits = bits;
        filter = -1 << ignoredBits;
        selector = 1 << ignoredBits;
    }

    boolean contains(int x, int y, int z) {
        return (x & filter) == this.x
                && (y & filter) == this.y
                && (z & filter) == this.z;
    }

    int getIndexFor(int x, int y, int z) {
        return (x & selector) | (y & selector) << 1 | (z & selector) << 2;
    }

    Octree addSection(RenderSection toSet) {
        if (toSet == null) {
            return this;
        }
        int x = toSet.getChunkX();
        int y = toSet.getChunkY();
        int z = toSet.getChunkZ();

        if (section == null && children == null) {
            section = toSet;

            // set mask to all 1s since a leaf node must match exactly
            setIgnoredBits(0);
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        } else {
            return merge(new Octree(toSet));
        }
    }

    private static Octree mergeAsSiblings(Octree a, Octree b) {
        return new Octree().merge(a).merge(b);
    }

    Octree merge(Octree other) {
        // if this section is a leaf node, generate a new parent on which to set both
        // this node and the new sibling
        if (section != null) {
            return mergeAsSiblings(this, other);
        }

        // this is an inner node without children
        else if (children == null) {
            // this section has no children yet, so create a new array of children
            children = new Octree[8];

            setIgnoredBits(other.ignoredBits + 1);
            x = other.x & filter;
            y = other.y & filter;
            z = other.z & filter;

            children[getIndexFor(other.x, other.y, other.z)] = other;
            return this;
        }

        // if the other is larger than this node, merge this node into the other
        else if (other.ignoredBits > ignoredBits) {
            return other.merge(this);
        }

        // if the other is the same size (same ignoredBits), create a new parent and add
        // this and the other. In the case of the other being the same as this node,
        // this results in a parent with only one child
        else if (other.ignoredBits == ignoredBits) {
            return mergeAsSiblings(this, other);
        }

        // if the other is more than one level smaller, make a new parent for it
        // and add it then, possibly then adding the new parent as a direct child
        // but otherwise creating new parents until the child is the right size
        else if (other.ignoredBits + 1 < ignoredBits) {
            return merge(new Octree().merge(other));
        }

        // if the other is exactly one level smaller than this node,
        // which is the right size for children of this node,
        // check if other is contained
        else if (contains(other.x, other.y, other.z)) {
            // check if the place for this node is already occupied
            int index = getIndexFor(other.x, other.y, other.z);
            Octree existingChild = children[index];
            if (existingChild != null) {
                // instead add the child to the existing node
                children[index] = existingChild.merge(other);
            } else {
                // if the place is free, add the new node
                children[getIndexFor(other.x, other.y, other.z)] = other;
            }
            return this;
        }

        // the other is at the right level, but not contained in this node.
        // make a new parent and try to add both this and the other
        // since the new parent is a higher level and is therefore larger,
        // possibly containing both this node and the other
        else {
            return mergeAsSiblings(this, other);
        }
    }

    // TODO: some kind of test to make sure that the numbers even make sense
    // TODO: do negative chunk coordinates work? (maximum recursion depth of the tree)
    static {
        Octree a = new Octree(new RenderSection(null, 0, 0, 0));
        Octree b = new Octree(new RenderSection(null, 1, 0, 0));
        Octree c = new Octree(new RenderSection(null, 0, 1, 0));
        Octree d = new Octree(new RenderSection(null, 0, 0, 1));
        Octree e = new Octree(new RenderSection(null, 1, 1, 0));
        Octree f = new Octree(new RenderSection(null, 1, 0, 1));
        Octree g = new Octree(new RenderSection(null, 0, 1, 1));
        Octree h = new Octree(new RenderSection(null, 1, 1, 1));
        
        Octree l1 = new Octree(new RenderSection(null, 10, 0, 0));
        Octree l2 = new Octree(new RenderSection(null, 10, 10, 0));
        Octree l3 = new Octree(new RenderSection(null, 0, 10, 0));

        Octree merged1 = a.merge(b).merge(c).merge(d).merge(e).merge(f).merge(g).merge(h);
        Octree merged2 = l1.merge(l2);
        Octree merged3 = merged2.merge(l3);
        Octree merged4 = merged1.merge(merged3);
    }
}
