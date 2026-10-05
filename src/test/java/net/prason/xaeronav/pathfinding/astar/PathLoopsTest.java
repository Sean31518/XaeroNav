package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class PathLoopsTest {

    private static PathStep at(int x) {
        return new PathStep(new BlockPos(x, 64, 0), MovementType.TRAVERSE, 4.0,
                List.of(), List.of(), PathRisk.NONE, null);
    }

    /** A move that crosses by placing a block. Folding it would remove the footing too, so sections containing this move can't be folded. */
    private static PathStep bridgeAt(int x) {
        return new PathStep(new BlockPos(x, 64, 0), MovementType.TRAVERSE, 20.0,
                List.of(), List.of(), PathRisk.NONE, new BlockPos(x, 63, 0));
    }

    private static List<BlockPos> positions(List<PathStep> steps) {
        return steps.stream().map(PathStep::pos).toList();
    }

    @Test
    void keepsAPathThatNeverRevisitsACell() {
        List<PathStep> steps = List.of(at(1), at(2), at(3));
        PathLoops.Folded folded = PathLoops.fold(steps);
        assertFalse(folded.changed());
        assertEquals(steps, folded.steps());
        assertArrayEquals(new int[] {0, 1, 2}, folded.newIndex());
    }

    @Test
    void dropsTheStepsBetweenTwoVisitsToTheSameCell() {
        // 1->2->3->2->4 turns back at 2, so it passes through the same places as 1->2->4
        List<PathStep> folded = PathLoops.fold(List.of(at(1), at(2), at(3), at(2), at(4))).steps();
        assertEquals(List.of(new BlockPos(1, 64, 0), new BlockPos(2, 64, 0), new BlockPos(4, 64, 0)),
                positions(folded));
    }

    /** So section boundaries can be re-attached, indices of removed steps point at the surviving one. */
    @Test
    void mapsDroppedIndexesOntoTheSurvivingStep() {
        PathLoops.Folded folded = PathLoops.fold(List.of(at(1), at(2), at(3), at(2), at(4)));
        assertArrayEquals(new int[] {0, 1, 1, 1, 2}, folded.newIndex());
    }

    @Test
    void keepsALoopThatPlacedABlockTheLaterStepsStandOn() {
        List<PathStep> steps = List.of(at(1), at(2), bridgeAt(3), at(2), at(4));
        assertFalse(PathLoops.fold(steps).changed());
    }

    @Test
    void foldsRepeatedlyWhenALoopHidesAnotherLoop() {
        // 1->2->3->4->3->2->5. Folding the inner loop (turning back at 3) reveals the outer one (turning back at 2)
        List<PathStep> folded =
                PathLoops.fold(List.of(at(1), at(2), at(3), at(4), at(3), at(2), at(5))).steps();
        assertEquals(List.of(new BlockPos(1, 64, 0), new BlockPos(2, 64, 0), new BlockPos(5, 64, 0)),
                positions(folded));
        assertTrue(folded.size() == 3);
    }

    private static PathStep walk(int x, int z) {
        return new PathStep(new BlockPos(x, 64, z), MovementType.TRAVERSE, 4.0, List.of(), List.of(), PathRisk.NONE, null);
    }

    /** An extension (V shape) that turns back at the end of a path extending east, goes 10 blocks back west, then exits south. */
    @Test
    void findsAReturnThatRunsBesideTheWayOut() {
        List<PathStep> route = new ArrayList<>();
        for (int x = 0; x <= 40; x++) {
            route.add(walk(x, 0));
        }
        List<PathStep> tail = new ArrayList<>();
        for (int z = 1; z <= 10; z++) {
            tail.add(walk(40, z));
        }
        for (int x = 39; x >= 0; x--) {
            tail.add(walk(x, 10));
        }
        for (int z = 11; z <= 30; z++) {
            tail.add(walk(0, z));
        }
        PathLoops.Return found = PathLoops.widestReturn(route, tail, 0, 16, 6, 20, 3);
        assertNotNull(found);
        assertEquals(0, found.entry());
        BlockPos rejoin = tail.get(found.rejoin()).pos();
        assertTrue(Math.max(Math.abs(rejoin.getX()), Math.abs(rejoin.getZ())) <= 16);
    }

    @Test
    void findsNoReturnWhenTheTailKeepsGoing() {
        List<PathStep> route = new ArrayList<>();
        for (int x = 0; x <= 40; x++) {
            route.add(walk(x, 0));
        }
        List<PathStep> tail = new ArrayList<>();
        for (int x = 41; x <= 80; x++) {
            tail.add(walk(x, 0));
        }
        assertNull(PathLoops.widestReturn(route, tail, 0, 16, 6, 20, 3));
    }

    /** Loops returning to already-walked ground (before {@code fromIndex}) can't be cut off, so they aren't picked up. */
    @Test
    void ignoresAReturnToWhereThePlayerHasAlreadyWalked() {
        List<PathStep> route = new ArrayList<>();
        for (int x = 0; x <= 40; x++) {
            route.add(walk(x, 0));
        }
        List<PathStep> tail = new ArrayList<>();
        for (int x = 39; x >= 0; x--) {
            tail.add(walk(x, 5));
        }
        assertNull(PathLoops.widestReturn(route, tail, 38, 16, 6, 20, 3));
    }

    @Test
    void laterStepsDependOnABlockPlacedInTheCutSection() {
        List<PathStep> steps = List.of(at(1), bridgeAt(2), at(3), at(4),
                new PathStep(new BlockPos(2, 65, 0), MovementType.ASCEND, 8.0, List.of(), List.of(), PathRisk.NONE, null));
        // The block placed at 2, (2,63,0), isn't footing for a later move (the later move stands at (2,65,0), so its footing is (2,64,0))
        assertFalse(PathLoops.laterStepsDependOn(steps, 1, 2));
        List<PathStep> standing = List.of(at(1), bridgeAt(2), at(3),
                new PathStep(new BlockPos(2, 64, 0), MovementType.TRAVERSE, 4.0, List.of(), List.of(), PathRisk.NONE, null));
        assertTrue(PathLoops.laterStepsDependOn(standing, 1, 2));
    }

    @Test
    void laterStepsDependOnAHoleDugInTheCutSection() {
        BlockPos hole = new BlockPos(5, 64, 0);
        List<PathStep> steps = List.of(at(1),
                new PathStep(new BlockPos(2, 64, 0), MovementType.TRAVERSE, 20.0, List.of(), List.of(hole), PathRisk.NONE, null),
                at(3),
                new PathStep(hole, MovementType.TRAVERSE, 4.0, List.of(hole), List.of(), PathRisk.NONE, null));
        assertTrue(PathLoops.laterStepsDependOn(steps, 1, 2));
        assertFalse(PathLoops.laterStepsDependOn(steps, 0, 0));
    }
}
