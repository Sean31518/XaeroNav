package net.prason.xaeronav.pathfinding.astar;

import java.util.Arrays;

/**
 * Binary heap for A*'s open set. Held in a 1-indexed array.
 *
 * <p>Pushing a new entry onto a {@code PriorityQueue} on every cost update (lazy deletion) piles up
 * entries for the same coordinate many times over, bloating the heap to several times the real node count.
 * Giving nodes {@link PathNode#heapPosition} and doing decrease-key directly keeps the heap size
 * always equal to the number of open nodes, and no entry objects are created.
 */
final class BinaryHeapOpenSet {

    private static final int INITIAL_CAPACITY = 1024;

    private PathNode[] array = new PathNode[INITIAL_CAPACITY];
    private int size;

    boolean isEmpty() {
        return size == 0;
    }

    void insert(PathNode node) {
        if (size >= array.length - 1) {
            array = Arrays.copyOf(array, array.length << 1);
        }
        size++;
        array[size] = node;
        node.heapPosition = size;
        siftUp(node);
    }

    /** Moves a node whose cost dropped up to its correct position. */
    void update(PathNode node) {
        siftUp(node);
    }

    PathNode removeLowest() {
        PathNode result = array[1];
        result.heapPosition = -1;

        PathNode last = array[size];
        array[size] = null;
        size--;
        if (size == 0) {
            return result;
        }

        array[1] = last;
        last.heapPosition = 1;
        siftDown(last);
        return result;
    }

    /** Instead of swapping at each level, moves parents down into the vacated hole and places the node only once at the end. */
    private void siftUp(PathNode node) {
        int index = node.heapPosition;
        double cost = node.combinedCost;
        while (index > 1) {
            int parentIndex = index >>> 1;
            PathNode parent = array[parentIndex];
            if (parent.combinedCost <= cost) {
                break;
            }
            array[index] = parent;
            parent.heapPosition = index;
            index = parentIndex;
        }
        array[index] = node;
        node.heapPosition = index;
    }

    private void siftDown(PathNode node) {
        int index = node.heapPosition;
        double cost = node.combinedCost;
        while (true) {
            int child = index << 1;
            if (child > size) {
                break;
            }
            PathNode smaller = array[child];
            if (child < size) {
                PathNode right = array[child + 1];
                if (right.combinedCost < smaller.combinedCost) {
                    child++;
                    smaller = right;
                }
            }
            if (cost <= smaller.combinedCost) {
                break;
            }
            array[index] = smaller;
            smaller.heapPosition = index;
            index = child;
        }
        array[index] = node;
        node.heapPosition = index;
    }
}
