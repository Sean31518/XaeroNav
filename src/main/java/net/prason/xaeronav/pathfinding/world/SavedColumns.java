package net.prason.xaeronav.pathfinding.world;

import org.jspecify.annotations.Nullable;

/**
 * Chunks read back from the singleplayer save, for chunks the client hasn't loaded ({@link ChunkView#capture}).
 * Only returns what it already holds: reading from disk happens beforehand, off the main thread.
 */
@FunctionalInterface
public interface SavedColumns {

    @Nullable ChunkColumn column(int chunkX, int chunkZ);
}
