package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.world.MovementOptions;

/**
 * Config values read together right before a search is submitted. The four search call sites in {@code PathfindingState}
 * (regular search, merge, seam re-solve, extension) each read {@code XaeroNavConfig.INSTANCE} individually
 * in the same order, so they are read once here.
 *
 * <p>What is not included here ({@code deviationThresholdBlocks}, {@code recalcIntervalTicks}, etc.) are
 * values used for per-tick threshold checks, where config changes should take effect immediately. Deliberately not snapshotted.
 */
public record NavigationTuning(int searchHorizontalMargin, MovementOptions movementOptions,
                                SearchLimits searchLimits, boolean costToGoGuideEnabled) {
}
