package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.prason.xaeronav.pathfinding.world.FakeCells;
import org.junit.jupiter.api.Test;

/**
 * Guards the rule that "cells the path plans to dig are assumed passable whether blocked or open".
 *
 * <p>Rejecting blocked ones makes dig-and-climb paths get rejected on every check, and since the search outputs the
 * same path again, full replans continue forever (110 seconds and 34 times in a real-game log, caused by a single
 * gravel cell). Rejecting open ones throws away paths of hundreds of steps every time the player digs as instructed
 * (real-game Nether: 723 steps -> 39 steps). However, a cell that lava flowed into after digging is not passable;
 * in the Nether, lava flowing into cells not planned for digging shows up repeatedly in real-game logs.
 *
 * <p>{@code stepFailure} itself requires a {@code Level} (i.e. bootstrapping Minecraft's registries), so this
 * looks only at the core of the decision.
 */
class PathValidatorDigCellsTest {

    private static final FakeCells CELLS = FakeCells.of(0, 0, 0, "#.L");
    private static final long STONE = CELLS.cell(0, 0, 0);
    private static final long AIR = CELLS.cell(1, 0, 0);
    private static final long LAVA = CELLS.cell(2, 0, 0);

    @Test
    void plannedDigStillSolidIsNotBlocked() {
        assertFalse(PathValidator.bodyCellBlocked(STONE, true));
    }

    @Test
    void plannedDigAlreadyDugIsNotBlocked() {
        assertFalse(PathValidator.bodyCellBlocked(AIR, true));
    }

    @Test
    void plannedDigFilledWithLavaIsBlocked() {
        assertTrue(PathValidator.bodyCellBlocked(LAVA, true));
    }

    @Test
    void solidCellNobodyPlansToDigIsBlocked() {
        assertTrue(PathValidator.bodyCellBlocked(STONE, false));
    }

    @Test
    void lavaCellNobodyPlansToDigIsBlocked() {
        assertTrue(PathValidator.bodyCellBlocked(LAVA, false));
    }
}
