# ADR-003: Loader, Xaero hook, and distribution contracts

- Status: Accepted
- Scope: supported loader nodes, optional Xaero integration, CI, and release artifacts

## Decision

Minecraft version and loader nodes are built from a single shared source with Stonecutter. The node list and dependency versions
aren't defined redundantly in separate places; `settings.gradle.kts` and `stonecutter.properties.toml` are canonical.

Xaero's World Map and Xaero's Minimap are optional dependencies. Even without Xaero, in-world paths and the HUD work.
The Xaero integration mixins are `required=false`, so an injection failure caused by a change in the external mod doesn't stop XaeroNav itself from starting.
Instead of silencing injection failures, they are detected by the in-game health display and the runtime CI's positive assertions.

## Invariants

- The Fabric jar contains `fabric.mod.json`, the NeoForge jar `neoforge.mods.toml` (`mods.toml` for NeoForge 20.4),
  and the Forge jar only `mods.toml` as loader metadata.
- Forge distribution jars include `MixinConfigs` in the manifest regardless of version. Don't rely solely on
  `[[mixins]]` in `mods.toml`.
- Forge jars running in the SRG namespace (1.20.4 and earlier) include a refmap. Don't carry the same assumption over to
  Forge 1.21.1, which runs on official mappings.
- Distribute the merged jar that includes the MixinExtras bundled for Forge; don't distribute the slim jar.
- Xaero's minimum supported version matches the version whose actual injection targets were checked for each mixin. Don't widen the lower bound by guesswork.
- Releases run `verifyDistribution` in a per-node job, checking each jar's name, version, metadata, manifest and refmap before publishing. The jar count is checked when creating the GitHub Release, which collects the artifacts of all jobs.
- CI builds all nodes in a matrix, and checks the runtime contract with client runtime and Forge dedicated-server smoke tests.
- External GitHub Actions are pinned to commit SHAs, and build jobs aren't given repository write permission.

## Client-only contract

XaeroNav is client-only and doesn't need to be installed on servers. Fabric restricts it to the client via metadata,
and NeoForge via the entrypoint's dist setting. Forge's `@Mod` has no equivalent dist argument, so
the structure is kept such that client classes aren't loaded early even if the outer entrypoint is loaded on a dedicated server.

Forge startup compatibility when the jar is mistakenly placed on a dedicated server is protected by the CI smoke test using the distribution jar.

## Verification

- Release build matrix (`verifyDistribution` with `-Pxaeronav.onlyNodes=<node>`): each node's distribution jar contract. Before publishing, confirms that CI on the same commit is green for tests
- Build matrix in `.github/workflows/ci.yml`: compiling all nodes
- Client runtime matrix in the same workflow: Minecraft startup and Xaero hook application
- Forge dedicated-server smoke matrix: preventing early loading of client classes

## Code map

- `settings.gradle.kts`
- `stonecutter.properties.toml`
- `build.*.gradle.kts`
- `stonecutter.gradle.kts`
- `src/main/java/net/prason/xaeronav/platform/`
- `src/main/java/net/prason/xaeronav/mixin/xaero/`
- `src/main/resources/META-INF/`
- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`

For per-loader steps for adding nodes and known version differences, see the [multiloader guide](../multiloader.md).
