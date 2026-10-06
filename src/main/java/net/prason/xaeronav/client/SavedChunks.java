package net.prason.xaeronav.client;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jspecify.annotations.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.pathfinding.world.ChunkColumn;
import net.prason.xaeronav.pathfinding.world.SavedColumns;
import net.prason.xaeronav.util.GameCompat;

/**
 * Reads chunks back from the singleplayer world's save, so a route can be planned past render distance
 * ({@link FullRoutePlanner}).
 *
 * <p>Goes through the integrated server's own chunk storage ({@code ChunkMap#read}): it reads on the server's IO
 * thread, sees writes still waiting to be flushed, and never generates anything; chunks that were never generated
 * (or only half) simply aren't there. Parsing the data into block sections runs on a low-priority thread of its own.
 *
 * <p>Read-only, and only for terrain: nothing is ever loaded into the world. On a multiplayer server the client has no
 * save to read, so {@link #available()} is false and routes keep stopping at the edge of the loaded chunks.
 *
 * <p>Minecraft 26.3 and later only.
 */
public final class SavedChunks {

    public static final SavedChunks INSTANCE = new SavedChunks();

    /** Most chunk columns kept. A 4 km route with a 3-chunk corridor needs about 3000; each is a few tens of KB. */
    private static final int CAPACITY = 8192;

    /** Chunks asked from disk at once. The server's IO thread serves its own chunk loading too. */
    private static final int BATCH = 64;

