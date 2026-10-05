package net.prason.xaeronav.pathfinding.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * Bit packing of {@link CellData}.
 *
 * <p>A cell is packed into a single {@code long}, so if a field's position shifts, you get accidents like "the dig cost
 * is read as a speed factor", where only the path silently breaks without any exception.
 *
 * <p>{@code flagsOf(BlockState)} requires bootstrapping Minecraft's registries, so it isn't touched here.
 * Only the packing and unpacking downstream of it is verified.
 */
class CellDataPackingTest {

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0, 2.0, 40.0, 123.5, 1000.0, 65535.0})
    void digTicksSurviveTheRoundTrip(double ticks) {
        long cell = CellData.withDigTicks(CellData.PRESENT, ticks);

        // Values are tick counts (tens to thousands), so float precision is enough
        assertEquals(ticks, CellData.digTicks(cell), 1.0e-3);
        assertTrue(CellData.present(cell), "packing the dig cost doesn't break the flags");
    }

    @Test
    void infeasibleIsPreservedExactly() {
        // Undiggable cells are represented by positive infinity. If a float round-trip turned it into a finite value,
        // paths that dig through bedrock or dig-forbidden blocks would appear
        long cell = CellData.withDigTicks(CellData.PRESENT, ActionCosts.INFEASIBLE);

        assertEquals(Double.POSITIVE_INFINITY, CellData.digTicks(cell));
        assertTrue(Double.isInfinite(CellData.digTicks(cell)));
    }

    @Test
    void flagsAndDigTicksDoNotOverlap() {
        // The dig cost can be read even with every flag set = the upper 32 bits and lower 32 bits are independent
        long allFlags = CellData.PRESENT | CellData.PASSABLE_EMPTY | CellData.WATER | CellData.LAVA
                | CellData.STANDABLE | CellData.FALLING_BLOCK | CellData.UNRESOLVED_SHAPE
                | CellData.CLIMBABLE | CellData.OPENABLE | CellData.COBWEB | CellData.HAZARD;
        long cell = CellData.withDigTicks(allFlags, 40.0);

        assertEquals(40.0, CellData.digTicks(cell), 1.0e-3);
        assertTrue(CellData.present(cell));
        assertTrue(CellData.passableEmpty(cell));
        assertTrue(CellData.water(cell));
        assertTrue(CellData.lava(cell));
        assertTrue(CellData.standable(cell));
        assertTrue(CellData.fallingBlock(cell));
        assertTrue(CellData.unresolvedShape(cell));
        assertTrue(CellData.climbable(cell));
        assertTrue(CellData.openable(cell));
        assertTrue(CellData.cobweb(cell));
        assertTrue(CellData.hazard(cell));
    }

    @Test
    void absentCellAnswersNoToEveryQuestion() {
        // Out-of-range and unloaded chunks must be treated on the safe side as "can't touch, can't stand, can't dig",
        // i.e. the path doesn't extend
        long absent = CellData.ABSENT;

        assertFalse(CellData.present(absent));
        assertFalse(CellData.passableEmpty(absent));
        assertFalse(CellData.water(absent));
        assertFalse(CellData.lava(absent));
        assertFalse(CellData.standable(absent));
        assertFalse(CellData.climbable(absent));
        assertFalse(CellData.occupiableWithoutDigging(absent));
        assertFalse(CellData.openable(absent));
        assertFalse(CellData.hazard(absent));
        assertEquals(1.0, CellData.speedFactor(absent), "unset reads as normal speed");
    }

    @Test
    void occupiableCoversAirWaterAndClimbables() {
        assertTrue(CellData.occupiableWithoutDigging(CellData.PRESENT | CellData.PASSABLE_EMPTY));
        assertTrue(CellData.occupiableWithoutDigging(CellData.PRESENT | CellData.WATER));
        assertTrue(CellData.occupiableWithoutDigging(CellData.PRESENT | CellData.CLIMBABLE));
        // Solids must be dug before the body can occupy them
        assertFalse(CellData.occupiableWithoutDigging(CellData.PRESENT | CellData.STANDABLE));
        // A closed door is "opened and passed through", so it isn't occupiable (handled separately as openable)
        assertFalse(CellData.occupiableWithoutDigging(CellData.PRESENT | CellData.OPENABLE));
    }
}
