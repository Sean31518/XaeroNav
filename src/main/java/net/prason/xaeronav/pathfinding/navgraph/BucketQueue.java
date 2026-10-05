package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;

/**
 * A priority queue that cuts distances into fixed-width buckets (Dial's algorithm). Within a bucket, items come out in reverse push order.
 *
 * <p>If the width is at most the minimum edge price, relaxing from a bucket always pushes into a later bucket, so
 * just emptying buckets from the front gives Dijkstra's settle order. Each push/pop is lighter than with a binary heap.
 */
final class BucketQueue {

    /** First element of each bucket. -1 if empty. */
    private int[] head = new int[0];
    private int[] value = new int[1 << 12];
    private int[] next = new int[1 << 12];
    /** Number of slots ever used. */
    private int size;
    /**
     * Head of the chain of slots already popped. -1 if empty. The window's reverse Dijkstra pushes 1.0-1.5x as many times as there are nodes, but only
     * part of them are queued at once, so reusing popped slots keeps the arrays small.
     */
    private int free = -1;
    /** Range of {@link #head} in use. */
    private int buckets;

    /**
     * @param items expected number queued at once. Grows by 1.25x when insufficient
     */
    void clear(int bucketCount, int items) {
        if (value.length < items) {
            value = new int[items];
            next = new int[items];
        }
        if (head.length < bucketCount) {
            head = new int[bucketCount + bucketCount / 4];
        }
        Arrays.fill(head, 0, Math.max(buckets, bucketCount), -1);
        buckets = bucketCount;
        size = 0;
        free = -1;
    }

    void push(int bucket, int item) {
        if (bucket >= buckets) {
            if (bucket >= head.length) {
                head = Arrays.copyOf(head, Math.max(bucket + 1, head.length * 2));
            }
            Arrays.fill(head, buckets, bucket + 1, -1);
            buckets = bucket + 1;
        }
        int entry = free;
        if (entry >= 0) {
            free = next[entry];
        } else {
            if (size == value.length) {
                value = Arrays.copyOf(value, size + size / 4);
                next = Arrays.copyOf(next, size + size / 4);
            }
            entry = size++;
        }
        value[entry] = item;
        next[entry] = head[bucket];
        head[bucket] = entry;
    }

    /** @return -1 if the bucket is empty */
    int pop(int bucket) {
        int entry = head[bucket];
        if (entry < 0) {
            return -1;
        }
        head[bucket] = next[entry];
        next[entry] = free;
        free = entry;
        return value[entry];
    }

    /** @return the first non-empty bucket at or after {@code from}, or -1 if none */
    int nextNonEmpty(int from) {
        for (int b = from; b < buckets; b++) {
            if (head[b] >= 0) {
                return b;
            }
        }
        return -1;
    }

    long bytes() {
        return 4L * head.length + 8L * value.length;
    }
}
