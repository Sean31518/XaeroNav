package net.prason.xaeronav.pathfinding.world;

/**
 * The animal the player is riding (a horse, donkey, mule, camel...), as the search sees it: how much room it needs,
 * how high it steps, and how fast it runs.
 *
 * <p>Everything is in whole cells, rounded up, so a route planned for the mount never squeezes it through a gap it
 * doesn't fit. The cost is a little conservative in narrow places (a horse 1.4 blocks wide fits a 2-wide corridor
 * when it walks the seam between two cells, which a cell-centred footprint can't express), never optimistic.
 *
 * @param footprintRadius cells the body reaches beyond the centre cell in each horizontal direction (1 for a horse:
 *                        1.4 blocks wide overhangs its cell by 0.2 on each side)
 * @param bodyHeight      cells of headroom the mount's own body needs above its feet
 * @param riderHeight     cells of headroom needed in the centre column for the rider on top
 * @param stepHeight      whole blocks it walks up without jumping (horses: 1)
 * @param ticksPerBlock   ticks to run one block on level ground
 */
public record Mount(int footprintRadius, int bodyHeight, int riderHeight, int stepHeight, double ticksPerBlock) {

    /**
     * Blocks per tick per point of the movement speed attribute on plain ground. Measured against the player: 0.1
     * (walking) is 4.317 blocks/s = 0.216 blocks/tick; horses in the wiki's speed table come out at the same ratio.
     */
    static final double BLOCKS_PER_TICK_PER_SPEED = 2.13;

    /** Fastest a mount is assumed to be (ticks per block). Guards against a modded or broken attribute value. */
    static final double MIN_TICKS_PER_BLOCK = 1.0;

    /** Where the rider sits, as a share of the mount's height (vanilla horses seat the rider at about 3/4). */
    private static final double SEAT_HEIGHT_SHARE = 0.75;
    private static final double RIDER_HEIGHT = 1.8;
    private static final double EPSILON = 1.0e-3;

    /** From the ridden entity's hitbox, step height and movement speed attribute. */
    public static Mount of(double width, double height, double stepHeight, double speedAttribute) {
        int radius = width / 2.0 > 0.5 + EPSILON ? (int) Math.ceil(width / 2.0 - 0.5 - EPSILON) : 0;
        int body = (int) Math.ceil(height - EPSILON);
        int rider = (int) Math.ceil(height * SEAT_HEIGHT_SHARE + RIDER_HEIGHT - EPSILON);
        int step = (int) Math.floor(stepHeight + EPSILON);
        double perTick = speedAttribute * BLOCKS_PER_TICK_PER_SPEED;
        double ticks = perTick > 0.0 ? Math.max(MIN_TICKS_PER_BLOCK, 1.0 / perTick) : Double.POSITIVE_INFINITY;
        return new Mount(radius, Math.max(1, body), Math.max(body, rider), step, ticks);
    }
}
