package net.prason.xaeronav.pathfinding.world;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The block data of one chunk column, as {@link ChunkView} reads it: its sections and the {@code MOTION_BLOCKING}
 * height per column.
 *
 * <p>Two sources: chunks loaded on the client ({@link #loaded}), and chunks read back from the singleplayer world's
 * save files beyond render distance ({@link Saved}), which is what lets a whole route be planned at once.
 */
public interface ChunkColumn {

    /** Section {@code index} counted from the lowest one, or {@code null} if it is empty (before 1.18) or missing. */
    @Nullable LevelChunkSection section(int index);

    /** Same as {@code LevelChunk#getHeight(MOTION_BLOCKING, x, z)}: the highest motion-blocking Y in the column. */
    int motionBlockingHeight(int x, int z);

    static ChunkColumn loaded(LevelChunk chunk) {
        return new Loaded(chunk);
    }

    /** A chunk the client has loaded. Read without synchronisation under {@link ChunkView}'s thread contract. */
    record Loaded(LevelChunk chunk) implements ChunkColumn {

        @Override
        public @Nullable LevelChunkSection section(int index) {
            LevelChunkSection[] sections = chunk.getSections();
            return index < 0 || index >= sections.length ? null : sections[index];
        }

        @Override
        public int motionBlockingHeight(int x, int z) {
            return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        }
    }

    /**
     * A chunk read back from disk. Immutable once built: its sections are private copies nobody writes to, so any
     * thread may read it.
     *
     * @param sections sections from the lowest one up ({@code null} entries are missing sections)
     * @param heights  {@link #motionBlockingHeight} per column, indexed {@code (z & 15) * 16 + (x & 15)}
     */
    record Saved(@Nullable LevelChunkSection[] sections, int[] heights) implements ChunkColumn {

        @Override
        public @Nullable LevelChunkSection section(int index) {
            return index < 0 || index >= sections.length ? null : sections[index];
        }

        @Override
        public int motionBlockingHeight(int x, int z) {
            return heights[(z & 15) * 16 + (x & 15)];
        }
    }
}
