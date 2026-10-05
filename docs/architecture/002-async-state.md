# ADR-002: Asynchronous state ownership

- Status: Accepted
- Scope: route computation, cancellation, and rendering snapshots

## Decision

Preparing search input by touching the Minecraft world and the heavy pathfinding sit on opposite sides of a thread boundary.

- The client thread handles references to the player and world, building `ChunkView` from loaded chunks,
  fetching Xaero map data, and state transitions.
- `PathfindingExecutor` workers take exclusive ownership of the view they're given and do cell reads, A*, and hazard annotation.
- A new request, clear, logout, or dimension change advances the generation. A completed future may publish its result only
  when the generation it captured matches the current one.
- HUD, world rendering, and map rendering don't combine individual mutable fields; they read one
  immutable `NavigationView` per frame.

## Invariants

- `CellSource` and `ChunkView` are owned by a single worker. Never pass the same caching instance to concurrent
  searches.
- When running the regular budget and the deep fallback concurrently, give each its own view.
- Don't modify Minecraft's UI, player, or world directly from a future's completion callback. Pass the needed results
  through a thread-safe handoff and apply them on the client tick.
- Cancellation is done to reduce work, but correctness is guarded by the generation check. Even if an interrupt is late,
  an old result must never come back. On transitions where nobody is left to receive the result (clear, arrival, takeoff),
  besides advancing the generation, stop running searches with `PathfindingExecutor#cancelAll`.
- When changing the state included in `NavigationView`, reissue the snapshot on every write path.
- After logout, keep no view holding the goal, route, generation, Xaero's temporary waypoint, or world.

## Why

Searches can take hundreds of ms or more, and running them on the client thread stalls rendering and input. On the other hand,
Minecraft's chunk management and Xaero's map API can't be driven from arbitrary workers. And merely
cancelling a future doesn't prevent the race where an old task just about to finish overwrites the new state.

Combining explicit ownership, generations, and immutable snapshots offloads the heavy work while keeping the displayed state
consistent.

## Verification

- `PathfindingExecutor*Test`: regular search, deep fallback, loosening, coarse guide
- `SearchHandoverTest`: on goal changes, clear, and takeoff/landing, old results don't arrive and running
  searches (including the second deep fallback search) stop reading their views
- `DiagnosticJobRunnerTest`: generation handling for diagnostic searches
- `StuckTrackerTest`: stuck detection extracted from the state machine
- `NavHud*Test`, `MapPathOverlayTest`, `PathGeometryTest`: consumers of the published snapshot
- `CliffSpliceTest`, `SeamRepairSectionTest`, `SpliceJoinTest`: path replacement using async results

`SearchHandoverTest` reproduces the same parts and the same steps as `PathfindingState`; `PathfindingState`
itself isn't tested because it needs a Minecraft client. When splitting the state machine further,
add integration tests with a fake scheduler/executor first.

## Code map

- `client/PathfindingState.java`
- `client/FlightNavState.java`
- `pathfinding/async/PathfindingExecutor.java`
- `pathfinding/async/DiagnosticJobRunner.java`
- `pathfinding/world/CellSource.java`
- `pathfinding/world/ChunkView.java`
