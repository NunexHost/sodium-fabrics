package me.jellysquid.mods.sodium.client.render.chunk.graph.v5;


public class Graph {
    //Needs an octree of render sections effectivly air sections (with non effectivly air sections contained within)
    // then need to construct a merged graph were as many nodes as possible merge together
    // ropes are used to iterate over the graph and find visible areas
    // this tree is effectivly inverted as it stores render sections that are non effectivly air nodes
    private static abstract class Node<T> {
        final Node<T>[][] ropes = new Node[6][];
        abstract T[] getChildren();
    }

    private static class EmptyNode<T> extends Node<T> {
        final Node<T>[][] ropes = new Node[6][];
        T[] children;

        T[] getChildren() {
            return children;
        }
    }

}