    private final ExecutorService parser = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XaeroNav saved chunks");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Access-ordered LRU. Guarded by {@code this}. A {@code null} value remembers "not in the save". */
    private final Map<Long, ChunkColumn> columns = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ChunkColumn> eldest) {
            return size() > CAPACITY;
        }
    };
    private @Nullable ResourceKey<Level> dimension;

    private SavedChunks() {
    }

    /** Whether there is a save to read: singleplayer on a version that supports it. */
    public boolean available() {
        //? if >=26.3 {
        /*return net.minecraft.client.Minecraft.getInstance().hasSingleplayerServer();
        *///?} else {
        return false;
        //?}
    }

    /** What is held for this dimension so far, for {@code ChunkView#capture}. */
    public SavedColumns view(ResourceKey<Level> dimension) {
        return (chunkX, chunkZ) -> {
            synchronized (this) {
                return dimension.equals(this.dimension) ? columns.get(GameCompat.chunkKey(chunkX, chunkZ)) : null;
            }
        };
    }

    /** Forgets everything, e.g. on leaving the world. */
    public synchronized void clear() {
        columns.clear();
        dimension = null;
    }

    /**
     * Reads the given chunks (keys from {@code GameCompat#chunkKey}) that aren't held yet. Completes when all of them
     * are read or known to be missing; never fails (unreadable chunks count as missing).
     */
    public CompletableFuture<Void> prefetch(ResourceKey<Level> dimension, Collection<Long> chunkKeys) {
        if (!available()) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (this) {
            if (!dimension.equals(this.dimension)) {
                columns.clear();
                this.dimension = dimension;
            }
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        long[] batch = new long[BATCH];
        int size = 0;
        for (long key : chunkKeys) {
            synchronized (this) {
                if (columns.containsKey(key)) {
                    continue;
                }
            }
            batch[size++] = key;
            if (size == BATCH) {
                long[] keys = batch.clone();
                chain = chain.thenCompose(ignored -> readBatch(dimension, keys, BATCH));
                size = 0;
            }
        }
        if (size > 0) {
            long[] keys = batch.clone();
            int count = size;
            chain = chain.thenCompose(ignored -> readBatch(dimension, keys, count));
        }
        return chain;
    }

    private CompletableFuture<Void> readBatch(ResourceKey<Level> dimension, long[] keys, int count) {
        CompletableFuture<?>[] reads = new CompletableFuture<?>[count];
        for (int i = 0; i < count; i++) {
            long key = keys[i];
            reads[i] = read(dimension, key).handle((column, error) -> {
                if (error != null) {
                    XaeroNav.LOGGER.debug("XaeroNav: could not read saved chunk {}", key, error);
                }
                synchronized (this) {
                    if (dimension.equals(this.dimension)) {
                        columns.put(key, error == null ? column : null);
                    }
                }
                return null;
            });
        }
        return CompletableFuture.allOf(reads);
    }

    //? if >=26.3 {
    /*private CompletableFuture<@Nullable ChunkColumn> read(ResourceKey<Level> dimension, long key) {
        net.minecraft.client.server.IntegratedServer server = net.minecraft.client.Minecraft.getInstance()
                .getSingleplayerServer();
        net.minecraft.server.level.ServerLevel level = server == null ? null : server.getLevel(dimension);
        if (level == null) {
            return CompletableFuture.completedFuture(null);
        }
        net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(
                net.minecraft.world.level.ChunkPos.getX(key), net.minecraft.world.level.ChunkPos.getZ(key));
        net.minecraft.server.level.ChunkMap storage = level.getChunkSource().chunkMap;
        return storage.read(pos).thenApplyAsync(tag -> tag.map(data -> parse(level, storage, data)).orElse(null),
                parser);
    }

    // Sections and the motion-blocking height of one saved chunk, or null unless it is fully generated in this
    // version's format
    private static @Nullable ChunkColumn parse(net.minecraft.server.level.ServerLevel level,
                                               net.minecraft.server.level.ChunkMap storage,
                                               net.minecraft.nbt.CompoundTag tag) {
        int current = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
        int version = net.minecraft.nbt.NbtUtils.getDataVersion(tag, -1);
        if (version < 0 || version > current) {
            return null;
        }
        if (version < current) {
            // Chunks saved by an older version: bring them up to date the way the server would when loading them
            tag = storage.upgradeChunkTag(tag, version);
        }
        net.minecraft.world.level.chunk.status.ChunkStatus status =
                net.minecraft.world.level.chunk.storage.SerializableChunkData.getChunkStatusFromTag(tag);
        if (status == null || !status.isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.FULL)) {
            // Half-generated edge chunks have no final terrain yet
            return null;
        }
        net.minecraft.world.level.chunk.storage.SerializableChunkData data =
                net.minecraft.world.level.chunk.storage.SerializableChunkData.parse(level,
                        level.palettedContainerFactory(), tag);
        if (data == null) {
            return null;
        }
        net.minecraft.world.level.chunk.LevelChunkSection[] sections =
                new net.minecraft.world.level.chunk.LevelChunkSection[level.getSectionsCount()];
        for (net.minecraft.world.level.chunk.storage.SerializableChunkData.SectionData section : data.sectionData()) {
            int index = section.y() - level.getMinSectionY();
            if (index >= 0 && index < sections.length && section.chunkSection() != null) {
                sections[index] = section.chunkSection();
            }
        }
        return new ChunkColumn.Saved(sections, motionBlockingHeights(sections, level.getMinY()));
    }

    // MOTION_BLOCKING per column by scanning down from the top, as the heightmap would hold it (the stored heightmap
    // is packed with a width that depends on the world height; recomputing avoids depending on that layout)
    private static int[] motionBlockingHeights(net.minecraft.world.level.chunk.LevelChunkSection[] sections, int minY) {
        java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> blocks =
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING.isOpaque();
        int[] heights = new int[256];
        for (int column = 0; column < 256; column++) {
            int x = column & 15;
            int z = column >> 4;
            int height = minY - 1;
            scan:
            for (int index = sections.length - 1; index >= 0; index--) {
                net.minecraft.world.level.chunk.LevelChunkSection section = sections[index];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                for (int y = 15; y >= 0; y--) {
                    if (blocks.test(section.getBlockState(x, y, z))) {
                        height = minY + index * 16 + y;
                        break scan;
                    }
                }
            }
            heights[column] = height;
        }
        return heights;
    }
    *///?} else {
    private CompletableFuture<@Nullable ChunkColumn> read(ResourceKey<Level> dimension, long key) {
        return CompletableFuture.completedFuture(null);
    }
    //?}
}
