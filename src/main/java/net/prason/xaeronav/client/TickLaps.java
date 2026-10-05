package net.prason.xaeronav.client;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * Accumulates the time spent in named work within one tick. Used to attach a breakdown to the slow-tick log.
 *
 * <p>Nested measurements count toward both parent and child (e.g. nav graph startup inside recalculation), so the sum can
 * exceed the whole tick. Records from threads other than the one that called {@link #begin} are dropped: the same work
 * may be called from {@code onMainThread} tasks or workers, and that is not tick time.
 */
final class TickLaps {

    /** Lower bound for the breakdown. Work below this is no clue to the cause even if listed. */
    private static final long SHOWN_NANOS = 1_000_000L;

    private static final int CAPACITY = 32;
    private static final String[] NAMES = new String[CAPACITY];
    private static final long[] NANOS = new long[CAPACITY];
    private static final int[] CALLS = new int[CAPACITY];
    private static int size;
    private static Thread owner;
    private static boolean inTick;
    private static long gcAtBegin;

    private TickLaps() {
    }

    static void begin() {
        owner = Thread.currentThread();
        size = 0;
        inTick = true;
        gcAtBegin = gcPauseMillis();
    }

    static void end() {
        inTick = false;
    }

    static long start() {
        return System.nanoTime();
    }

    static void add(String name, long startNanos) {
        if (Thread.currentThread() != owner) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        for (int i = 0; i < size; i++) {
            // Names are constants, so they can be looked up by identity
            if (NAMES[i] == name) {
                NANOS[i] += elapsed;
                CALLS[i]++;
                return;
            }
        }
        if (size < CAPACITY) {
            NAMES[size] = name;
            NANOS[size] = elapsed;
            CALLS[size] = 1;
            size++;
        }
    }

    /**
     * Measures tasks that receive search results on the main thread. If run directly within the tick ({@code Minecraft#execute} runs
     * immediately when called from the render thread), it counts toward the tick breakdown. Runs outside the tick don't show in the slow-tick log
     * but block the same render thread, so beyond {@link #SLOW_TASK_MILLIS} they are reported separately with their own breakdown.
     */
    static Runnable timed(Runnable task) {
        return () -> {
            boolean nested = inTick && Thread.currentThread() == owner;
            if (!nested) {
                begin();
            }
            long lap = start();
            try {
                task.run();
            } finally {
                add("receiving results", lap);
                long millis = (System.nanoTime() - lap) / 1_000_000L;
                long now = MonotonicTime.millis();
                if (!nested && millis > SLOW_TASK_MILLIS
                        && slowTaskGate.changed(true, now, SLOW_TASK_LOG_INTERVAL_MILLIS)) {
                    XaeroNav.LOGGER.warn("XaeroNav: Receiving results outside the tick is slow ({}ms, breakdown={})", millis, summary());
                }
                if (!nested) {
                    end();
                }
            }
        };
    }

    /** Names and measures what happens inside a receive. Which receive is heavy can't be told from the lambda's class name. */
    static <T> BiConsumer<T, Throwable> timed(String name, BiConsumer<T, Throwable> action) {
        return (result, error) -> {
            long lap = start();
            try {
                action.accept(result, error);
            } finally {
                add(name, lap);
            }
        };
    }

    private static final long SLOW_TASK_MILLIS = 50L;
    private static final long SLOW_TASK_LOG_INTERVAL_MILLIS = 5_000L;
    private static final ChangeGate<Boolean> slowTaskGate = new ChangeGate<>();

    /** Work at or above the lower bound, in order of time spent. {@code "no breakdown"} if none. */
    static String summary() {
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (NANOS[i] >= SHOWN_NANOS) {
                shown.add(i);
            }
        }
        // GC while stopped. Things can look slow not because of the work itself but because of a GC pause that hit during it
        long gc = gcPauseMillis() - gcAtBegin;
        if (shown.isEmpty()) {
            return gc > 0 ? "no breakdown, GC=" + gc + "ms" : "no breakdown";
        }
        shown.sort((a, b) -> Long.compare(NANOS[b], NANOS[a]));
        StringBuilder text = new StringBuilder();
        for (int i : shown) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(NAMES[i]).append('=').append(String.format(Locale.ROOT, "%.1f", NANOS[i] / 1e6)).append("ms");
            if (CALLS[i] > 1) {
                text.append('×').append(CALLS[i]);
            }
        }
        if (gc > 0) {
            text.append(", GC=").append(gc).append("ms");
        }
        return text.toString();
    }

    /** Total time threads were stopped by GC since startup. */
    static long gcPauseMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            // G1's "G1 Concurrent GC" is concurrent work that doesn't stop application threads, so it doesn't count as stopped time
            if (!bean.getName().contains("Concurrent")) {
                total += Math.max(0L, bean.getCollectionTime());
            }
        }
        return total;
    }
}
