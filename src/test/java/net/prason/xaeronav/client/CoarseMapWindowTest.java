package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/** Checks the read range cap, and that the timing field isn't just passed through. */
class CoarseMapWindowTest {

    @Test
    void anOversizedSpanSkipsTheReadEntirelyAndReportsZeroMillis() {
        // An X distance far beyond MAX_SPAN_CHUNKS (1024). Neither the map nor Xaero is called at all, so
        // this can be verified without a Minecraft/Xaero runtime.
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(1024 * 16, 64, 0);
        CoarseMapWindow.Window window = CoarseMapWindow.read(from, to, 1);
        assertNull(window.map());
        assertEquals(0L, window.readMillis());
    }
}
