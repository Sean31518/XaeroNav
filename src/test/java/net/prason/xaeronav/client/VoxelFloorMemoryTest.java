package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How {@link NetherVoxelGuide} packs the floors it remembers. If it can't round-trip with negative coordinates or at the Y extremes,
 * remembered floors turn into other locations and the grid breaks.
 */
class VoxelFloorMemoryTest {

    private static void roundTrips(int x, int z, int y, boolean lava) {
        long packed = NetherVoxelGuide.packFloor(x, z, y, lava);
        assertEquals(x, NetherVoxelGuide.unpackX(packed), "X");
        assertEquals(z, NetherVoxelGuide.unpackZ(packed), "Z");
        assertEquals(y, NetherVoxelGuide.unpackY(packed), "Y");
        assertEquals(lava, NetherVoxelGuide.unpackLava(packed), "lava");
    }

    @Test
    void roundTripsNegativeCoordinates() {
        roundTrips(-591, 962, 0, true);
        roundTrips(264, -256, 127, false);
        roundTrips(0, 0, 64, true);
    }

    @Test
    void roundTripsTheEdgesOfEveryField() {
        // The Nether's world border (±29,999,984 ÷ 8) and the Overworld's height extremes
        roundTrips(-3_749_998, 3_749_998, -64, false);
        roundTrips(3_749_998, -3_749_998, 319, true);
    }

    @Test
    void differentFloorsStayDifferent() {
        long lava = NetherVoxelGuide.packFloor(10, 20, 30, true);
        long solid = NetherVoxelGuide.packFloor(10, 20, 30, false);
        assertFalse(lava == solid, "lava or not must give different values");
        assertTrue(NetherVoxelGuide.packFloor(10, 20, 30, true)
                == NetherVoxelGuide.packFloor(10, 20, 30, true), "the same floor must give the same value");
    }
}
