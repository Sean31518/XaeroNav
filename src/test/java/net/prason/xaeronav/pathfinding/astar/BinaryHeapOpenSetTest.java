package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Verifies the correctness of {@link BinaryHeapOpenSet}. It's the heart that handles tens of thousands of insert/update/removeLowest
 * calls per A* search, and if it breaks the symptom only surfaces as "search results occasionally take a strange path"
 * (even a broken heap mostly looks like it works). The decrease-key path ({@link #update}) is a branch
 * ordinary priority queues don't have, so it's checked especially thoroughly.
 */
class BinaryHeapOpenSetTest {

    private static PathNode node(int index, double combinedCost) {
        // x, y, z, boating and estimatedCostToGoal aren't used in this test, so only the index matters, for identification
        PathNode node = new PathNode(index, 0, 0, false, 0.0);
        node.combinedCost = combinedCost;
        return node;
    }

    @Test
    void removalOrderIsNonDecreasingUnderRandomInsertions() {
        Random random = new Random(20260811L);
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        int count = 2000;
        for (int i = 0; i < count; i++) {
            heap.insert(node(i, random.nextDouble() * 1000.0));
        }

        double previous = Double.NEGATIVE_INFINITY;
        int removed = 0;
        while (!heap.isEmpty()) {
            PathNode next = heap.removeLowest();
            assertTrue(next.combinedCost >= previous,
                    "Order reversed: " + previous + " was followed by " + next.combinedCost + " coming out");
            previous = next.combinedCost;
            removed++;
        }
        assertEquals(count, removed);
    }

    @Test
    void decreaseKeyMovesNodeAheadOfCheaperExistingEntries() {
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        PathNode cheap = node(0, 10.0);
        PathNode mid = node(1, 20.0);
        PathNode expensive = node(2, 30.0);
        heap.insert(cheap);
        heap.insert(mid);
        heap.insert(expensive);

        // They should come out in the order cheap(10) < mid(20) < expensive(30), but expensive's cost
        // is lowered to 5 before update(). If this doesn't trigger siftUp correctly, the heap ends up inconsistent,
        // with expensive left in its old position
        expensive.combinedCost = 5.0;
        heap.update(expensive);

        assertEquals(5.0, heap.removeLowest().combinedCost);
        assertEquals(10.0, heap.removeLowest().combinedCost);
        assertEquals(20.0, heap.removeLowest().combinedCost);
        assertTrue(heap.isEmpty());
    }

    @Test
    void isOpenReflectsMembership() {
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        PathNode a = node(0, 1.0);
        assertFalse(a.isOpen());

        heap.insert(a);
        assertTrue(a.isOpen());

        PathNode removed = heap.removeLowest();
        assertEquals(a, removed);
        assertFalse(a.isOpen());
    }

    @Test
    void handlesGrowthPastInitialCapacity() {
        // Checks that heap order isn't broken even when the array grows (copyOf)
        // beyond BinaryHeapOpenSet's initial array size (1024)
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        int count = 5000;
        for (int i = 0; i < count; i++) {
            heap.insert(node(i, count - i));
        }
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            double cost = heap.removeLowest().combinedCost;
            assertTrue(cost >= previous);
            previous = cost;
        }
    }
}
