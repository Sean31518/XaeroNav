package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
*///?}
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.xaero.XaeroHookHealth;
import net.prason.xaeronav.util.GameCompat;

/**
 * Guidance display at the top of the screen. Shows actions needed soon and the remaining distance and time.
 *
 * <p>The search is cut off at a cap on expanded nodes, so for distant destinations the path ends partway.
 * Unless that is made explicit here, it just looks like the line breaks off in the middle of nowhere.
 */
public final class NavHud {

    private static final int MARGIN_TOP = 6;
    private static final int LINE_HEIGHT = 11;
    private static final int PADDING_X = 6;
    private static final int PADDING_Y = 4;

    private static final int BACKGROUND_COLOR = 0x90000000;
    private static final int PRIMARY_COLOR = 0xFFFFFFFF;
    private static final int SECONDARY_COLOR = 0xFFB0B0B0;
    private static final int WARNING_COLOR = 0xFFFFC24D;
    private static final double ACTION_NOTICE_BLOCKS = 12.0;

    private final List<Component> lines = new ArrayList<>(4);
    private final List<Integer> colors = new ArrayList<>(4);

    // Whether there's a stretch worth warning about changes only when the path changes. The HUD is drawn every frame,
    // so scan all steps only once per path
    private final PathCache<PathSuffixes> suffixes = new PathCache<>();
    private final GoalEta eta = new GoalEta();

    public void render(
            //? if >=1.20 {
            GuiGraphics graphics
            //?} else {
            /*PoseStack graphics
            *///?}
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || ClientCompat.hudHidden(mc) || !XaeroNavConfig.INSTANCE.hudEnabled()) {
            return;
        }
        // Fetch once here and read only this instance afterwards. Calling individual getters many times during
        // rendering lets a worker callback interleave and, for a single frame, display "a combination that never
        // existed at any moment" (e.g. a new goal with an old stuck reason)
        PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
        if (view.goal() == null) {
            return;
        }

