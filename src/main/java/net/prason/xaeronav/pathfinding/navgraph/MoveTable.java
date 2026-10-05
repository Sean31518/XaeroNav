package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Table assigning serial numbers to edge "moves" (pairs of relative coordinates and price). Edges hold only this number (2 bytes).
 *
 * <p>There are fewer than a few thousand move kinds even across a whole window (measured: 70 relative coordinates and about 100 prices in a wide-area window, at most 319 kinds in one section),
 * so this takes a fifth of the space of holding coordinates and price per edge.
 *
 * <p>Numbers are only appended, never reassigned. A table taken with {@link #view} can look up every number assigned before it.
 * May be called concurrently from worker threads.
 */
final class MoveTable {

    /** Numbers fit in a {@code char}. */
    private static final int CAPACITY = 1 << 16;

    /** Table for lookups. Can read the numbers assigned up to when it was taken. */
    static final class View {
        final byte[] dx;
        final short[] dy;
        final byte[] dz;
        final float[] cost;
        /** Minimum price among assigned numbers. {@link Float#MAX_VALUE} if none yet. */
        final float minCost;

        private View(byte[] dx, short[] dy, byte[] dz, float[] cost, float minCost) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.cost = cost;
            this.minCost = minCost;
        }
    }

    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
    private volatile View view = new View(new byte[64], new short[64], new byte[64], new float[64], Float.MAX_VALUE);
    private int size;

    MoveTable() {
        index.defaultReturnValue(-1);
    }

    View view() {
        return view;
    }

    /** Relative coordinate key {@code dx(8) | dz(8) | dy(16)}. */
    static int offsetKey(int dx, int dy, int dz) {
        return (dx & 0xFF) << 24 | (dz & 0xFF) << 16 | (dy & 0xFFFF);
    }

    /**
     * Assigns numbers to the pairs of {@code offsets[i]} ({@link #offsetKey}) and {@code costs[i]}, writing them to {@code ids[i]}.
     *
     * @throws IllegalStateException if the move kinds don't fit in a {@code char}. Even in a world where prices vary block by block
     *                               they stay at a few thousand kinds, so getting here means move generation is broken
     */
    synchronized void intern(int[] offsets, float[] costs, int count, char[] ids) {
        View current = view;
        byte[] dx = current.dx;
        short[] dy = current.dy;
        byte[] dz = current.dz;
        float[] cost = current.cost;
        float minCost = current.minCost;
        for (int i = 0; i < count; i++) {
            long key = (long) offsets[i] << 32 | Float.floatToIntBits(costs[i]) & 0xFFFFFFFFL;
            int id = index.get(key);
            if (id < 0) {
                if (size == CAPACITY) {
                    throw new IllegalStateException("Move kinds exceeded " + CAPACITY + " entries");
                }
                if (size == dx.length) {
                    int grown = Math.min(CAPACITY, size * 2);
                    dx = Arrays.copyOf(dx, grown);
                    dy = Arrays.copyOf(dy, grown);
                    dz = Arrays.copyOf(dz, grown);
                    cost = Arrays.copyOf(cost, grown);
                }
                id = size++;
                dx[id] = (byte) (offsets[i] >> 24);
                dz[id] = (byte) (offsets[i] >> 16);
                dy[id] = (short) offsets[i];
                cost[id] = costs[i];
                minCost = Math.min(minCost, costs[i]);
                index.put(key, id);
            }
            ids[i] = (char) id;
        }
        // Readers only look up numbers after they are published, so the array can be swapped while still being reused
        view = new View(dx, dy, dz, cost, minCost);
    }
}
