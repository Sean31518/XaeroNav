# ADR-001: Route pipeline

- Status: Accepted
- Scope: walking routes and their long-distance guidance

## Decision

XaeroNav handles long-distance walking routes in layers that differ in resolution and responsibility.

1. `CoarseRouter` (Layer 1) builds a sequence of chunk-level, big-picture intermediate targets from Xaero's map data.
2. `CorridorLegSolver` (Layer 2) refines each coarse leg into a block-resolution corridor based on surface data.
3. `AStarPathfinder` (Layer 3) uses the real blocks in loaded chunks to build the sequence of moves the player actually follows.

Where Layer 3 aims and what it uses as its guide (cost-to-go) depends on the dimension.

| Dimension | Aims at | Guide | Weight |
|---|---|---|---|
| Runs where the navigation graph has been built (all dimensions) | Final destination | Navigation graph (`NavGraph` / `WindowField`) | 1.0 |
| Runs where it isn't ready or usable (dimensions without a ceiling) | Layer 1/Layer 2 intermediate targets | Layer 1 built over the leg's box | Configured value |
| Runs where it isn't ready or usable (dimensions with a ceiling) | Final destination | 3D coarse layer (`VoxelCostToGo`) | Configured value |

In the Nether, the navigation graph isn't used until the 3D coarse layer has been built (with the geometric lower bound outside the window, it's worse than the 3D coarse layer alone).

### Navigation graph

