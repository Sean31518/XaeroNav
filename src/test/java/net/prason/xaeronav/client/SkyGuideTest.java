package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.IntBinaryOperator;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/** Where to raise the light pillar under open sky, and the direction of the HUD arrow. */
class SkyGuideTest {

    private static final IntBinaryOperator FLAT_64 = (x, z) -> 64;

    @Test
    void standsOnTheGoalWhenTheRouteStaysOnTheSurface() {
        BlockPos goal = new BlockPos(300, 64, 0);
        List<BlockPos> route = List.of(new BlockPos(100, 64, 0), new BlockPos(200, 65, 0));

        assertEquals(goal, SkyGuide.descentPoint(goal, route, FLAT_64));
    }

    @Test
    void standsWhereTheRouteGoesUnderground() {
        BlockPos goal = new BlockPos(400, 20, 0);
        List<BlockPos> route = List.of(new BlockPos(100, 64, 0), new BlockPos(200, 64, 0),
                new BlockPos(260, 30, 0), new BlockPos(330, 22, 0));

        assertEquals(new BlockPos(200, 64, 0), SkyGuide.descentPoint(goal, route, FLAT_64));
    }

    @Test
    void standsAboveAnUndergroundGoalWithoutCaveData() {
        // Like a stronghold, only the goal is underground while the route runs along the surface. Raise it on the surface directly above the goal
        BlockPos goal = new BlockPos(400, 20, 0);
        List<BlockPos> route = List.of(new BlockPos(100, 64, 0), new BlockPos(200, 64, 0));

        assertEquals(new BlockPos(400, 64, 0), SkyGuide.descentPoint(goal, route, FLAT_64));
    }

    @Test
    void doesNotCallUnknownColumnsUnderground() {
        BlockPos goal = new BlockPos(400, 20, 0);
        List<BlockPos> route = List.of(new BlockPos(100, 10, 0));

        assertEquals(goal, SkyGuide.descentPoint(goal, route, (x, z) -> Integer.MIN_VALUE));
    }

    @Test
    void arrowPointsRelativeToWhereThePlayerFaces() {
        // Yaw 0 faces south (+Z). A goal to the south is ahead, west (-X) is right, east (+X) is left, north is behind
        assertEquals("↑", NavHud.bearingArrow(0, 0, 0f, 0, 100));
        assertEquals("→", NavHud.bearingArrow(0, 0, 0f, -100, 0));
        assertEquals("←", NavHud.bearingArrow(0, 0, 0f, 100, 0));
        assertEquals("↓", NavHud.bearingArrow(0, 0, 0f, 0, -100));
        // Facing west (yaw 90), a goal to the west is ahead
        assertEquals("↑", NavHud.bearingArrow(0, 0, 90f, -100, 0));
        assertEquals("↗", NavHud.bearingArrow(0, 0, -720f, -100, 100));
    }
}
