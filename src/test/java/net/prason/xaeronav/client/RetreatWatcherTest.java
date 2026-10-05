package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class RetreatWatcherTest {

    private static final BlockPos GOAL = new BlockPos(0, 64, 0);

    private static BlockPos northOf(int blocks) {
        return new BlockPos(0, 64, blocks);
    }

    @Test
    void staysQuietWhileApproaching() {
        RetreatWatcher watcher = new RetreatWatcher();
        for (int distance = 400; distance >= 100; distance -= 20) {
            assertNull(watcher.observe(northOf(distance), GOAL), "Stays quiet while getting closer: " + distance);
        }
    }

    @Test
    void staysQuietForAnOrdinaryDetour() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        // Detours around the edge of a lava sea or the void are progressing correctly while moving away from the destination
        assertNull(watcher.observe(northOf(160), GOAL));
    }

    @Test
    void reportsALargeRetreatWithWhereItTurnedBack() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        RetreatWatcher.Retreat retreat = watcher.observe(northOf(200), GOAL);
        assertNotNull(retreat);
        assertEquals(northOf(100), retreat.closestAt());
        assertEquals(100.0, retreat.closest());
        assertEquals(200.0, retreat.distance());
        assertEquals(100.0, retreat.retreated());
    }

    @Test
    void reportsAgainOnlyAfterRetreatingFurther() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        assertNotNull(watcher.observe(northOf(200), GOAL));
        assertNull(watcher.observe(northOf(210), GOAL), "Doesn't write so finely that it slices up the same retreat");
        assertNotNull(watcher.observe(northOf(240), GOAL));
    }

    @Test
    void countsTheNextRetreatFromTheNewClosestPoint() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        assertNotNull(watcher.observe(northOf(200), GOAL));
        // Getting closer again after turning back makes that the new closest approach
        watcher.observe(northOf(50), GOAL);
        assertNull(watcher.observe(northOf(120), GOAL));
        RetreatWatcher.Retreat again = watcher.observe(northOf(140), GOAL);
        assertNotNull(again);
        assertEquals(northOf(50), again.closestAt());
    }

    @Test
    void forgetsEverythingOnReset() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        watcher.reset();
        assertNull(watcher.observe(northOf(200), GOAL), "The first position after a reset is the closest approach itself");
    }

    @Test
    void acceptsAGuideThatEndsWithinAnOrdinaryDetour() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        // Detours around the edge of a lava sea measured at worst 67 blocks on the model. Let this through
        assertFalse(watcher.leadsAway(List.of(northOf(167)), GOAL));
    }

    @Test
    void refusesAGuideThatEndsFurtherThanTheClosestApproach() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(197), GOAL);
        // In-game 2026-09-19: at 197 blocks away, it was guided along a route whose end was 277 blocks away
        assertTrue(watcher.leadsAway(List.of(northOf(277)), GOAL));
    }

    @Test
    void hasNoBarBeforeTheFirstObservation() {
        // Rejecting before there's a reference would stop the first route from appearing
        assertFalse(new RetreatWatcher().leadsAway(List.of(northOf(9999)), GOAL));
    }

    @Test
    void movesTheBarWhenThePlayerGetsCloser() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(300), GOAL);
        assertFalse(watcher.leadsAway(List.of(northOf(370)), GOAL));
        watcher.observe(northOf(100), GOAL);
        assertTrue(watcher.leadsAway(List.of(northOf(370)), GOAL), "Getting closer moves the reference there too");
    }

    @Test
    void looksAtEveryPointOnTheGuideNotJustItsEnd() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(197), GOAL);
        // In-game 2026-09-19: the end was at 252 (inside the band), but the guidance moved away to 277 partway
        assertTrue(watcher.leadsAway(List.of(northOf(230), northOf(277), northOf(252)), GOAL));
    }
}