        lines.clear();
        colors.clear();
        PathResult result = view.currentResult();
        PathfindingState.StuckReason stuck = view.stuckReason();
        if (view.arrived()) {
            add(TextCompat.translatable("hud.xaeronav.arrived"), PRIMARY_COLOR);
        } else if (view.flying() && view.skyPillar() != null) {
            // Under open sky, draw no line; show only the direction and distance to the descent point (pillar)
            BlockPos pillar = view.skyPillar();
            boolean atGoal = pillar.getX() == view.goal().getX() && pillar.getZ() == view.goal().getZ();
            add(TextCompat.translatable(atGoal ? "hud.xaeronav.sky_goal" : "hud.xaeronav.sky_descent",
                    bearingArrow(mc, pillar), horizontalDistance(mc, pillar)), PRIMARY_COLOR);
            if (!atGoal) {
                add(TextCompat.translatable("hud.xaeronav.direct_distance",
                        straightDistance(mc, view.goal())), SECONDARY_COLOR);
            }
        } else if (view.flying()) {
            // Failing to draw an air path (no way through within the loaded range) is different from no guidance
            // at all. Using the same wording as "no path" for the former makes it look like the same failure as on the ground
            add(view.flightRoute().isEmpty()
                    ? TextCompat.translatable("hud.xaeronav.flying_no_route")
                    : TextCompat.translatable("hud.xaeronav.flying"), SECONDARY_COLOR);
            add(TextCompat.translatable("hud.xaeronav.direct_distance",
                    straightDistance(mc, view.goal())), SECONDARY_COLOR);
            int climb = upcomingClimb(view.flightRoute());
            if (climb >= CLIMB_NOTICE_BLOCKS) {
                // Climbing is the only point where the player is required to act. Without rockets the only option is
                // trading speed for altitude, and seeing "climb" from the line alone may come too late
                add(TextCompat.translatable("hud.xaeronav.flight_climb", climb), WARNING_COLOR);
            }
        } else if (result == null || result.steps().isEmpty()) {
            if (stuck != null) {
                addUnreachable(stuck);
            } else {
                // "No path" is only the result of this search. The next search may find one, so phrase it
                // differently from the conclusion (addUnreachable)
                add(view.computing()
                        ? TextCompat.translatable("hud.xaeronav.searching")
                        : TextCompat.translatable("hud.xaeronav.no_route"), SECONDARY_COLOR);
            }
            if (stuck == null) {
                // Show a rough time estimate even before a path comes out. The first search takes a few seconds
                double ticks = eta.ticks(mc.player.blockPosition(), view.goal(), view.coarseRouteWaypoints());
                add(TextCompat.translatable("hud.xaeronav.direct_distance_eta",
                        straightDistance(mc, view.goal()), time(NavGuidance.estimateSeconds(ticks))), SECONDARY_COLOR);
            } else {
                add(TextCompat.translatable("hud.xaeronav.direct_distance",
                        straightDistance(mc, view.goal())), SECONDARY_COLOR);
            }
        } else {
            // Even if a partial path is shown, say first if it's known not to lead to the destination. This path
            // is "as far as we can go", not a continuation of the guidance, so silently showing only the turns
            // means you only notice after walking to the dead end
            if (stuck != null) {
                addUnreachable(stuck);
            }
            boolean climbing = view.climbingToSurface();
            if (climbing) {
                // Indicate this is a relay path to first get to the surface, not the actual destination.
                // Without it, it's unclear why you're being guided in a direction different from the destination
                add(TextCompat.translatable("hud.xaeronav.climbing_to_surface"), SECONDARY_COLOR);
            }
            if (view.rerouted()) {
                // Show why the guidance suddenly changed. Without it, the road you were walking just
                // seems to vanish
                add(TextCompat.translatable("hud.xaeronav.rerouted"), WARNING_COLOR);
            }
            boolean endsAtDestination = view.currentPathEndsAtDestination();
            // Don't show time to a destination known to be unreachable. Only the time to the end of the solid line
            boolean estimateBeyond = !endsAtDestination && stuck == null;
            double beyondTicks = estimateBeyond
                    ? eta.ticks(result.steps().get(result.steps().size() - 1).pos(), view.goal(),
                    view.coarseRouteWaypoints())
                    : 0.0;
            NavGuidance guidance = NavGuidance.forPath(result, mc.player.blockPosition(), beyondTicks);
            PathSuffixes ahead = suffixes.get(result, PathSuffixes::new);
            int from = PathProgress.INSTANCE.indexFor(result) + 1;
            PathSuffixes.Action next = ahead.nextAction(from);
            String endpoint = guidance.nearEnd ? endpointKey(climbing, endsAtDestination, stuck != null) : null;
            if (next != null && ahead.distanceToAction(from) <= ACTION_NOTICE_BLOCKS) {
                add(TextCompat.translatable(next.key()), PRIMARY_COLOR);
                endpoint = null;
            } else if (endpoint != null) {
                add(TextCompat.translatable(endpoint), PRIMARY_COLOR);
            }
            add(TextCompat.translatable(remainingKey(endsAtDestination, estimateBeyond),
                    guidance.remainingBlocks, time(guidance.remainingSeconds)), SECONDARY_COLOR);
            // The path's color alone doesn't convey "take out the boat here". Noticing only after reaching the
            // shore invalidates the guidance up to that point, premise and all.
            // Not shown while riding: it would keep prompting for preparation already done
            if (ahead.usesBoat(from) && !ChunkView.ridingBoat(mc.player)) {
                add(TextCompat.translatable("hud.xaeronav.boat_ahead"), SECONDARY_COLOR);
            }
            // Paths the inventory can't cover come out through the relaxation ladder that drops the budget (when there's no other way).
            // Stay quiet while it's enough: in The End nearly every path involves placing blocks, so always showing it
            // would make it meaningless as a warning. Creative can place with an empty inventory, so it isn't counted
            if (!GameCompat.abilities(mc.player).instabuild) {
                int needed = ahead.placements(from);
                int available = ChunkView.countPlaceableBlocks(mc.player);
                if (needed > available) {
                    add(TextCompat.translatable("hud.xaeronav.blocks_short", needed, available), WARNING_COLOR);
                }
            }
            if (ahead.hasRisk(from, PathRisk.DROWNING)) {
                // The line's color alone doesn't convey "you'll run out of breath". It must be known before diving
                add(TextCompat.translatable("hud.xaeronav.drowning"), WARNING_COLOR);
            }
            if (ahead.hasRisk(from, PathRisk.MLG_REQUIRED)) {
                // This stretch needs input the moment you land, so noticing on arrival is too late
                add(TextCompat.translatable("hud.xaeronav.mlg_required"), WARNING_COLOR);
            }
            if (ahead.hasRisk(from, PathRisk.FALL_DAMAGE)) {
                add(TextCompat.translatable("hud.xaeronav.fall_damage"), WARNING_COLOR);
            }
            if (ahead.hasRisk(from, PathRisk.SNEAK_OVER_MAGMA)) {
                // Noticing after stepping on it is too late (stepping on it while running sets you on fire instantly)
                add(TextCompat.translatable("hud.xaeronav.sneak_over_magma"), WARNING_COLOR);
            }
            // If already judged stuck, "the rest will be calculated" is a lie (it's stuck precisely because there's
            // known to be no way beyond). While "guidance continues" is shown before the end, it'd also say the same thing twice
            if (!guidance.complete && stuck == null && !"hud.xaeronav.route_continues".equals(endpoint)) {
                add(TextCompat.translatable("hud.xaeronav.incomplete"), SECONDARY_COLOR);
            }
        }