Inside the loaded window (a square reaching 224 blocks horizontally from the player, or the render distance if that's smaller),
edges are derived per 16³ section **with exactly the same move generation as Layer 3**, and a reverse Dijkstra from the destination
builds the remaining cost. The ends of edges leaving the window and the window's rim are seeded with a "far estimate": Layer 1 in the
Overworld, the 3D coarse layer × 1.3 in the Nether (the 3D coarse layer runs at about 0.77× the true remaining cost), and in the End
or with no map, the straight-line distance to the destination (the geometric lower bound), which is only placed while the destination
is outside the window.

- A section holds only the shell within 8 horizontally and 2 vertically of naturally standable points (heights standable without
  digging or placing, and every depth of water).
- Sections are stored per destination, and only the band walked into is added, in parallel. Sections far from the window are dropped.
  Sections built while surrounding chunks were missing are rebuilt once more of them can be read.
- The guide is rebuilt on a worker every 8 blocks walked; until it's ready, navigation continues with the previous guide or the
  conventional search.
- At window 224 the graph and guide use up to about 270MB. If Java's heap limit (`Runtime#maxMemory`) is under 2.5GB, the window is
  160 (about 150MB). 192, in between, isn't chosen because its Nether worst case is worse than 160's. Heaps under 2GB aren't supported.
- If building fails, it isn't rebuilt for 30 seconds, and guidance uses the conventional search in the meantime.
- With a rebuilt guide, the rest of the drawn path is reviewed once (`RouteReview`). If the destination is inside the window and the
  cost along the line is at least 40 ticks higher than the guide's value, the path is redrawn. The path was drawn aiming outside the
  window by estimate and is afterwards only extended from its end, so even when the window advances and the estimate becomes
  accurate, the direction of the near part isn't corrected on its own.
- The search's box is cut to the same window, and covers the full height.

The output of Layers 1 and 2 is intermediate targets that tell Layer 3 the direction, not the path actually walked. Don't require
an exact match to an intermediate target's coordinates. Forcing a coarse cell's representative point causes unnecessary detours or
failures even when there's a passable spot within the cell. Layer 1 is drawn regardless of whether there's a guide, and is used for the map's dotted line, the HUD's travel time, and the estimate outside the window.

## Invariants

- Every layer's cost is in ticks, and the same move must not be priced inconsistently across layers.
- Layer 1's cost-to-go passed to Layer 3's heuristic must be a lower bound on the real cost. Changes that lose admissibility
  are forbidden, because they change A*'s optimality and search order.
- The navigation graph's guide **doesn't return 0 at points not in the graph**. Standing spots created by dug or placed blocks
  aren't in the graph, and returning 0 there attracts the search to them. Extend from nearby nodes' values, or else use the far estimate or the geometric lower bound.
- The far estimate **makes points it doesn't know infinite (unknown)**. A poor estimate is more harmful than placing nothing; in the End,
  putting Layer 1 on the window's rim is worse than the geometric lower bound.
- If the destination inside the window isn't connected to the shell, the navigation graph isn't used for that run (`WindowField#reachesGoal`).
  All values in the window would come only from the estimate outside the rim, and their scale would disagree with the accurate values inside the window.
- The search start not being connected to the shell alone isn't a reason to refuse. In the End, islands separated by voids wider than 16 blocks
  are normal, and refusing there to fall back to the conventional search makes things worse (1.022 -> 1.235×).
- The straight-line distance on the window's rim isn't placed when the destination is inside the window. An underestimate that knows nothing about outside the window attracts the search to the rim.
- The navigation graph's edges depend on the destination and the movement conditions (whether digging and placing are allowed). If either changes, it's rebuilt.
- Unknown chunks are not made impassable, since that would block progress toward destinations beyond unexplored land. On the other hand,
  so that known safe detours are never discarded, Layer 1 gives them a higher cost than known land.
- Layer 2 doesn't know the inventory, health, or exact block shapes. Final decisions that need that information, such as digging,
  placing blocks, or painful falls, are made only by Layer 3.
- Layer 1's intermediate target spacing is kept shorter than the detailed search distance Layer 3 solves at once.
- In the Nether, state is treated as `(chunkX, chunkZ, floor)`. Independent floors at the same XZ must not be connected as
  cheap steps.

## Why

Detailed search over long distances using only loaded chunks breaks off, because the terrain all the way to the destination doesn't exist.
Conversely, walking the coarse line derived from the map as-is can't make decisions about bridges, digging, collision, inventory, or
safety. Separating the big-picture direction from the actual moves lets guidance keep being extended while chunks stream in.

However, Layers 1 and 2 are "a different guess" that builds edges with a cost model different from Layer 3's; steering toward intermediate
targets is itself a detour, and tuning constants put a ceiling on quality. Within the loaded range, the real remaining cost can be built from
Layer 3's moves themselves, so that's left to the navigation graph. In the walk-through model (`NavGraphWalkBenchTest`, including rebuild delays),
Overworld wide long-distance went from 1.067/1.165× to 1.016/1.030× (routes starting in caves 1.029/1.058×), the End from 1.122× with one unreached route
to 1.013/1.029×, and the Nether from 1.048/1.104× with the 3D coarse layer alone to 1.013/1.023× (mean/worst, ratio to the full-visibility optimal path, window 160).
The review runs once the destination enters the window, so a narrow window notices detours later. In measurements comparing windows 160 and 224,
the Nether went from mean 1.044 -> 1.001 and worst 1.147 -> 1.003×, and the Overworld from 1.018 -> 1.009×. With 192 the Nether worst case was worse than 160, and with 240 one route among the End's outer islands no longer produced a path.

HPA*-style compression that keeps only representative points of 16³ sections forces detours through those points, making values non-smooth;
it dropped to 1.05-1.4×, so it wasn't adopted.

## Verification

- `GuideAdmissibilityTest`: admissibility of Layer 1's cost-to-go
- `WindowFieldTest`: the navigation graph's guide matches the optimal remaining cost, doesn't return 0 at points not in the graph,
  tells closed small rooms apart as unconnected, and isn't used with destinations not connected to the shell
- `CoarseRouterTest`, `CoarseWaypointFidelityTest`: Layer 1 routes and intermediate targets
- `CorridorWaypointsTest`, `SurfaceGridTest`: Layer 2 refinement
- `AStarPathfinderTest`, `PathOptimalityTest`, `LongRouteOptimalityTest`: Layer 3 moves and optimality
- `NetherCaveLayerSlabReproTest`, `NetherThinMapGuideTest`: dimensions with multiple floors
- `ProgressiveDiscoveryTest`, `ProgressiveWalkTerrainTest`: extension while terrain is loading
- `RouteReviewTest`: the review finds detours and leaves optimal paths alone
- `NavGraphWalkBenchTest` (`bench`, no assertions): quality, build time, and memory when walking all the way with the navigation graph

## Code map

- `pathfinding/coarse/CoarseRouter.java`
- `pathfinding/corridor/CorridorLegSolver.java`
- `pathfinding/astar/AStarPathfinder.java`, `pathfinding/astar/SectionMoves.java`
- `pathfinding/navgraph/NavGraph.java`, `pathfinding/navgraph/WindowField.java`, `pathfinding/navgraph/RouteReview.java`
- `client/NavGraphGuide.java`, `client/NetherVoxelGuide.java`
- `client/PathfindingState.java`
