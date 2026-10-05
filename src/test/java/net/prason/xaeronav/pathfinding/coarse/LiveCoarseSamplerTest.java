package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class LiveCoarseSamplerTest {

    @Test
    void detectsACliffWithinASingleChunk() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            int floorY = x < 8 ? 64 : 60;
            for (int z = 0; z < 16; z++) {
                cells.set(x, floorY, z, FakeCells.STONE);
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 0));
        assertEquals(60, map.minHeightAtFloor(0, 0, 0));
        assertEquals(64, map.maxHeightAtFloor(0, 0, 0));
    }

    /**
     * When a cell has more than {@link CoarseMap#MAX_FLOORS} levels, the floors kept are those nearest the reference Y.
     * Passing them straight to {@code CoarseMapBuilder} without looking at the reference Y makes it always evict
     * the "highest floor", so the topmost corridor the player is standing in simply disappears.
     */
    @Test
    void keepsTheFloorsNearestTheReferenceYWhenACellHasTooMany() {
        // Per-column scanning is capped at MAX_FLOORS, so show a different level in each column
        // so that the chunk as a whole has limit+1 clusters (a shape that occurs routinely in the Nether)
        SearchBounds bounds = new SearchBounds(0, 0, 0, 15, 127, 15);
        FakeCells cells = FakeCells.empty(bounds);
        // Space the levels wider than FLOOR_CLUSTER_THRESHOLD_BLOCKS (12). Any tighter and
        // they get merged as the same floor
        int top = 118;
        int spacing = 18;
        int farthest = top - spacing * CoarseMap.MAX_FLOORS;
        for (int x = 0; x < 16; x++) {
            int[] floorYs;
            if (x < 8) {
                floorYs = new int[CoarseMap.MAX_FLOORS];
                for (int i = 0; i < CoarseMap.MAX_FLOORS; i++) {
                    floorYs[i] = top - spacing * i;
                }
            } else {
                floorYs = new int[] {farthest};
            }
            for (int floorY : floorYs) {
                for (int z = 0; z < 16; z++) {
                    cells.set(x, floorY, z, FakeCells.STONE);
                }
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds, top, () -> false);

        assertEquals(CoarseMap.MAX_FLOORS, map.floorCount(0, 0));
        assertEquals(top, map.heightAtFloor(0, 0, CoarseMap.MAX_FLOORS - 1),
                "The floor at the reference Y (the corridor the player is standing in) was not kept");
        assertEquals(top - spacing * (CoarseMap.MAX_FLOORS - 1), map.heightAtFloor(0, 0, 0),
                "The floor farthest from the reference Y should be the one discarded");
    }

    @Test
    void classifiesAMostlyWaterChunkAsWater() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 60, z, FakeCells.WATER);
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.WATER, map.kindAtFloor(0, 0, 0));
    }

    @Test
    void lavaMixedChunkHeightIgnoresTheLavaSurface() {
        // Edge of a lava sea: 1/4 is lava surface (Y=31, the lower bound of LAVA_MIXED), the rest is higher ground (Y=40).
        // If the representative height is pulled toward the lava surface, waypoints land where you cannot stand
        SearchBounds bounds = new SearchBounds(0, 20, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (x < 4) {
                    cells.set(x, 31, z, FakeCells.LAVA);
                } else {
                    cells.set(x, 40, z, FakeCells.STONE);
                }
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.LAVA_MIXED, map.kindAtFloor(0, 0, 0));
        assertEquals(40, map.heightAtFloor(0, 0, 0));
    }

    /**
     * The Nether shape. With a bedrock ceiling, {@code openSkyY} points at the ceiling (here far above the search range),
     * but what belongs on the map is the terrain underfoot within the range. Unless scanning starts capped at the top of the range,
     * it reads outside the range, every column becomes ABSENT, and not a single cell of the map is filled.
     */
    @Test
    void samplesTheGroundInsideBoundsWhenTheCeilingIsAboveThem() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 74, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 42, z, FakeCells.STONE);
            }
        }
        // Bedrock ceiling outside the search range. openSkyY points at this
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(1, map.knownCells(), "Terrain below the ceiling must be put on the map");
        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 0));
        assertEquals(42, map.heightAtFloor(0, 0, 0));
    }

    /** Also picks up a lava sea far below the top of the range (if scanning is too shallow, the sea itself never reaches the map). */
    @Test
    void reachesLavaFarBelowTheTopOfBounds() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 74, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 31, z, FakeCells.LAVA);
            }
        }
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.LAVA, map.kindAtFloor(0, 0, 0), "If the lava sea is not on the map, it cannot be avoided either");
    }

    /**
     * The Nether 3D maze. In columns where the top of the search range is buried in rock, that top must not be
     * reported as ground. Every column would end up at the same height, looking like flat, cheapest terrain with zero relief = zero cliff penalty,
     * and the lava sea underfoot would vanish from the map entirely.
     */
    @Test
    void doesNotReportTheTopOfBoundsAsGroundWhenItIsInsideRock() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 72, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Fill with rock from the top of the range (72) down to 50. Below that is a cavity whose floor is a lava sea
                for (int y = 50; y <= 72; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
                cells.set(x, 31, z, FakeCells.LAVA);
            }
        }
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.LAVA, map.kindAtFloor(0, 0, 0), "Reading ceiling-side rock as ground makes the lava sea vanish from the map");
        assertEquals(31, map.heightAtFloor(0, 0, 0));
    }

    /** A column packed with rock from top to bottom is "unknown". Ceiling rock must not be read as ground. */
    @Test
    void columnsFilledWithRockAreLeftUnknown() {
        SearchBounds bounds = new SearchBounds(0, 60, 0, 15, 72, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 60; y <= 72; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(0, map.knownCells());
    }

    /**
     * The core of the Nether 3D maze: when two independent passages are stacked at the same XZ, both must remain on the map
     * as separate floors. Collapsing them into one height makes vertically separated passages
     * look connected by a "cheap step", or makes one passage vanish entirely.
     */
    @Test
    void capturesTwoIndependentFloorsStackedInTheSameColumn() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 100, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 90, z, FakeCells.STONE); // upper level (just below the ceiling)
                cells.set(x, 40, z, FakeCells.STONE); // lower level
            }
        }
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(2, map.floorCount(0, 0));
        assertEquals(40, map.heightAtFloor(0, 0, 0), "Floors are in ascending height order");
        assertEquals(90, map.heightAtFloor(0, 0, 1));
        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 0));
        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 1));
    }

    /**
     * A case where one level is a lava sea and the other is ordinary land. Whether layer 1 can correctly tell the lava sea
     * apart from another level from stage 4 onward depends on both being kept as floors in the first place.
     */
    @Test
    void capturesALavaFloorBelowALandFloor() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 100, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 70, z, FakeCells.STONE);
                cells.set(x, 31, z, FakeCells.LAVA);
            }
        }
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(2, map.floorCount(0, 0));
        assertEquals(CoarseMap.LAVA, map.kindAtFloor(0, 0, 0));
        assertEquals(31, map.heightAtFloor(0, 0, 0));
        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 1));
        assertEquals(70, map.heightAtFloor(0, 0, 1));
    }

    /** In the "one floor per column" case, as in the Overworld and the End, the floor count always stays at 1 (regression guard). */
    @Test
    void aSingleFloorColumnStillProducesExactlyOneFloor() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 64, z, FakeCells.STONE);
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(1, map.floorCount(0, 0));
    }

    @Test
    void columnsWithNoDataAreLeftUnknown() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.ABSENT);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(0, map.knownCells());
        assertEquals(0, map.floorCount(0, 0), "A cell with no data has no floors at all");
    }

    /**
     * <b>The End void itself.</b> In columns with no floor at all, {@code MOTION_BLOCKING} is empty, so
     * {@code ChunkView#openSkyY} returns <b>the world's minimum Y+1</b>, i.e. <b>below the bottom</b> of the search band.
     *
     * <p>Taking the top of the scan from there gives `top < bottom`, so <b>the loop body never runs</b>.
     * This is why the in-game log (the_end, 2026-08-27) only reached `known cells=51/154`,
     * and void detection never fired once in the End.
     */
    @Test
    void detectsVoidWhenTheHeightmapIsBelowTheSearchBand() {
        // The player is at Y=57 and the search band is Y±32. Void columns have an empty MOTION_BLOCKING, so openSkyY=1
        SearchBounds bounds = new SearchBounds(0, 25, 0, 15, 89, 15);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).openSkyYOverride(1);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(1, map.floorCount(0, 0),
                "If the End void stays unknown, layer 1 cuts straight through it as the cheapest path");
        assertEquals(CoarseMap.VOID, map.kindAtFloor(0, 0, 0));
    }

    /**
     * <b>Floor count 0 has three meanings.</b> Only air (void), solid from top to bottom (inside rock), and
     * unloaded and unreadable (unknown). Only the void is {@link CoarseMap#VOID}; the other two stay unknown.
     *
     * <p>Collapsing the void into unknown makes layer 1 cut straight between End islands at the cheap unknown-cell cost
     * (1.6x land). Moreover, {@code CoarseRouter#calibratedUnknownMultiplier} raises the unknown cost from the
     * known void ratio, so when the void collapses into unknown, <b>the material for that calibration disappears at the same time</b>
     * (cheap unknowns increase while the grounds for raising them decrease). Conversely, collapsing rock or unloaded cells into void
     * produces routes that build bridges where crossing should be impossible.
     */
    @Test
    void tellsVoidApartFromRockAndFromUnloaded() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);

        CoarseMap air = LiveCoarseSampler.sample(FakeCells.empty(bounds).fillWith(FakeCells.AIR), bounds);
        assertEquals(1, air.floorCount(0, 0), "An air-only column is known to have 'no floor'");
        assertEquals(CoarseMap.VOID, air.kindAtFloor(0, 0, 0));
        assertEquals(CoarseMap.UNKNOWN_HEIGHT, air.heightAtFloor(0, 0, 0),
                "The void has no representative height. Putting a concrete value there makes layers 2 and 3 aim for it");

        CoarseMap rock = LiveCoarseSampler.sample(FakeCells.empty(bounds).fillWith(FakeCells.STONE), bounds);
        assertEquals(0, rock.floorCount(0, 0), "A column packed with rock is not void");

        CoarseMap unloaded =
                LiveCoarseSampler.sample(FakeCells.empty(bounds).fillWith(FakeCells.ABSENT), bounds);
        assertEquals(0, unloaded.floorCount(0, 0), "Unloaded cannot be declared as having 'no floor'");
    }
}