        // This is the only place to notice that mixins applied but nothing is drawn on the map.
        // The path is shown, so staying quiet makes it look like "that's just how it is" rather than "only the map integration broke"
        if (XaeroHookHealth.worldMapRenderBroken()) {
            add(TextCompat.translatable("hud.xaeronav.hook_render_missing"), WARNING_COLOR);
        }

        draw(graphics, mc.font);
    }

    private void add(Component line, int color) {
        lines.add(line);
        colors.add(color);
    }

    /**
     * Shows the conclusion "the destination can't be reached", with its reason and what to do.
     *
     * <p>The key point is separating the conclusion from the reason. The conclusion alone doesn't tell you what
     * to do, and the reason alone doesn't tell you "whether the search is still running or has stopped". That
     * it has stopped (and the condition for resuming) belongs on the conclusion side: waiting on without knowing it has stopped is the worst loss.
     */
    private void addUnreachable(PathfindingState.StuckReason reason) {
        add(TextCompat.translatable("hud.xaeronav.unreachable"), WARNING_COLOR);
        add(TextCompat.translatable(PathfindingState.stuckHintKey(reason)), SECONDARY_COLOR);
    }

    /** HUD aggregates from each index to the end, built only once per path change. */
    static final class PathSuffixes {
        enum Action {
            DIG("hud.xaeronav.action_dig"),
            PLACE("hud.xaeronav.action_place"),
            JUMP("hud.xaeronav.action_jump"),
            CLIMB("hud.xaeronav.action_climb"),
            DISMOUNT("hud.xaeronav.action_dismount");

            private final String key;

            Action(String key) {
                this.key = key;
            }

            String key() {
                return key;
            }
        }

        private final int[] riskMasks;
        private final boolean[] boats;
        private final int[] placements;
        private final int[] nextActionSteps;
        private final Action[] actions;
        private final double[] blocks;

        PathSuffixes(PathResult result) {
            List<PathStep> steps = result.steps();
            riskMasks = new int[steps.size() + 1];
            boats = new boolean[steps.size() + 1];
            placements = new int[steps.size() + 1];
            nextActionSteps = new int[steps.size() + 1];
            actions = new Action[steps.size()];
            blocks = new double[steps.size()];
            nextActionSteps[steps.size()] = -1;
            for (int i = 1; i < steps.size(); i++) {
                blocks[i] = blocks[i - 1] + Math.sqrt(steps.get(i - 1).pos().distSqr(steps.get(i).pos()));
            }
            for (int i = steps.size() - 1; i >= 0; i--) {
                PathStep step = steps.get(i);
                riskMasks[i] = riskMasks[i + 1] | (1 << step.risk().ordinal());
                boats[i] = boats[i + 1] || step.boating();
                placements[i] = placements[i + 1] + (step.bridging() ? 1 : 0);
                actions[i] = step.digging() ? Action.DIG
                        : step.bridging() ? Action.PLACE
                        : step.movement() == MovementType.JUMP ? Action.JUMP
                        : step.climbing() ? Action.CLIMB
                        : step.movement() == MovementType.DISMOUNT ? Action.DISMOUNT : null;
                nextActionSteps[i] = actions[i] != null ? i : nextActionSteps[i + 1];
            }
        }

        boolean hasRisk(int from, PathRisk risk) {
            return (riskMasks[index(from)] & (1 << risk.ordinal())) != 0;
        }

        boolean usesBoat(int from) {
            return boats[index(from)];
        }

        int placements(int from) {
            return placements[index(from)];
        }

        Action nextAction(int from) {
            int step = nextActionSteps[index(from)];
            return step < 0 ? null : actions[step];
        }

        double distanceToAction(int from) {
            int step = nextActionSteps[index(from)];
            return step < 0 ? Double.POSITIVE_INFINITY : blocks[step] - blocks[Math.max(0, index(from) - 1)];
        }

        private int index(int from) {
            return Math.max(0, Math.min(from, riskMasks.length - 1));
        }
    }

    /**
     * Only when the displayed solid line reaches the actual destination can the distance be called simply "remaining". Otherwise the distance is to the end of the solid line,
     * and the time is to the destination if {@code toDestination} (beyond the solid line is an estimate), otherwise to the end of the solid line.
     */
    static String remainingKey(boolean endsAtDestination, boolean toDestination) {
        return endsAtDestination ? "hud.xaeronav.remaining"
                : toDestination ? "hud.xaeronav.path_remaining_eta" : "hud.xaeronav.path_remaining";
    }

    /**
     * Picks guidance text that doesn't misrepresent what the path's end means. Even for a reached relay path, its end is not the destination.
     * The end of a path judged stuck is a dead end, so it doesn't say "guidance continues" ({@code null}).
     */
    static String endpointKey(boolean climbing, boolean endsAtDestination, boolean stuck) {
        if (climbing) {
            return "hud.xaeronav.surface_ahead";
        }
        if (endsAtDestination) {
            return "hud.xaeronav.arriving";
        }
        return stuck ? null : "hud.xaeronav.route_continues";
    }

    /** Notify when at least this much climbing lies ahead (blocks). */
    private static final int CLIMB_NOTICE_BLOCKS = 12;

    /** Total height to be climbed over the rest of the path. Descents aren't subtracted (what you descend isn't climbed back). */
    private static int upcomingClimb(FlightRoute route) {
        List<Vec3> points = route.points();
        double climb = 0.0;
        for (int i = FlightProgress.INSTANCE.segmentFor(route); i + 1 < points.size(); i++) {
            climb += Math.max(0.0, points.get(i + 1).y - points.get(i).y);
        }
        return (int) Math.round(climb);
    }

    /** Direction of {@code target} as seen from the facing direction. An 8-way arrow, straight up being ahead. */
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    static String bearingArrow(double fromX, double fromZ, float yaw, double toX, double toZ) {
        // Minecraft's yaw is 0 at south (+Z) and increases clockwise (west is 90)
        double targetYaw = Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
        double relative = ((targetYaw - yaw) % 360.0 + 360.0) % 360.0;
        return ARROWS[(int) Math.round(relative / 45.0) % ARROWS.length];
    }

    private static String bearingArrow(Minecraft mc, BlockPos target) {
        return bearingArrow(mc.player.getX(), mc.player.getZ(), GameCompat.yaw(mc.player),
                target.getX() + 0.5, target.getZ() + 0.5);
    }

    private static int horizontalDistance(Minecraft mc, BlockPos target) {
        return (int) Math.round(Math.hypot(target.getX() + 0.5 - mc.player.getX(),
                target.getZ() + 0.5 - mc.player.getZ()));
    }

    /** Straight-line distance to the destination. Even when no path can be made, at least shows whether it's far or near. */
    private static int straightDistance(Minecraft mc, BlockPos goal) {
        if (goal == null) {
            return 0;
        }
        double dx = goal.getX() + 0.5 - mc.player.getX();
        double dy = goal.getY() - mc.player.getY();
        double dz = goal.getZ() + 0.5 - mc.player.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    private static Component time(int seconds) {
        return seconds >= 60
                ? TextCompat.translatable("hud.xaeronav.minutes_seconds", seconds / 60, seconds % 60)
                : TextCompat.translatable("hud.xaeronav.seconds", seconds);
    }

    private void draw(
            //? if >=1.20 {
            GuiGraphics graphics,
            //?} else {
            /*PoseStack graphics,
            *///?}
            Font font) {
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        //? if >=1.20 {
        int centerX = graphics.guiWidth() / 2;
        //?} else {
        /*int centerX = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2;
        *///?}
        int boxWidth = width + PADDING_X * 2;
        int boxHeight = (lines.size() - 1) * LINE_HEIGHT + font.lineHeight + PADDING_Y * 2;
        //? if >=1.20 {
        graphics.fill(centerX - boxWidth / 2, MARGIN_TOP, centerX + boxWidth / 2, MARGIN_TOP + boxHeight,
                BACKGROUND_COLOR);
        //?} else {
        /*GuiComponent.fill(graphics, centerX - boxWidth / 2, MARGIN_TOP,
                centerX + boxWidth / 2, MARGIN_TOP + boxHeight, BACKGROUND_COLOR);
        *///?}

        int y = MARGIN_TOP + PADDING_Y;
        for (int i = 0; i < lines.size(); i++) {
            //? if >=26.1 {
            /*graphics.centeredText(font, lines.get(i), centerX, y, colors.get(i));
            *///?} else if >=1.20 {
            graphics.drawCenteredString(font, lines.get(i), centerX, y, colors.get(i));
            //?} else {
            /*GuiComponent.drawCenteredString(graphics, font, lines.get(i), centerX, y, colors.get(i));
            *///?}
            y += LINE_HEIGHT;
        }
    }
}
