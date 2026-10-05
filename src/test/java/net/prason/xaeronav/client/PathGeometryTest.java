package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Geometry of the route baked for rendering.
 *
 * <p>This only checks <b>the point where the passed section is trimmed</b>. Water sections are folded into one line
 * even if they aren't straight, so the step positions before folding lie off the folded line; using them as the cut point
 * would make the near end of the line swing in a different direction with every step.
 */
class PathGeometryTest {

    @Test
    void theCutPointStaysOnTheLine() {
        double[] out = new double[3];

        // A raw step position 1 block off to the side of the chord (0,0,0)-(10,0,0)
        PathGeometry.projectOntoSegment(4.0, 0.0, 1.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, out);

        assertEquals(4.0, out[0], 1.0e-9);
        assertEquals(0.0, out[1], 1.0e-9);
        assertEquals(0.0, out[2], 1.0e-9, "brought back onto the chord");
    }

    @Test
    void theCutPointDoesNotRunOffTheEnds() {
        double[] out = new double[3];

        PathGeometry.projectOntoSegment(-5.0, 0.0, 0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, out);

        assertEquals(0.0, out[0], 1.0e-9, "not placed before the section start (would cut into the previous section)");
    }
}
