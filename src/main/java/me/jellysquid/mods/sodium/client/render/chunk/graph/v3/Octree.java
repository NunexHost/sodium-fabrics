package me.jellysquid.mods.sodium.client.render.chunk.graph.v3;

public class Octree {
    public abstract static class Node<T> {
        abstract boolean isLeaf();
        abstract boolean isEmpty();
        abstract boolean isFull();
    }

    public final static class LeafNode<T> extends Node<T> {
        private T leaf;
        boolean isLeaf() {return true;}
        boolean isEmpty() {return false;}
        boolean isFull() {return true;}
    }

    public final static class InnerNode<T> extends Node<T> {
        Node<T>[] children = new Node[8];
        int count = 0;//Number of full children
        boolean isLeaf() {return false;}
        boolean isEmpty() {return count == 0;}
        boolean isFull() {return count == 8;}

        void setChild(int depth, int x, int y, int z, T leaf) {
            int id = ((x>>depth)&1) | (((y>>depth)&1)<<2) | (((z>>depth)&1)<<1);
            if (depth == 0) {
                count += leaf == null?-1:1;
                if (leaf == null) {
                    if (children[id] == null)//Verification
                        throw new IllegalStateException();
                    children[id] = null;
                } else {
                    if (children[id] != null)//Verification
                        throw new IllegalStateException();
                    LeafNode<T> leafNode = new LeafNode<T>();
                    leafNode.leaf = leaf;
                    children[id] = leafNode;
                }
            } else {
                InnerNode<T> child = (InnerNode<T>) children[id];
                if (child == null) {
                    child = (InnerNode<T>) (children[id] = new InnerNode<>());
                }
                boolean before = child.isFull();
                child.setChild(depth-1, x, y, z, leaf);
                boolean after = child.isFull();
                count += (before^after)?(before?-1:1):0;
            }
        }
    }
}
