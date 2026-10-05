package net.prason.xaeronav.pathfinding.world;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A watchdog that wraps a {@link CellSource} and records "the threads that touched this instance".
 *
 * <p>{@code CellSource} is promised to be owned by a single worker thread ({@code ChunkView}'s thread contract), and
 * breaking that only shows up nondeterministically: in-game, an {@code ArrayIndexOutOfBoundsException} every few minutes,
 * and when it didn't appear, a route came out having read blocks from another chunk. <b>By looking at the contract
 * violation itself rather than waiting for the breakage</b>, the check doesn't depend on interleaving.
 *
 * <p>It's implemented as a dynamic proxy so the watchdog isn't bypassed when methods are added to {@code CellSource}.
 */
public final class OwnerTrackingCells {

    private final CellSource view;
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    private final AtomicReference<Thread> intruder = new AtomicReference<>();

    public OwnerTrackingCells(CellSource delegate) {
        this.view = (CellSource) Proxy.newProxyInstance(
                CellSource.class.getClassLoader(),
                new Class<?>[] {CellSource.class},
                (proxy, method, args) -> {
                    Thread current = Thread.currentThread();
                    if (!owner.compareAndSet(null, current) && owner.get() != current) {
                        intruder.compareAndSet(null, current);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    public CellSource view() {
        return view;
    }

    /** The first thread that touched this view. {@code null} if it has never been touched. */
    public Thread owner() {
        return owner.get();
    }

    /** A thread other than the owner that touched it. {@code null} if the contract is upheld. */
    public Thread intruder() {
        return intruder.get();
    }
}
