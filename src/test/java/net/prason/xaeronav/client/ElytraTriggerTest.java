package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ElytraTriggerTest {

    private static final int REQUIRED_CLEARANCE = 4;
    private static final int HIGH_ABOVE_GROUND = 32;

    private final ElytraTrigger trigger = new ElytraTrigger();

    /**
     * Like bouncing on a one-block bridge, a glide check that holds for only a few ticks doesn't enter flight mode.
     *
     * <p>The point is checking with plenty of height (on a bridge = void directly below): height hysteresis can't
     * stop this case, so if time doesn't stop it, it gets through.
     */
    @Test
    void ignoresBriefGlidesFromJumping() {
        for (int tick = 0; tick < ElytraTrigger.SUSTAIN_TICKS - 1; tick++) {
            assertFalse(trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE),
                    "Sustained tick " + tick + ": entered flight mode too early");
        }
        // Landing resets the count
        assertFalse(trigger.update(false, 0, REQUIRED_CLEARANCE));
        assertFalse(trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE),
                "Entered on the first tick after bouncing again = the sustained count isn't being reset");
    }

    /** A real glide (sustained, with height) enters flight mode. */
    @Test
    void turnsOnForASustainedGlide() {
        for (int tick = 0; tick < ElytraTrigger.SUSTAIN_TICKS - 1; tick++) {
            trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE);
        }
        assertTrue(trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE));
    }

    /** Even if sustained, it doesn't enter when skimming the ground. */
    @Test
    void staysOffWhileHuggingTheGround() {
        for (int tick = 0; tick < ElytraTrigger.SUSTAIN_TICKS * 2; tick++) {
            assertFalse(trigger.update(true, REQUIRED_CLEARANCE - 1, REQUIRED_CLEARANCE),
                    "Sustained tick " + tick + ": entered");
        }
    }

    /** After entering, the threshold drops, so it doesn't flip back and forth while gliding over the boundary. */
    @Test
    void keepsGlidingBelowTheEntryClearance() {
        for (int tick = 0; tick < ElytraTrigger.SUSTAIN_TICKS; tick++) {
            trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE);
        }
        assertTrue(trigger.update(true, REQUIRED_CLEARANCE - 1, REQUIRED_CLEARANCE),
                "Exits at the same height it enters");
        assertFalse(trigger.update(true, 0, REQUIRED_CLEARANCE), "Doesn't exit even when dropping down to the ground");
    }

    /** Exits immediately when the glide ends (landing). */
    @Test
    void turnsOffAsSoonAsTheGlideEnds() {
        for (int tick = 0; tick < ElytraTrigger.SUSTAIN_TICKS; tick++) {
            trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE);
        }
        assertFalse(trigger.update(false, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE));
    }

    /** Even with the height-agnostic setting (0), the sustain condition remains. */
    @Test
    void stillRequiresSustainWhenClearanceIsNotChecked() {
        assertFalse(trigger.update(true, 0, 0));
        for (int tick = 1; tick < ElytraTrigger.SUSTAIN_TICKS; tick++) {
            trigger.update(true, 0, 0);
        }
        assertTrue(trigger.update(true, 0, 0));
    }
}
