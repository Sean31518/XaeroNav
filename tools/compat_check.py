#!/usr/bin/env python3
"""Creates Prism Launcher instances for checking in-game the builds whose node jar is also used for Minecraft versions
older than the node (minecraftCompatFor).

    tools/compat_check.py            # create the instances (for launching by hand to check)
    tools/compat_check.py --auto     # create them, then launch each in turn and print a table of whether the Xaero hooks applied and ran
    tools/compat_check.py --auto 1.21.9-fabric   # filter by name

The distribution jar and the node's Xaero (including xaerolib) come from Gradle's `stageRuntimeTestMods`.
Only for versions using a Xaero build that is no longer updated is Xaero taken from Modrinth.
--auto installs mc-runtime-test (the same one as CI's launch test), enters a world, and reads the runtime hook probe results.
Afterwards it removes mc-runtime-test and the probe, returning the instance to a hand-playable state.
"""
import argparse
import json
import shutil
import subprocess
import sys
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
PRISM_APP = Path("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher")
PRISM_DATA = Path.home() / "Library/Application Support/PrismLauncher"
CACHE = PROJECT / "build/compat-check"
FABRIC_LOADER = "0.19.5"
RUNTIME_TEST_RELEASE = "4.5.1"
PROBE_ARG = "-Dxaeronav-ci.runtimeHookProbe=true"
OFFLINE_NAME = "XaeroNavCheck"
# For a version used for the first time, the game won't start until Prism finishes fetching assets (this can take about 10 minutes)
STARTUP_TIMEOUT_SECONDS = 1800
TIMEOUT_SECONDS = 600


@dataclass(frozen=True)
class Target:
    minecraft: str
    loader: str
    node: str
    # For Fabric, the fabric-api version; for Forge and NeoForge, the loader's own version
    version: str
    # The Xaero version on Modrinth. If None, the current version collected by `stageRuntimeTestMods` is used
    worldmap: str | None = None
    minimap: str | None = None
    # Older Xaero lines (World Map 1.39.x, Minimap 25.2.x) don't use xaerolib, and the staged
    # current xaerolib rejects that Minecraft version, so it isn't installed
    xaerolib: bool = True
    # For versions without an mc-runtime-test release, use the neighboring version's, which accepts a wider range
    runtime_test_minecraft: str | None = None

    @property
    def name(self) -> str:
        return f"{self.minecraft}-{self.loader}"

    @property
    def instance_id(self) -> str:
        return f"xaeronav-check-{self.name}"


# List the lower versions alongside the node's own version for comparison (the Accessor and rendering mixins also change behavior on the node's own version)
TARGETS = [
    Target("1.20.2", "fabric", "1.20.2-fabric", "0.91.6+1.20.2"),
    Target("1.20.2", "forge", "1.20.2-forge", "48.1.0"),
    Target("1.20.5", "fabric", "1.20.6-fabric", "0.97.8+1.20.5", runtime_test_minecraft="1.20.6"),
    Target("1.20.6", "fabric", "1.20.6-fabric", "0.100.8+1.20.6"),
    Target("1.20.6", "forge", "1.20.6-forge", "50.2.10"),
    Target("1.20.6", "neoforge", "1.20.6-neoforge", "20.6.141"),
    # The World Map that works with Minimap 25.3.2 goes up to 1.41.2 (1.42.0 and later reject the old Minimap)
    Target("1.20.3", "fabric", "1.20.4-fabric", "0.91.1+1.20.3",
           worldmap="fabric-1.20.4-1.41.2", minimap="25.3.2_Fabric_1.20.4"),
    Target("1.20.4", "fabric", "1.20.4-fabric", "0.97.3+1.20.4"),
    Target("1.21", "fabric", "1.21.1-fabric", "0.102.0+1.21",
           worldmap="fabric-1.21.1-1.41.2", minimap="25.3.2_Fabric_1.21"),
    Target("1.21.1", "fabric", "1.21.1-fabric", "0.116.7+1.21.1"),
    Target("1.21.3", "fabric", "1.21.3-fabric", "0.114.1+1.21.3"),
    Target("1.21.3", "forge", "1.21.3-forge", "53.1.12"),
    Target("1.21.3", "neoforge", "1.21.3-neoforge", "21.3.97"),
    Target("1.21.6", "fabric", "1.21.8-fabric", "0.128.2+1.21.6",
           worldmap="1.39.10_Fabric_1.21.6", minimap="25.2.7_Fabric_1.21.6", xaerolib=False),
    Target("1.21.7", "fabric", "1.21.8-fabric", "0.129.0+1.21.7",
           worldmap="1.39.12_Fabric_1.21.7", minimap="25.2.10_Fabric_1.21.7", xaerolib=False),
    Target("1.21.8", "fabric", "1.21.8-fabric", "0.136.1+1.21.8"),
    Target("1.21.9", "fabric", "1.21.10-fabric", "0.134.1+1.21.9",
           worldmap="1.39.17_Fabric_1.21.9", minimap="25.2.15_Fabric_1.21.9", xaerolib=False, runtime_test_minecraft="1.21.10"),
    Target("1.21.10", "fabric", "1.21.10-fabric", "0.138.4+1.21.10"),
    Target("26.1", "fabric", "26.1.2-fabric", "0.155.3+26.1.2"),
    Target("26.1.1", "fabric", "26.1.2-fabric", "0.155.3+26.1.2"),
    Target("26.1.2", "fabric", "26.1.2-fabric", "0.155.3+26.1.2"),
]


def download(url: str, dest: Path) -> Path:
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_suffix(".part")
        with urllib.request.urlopen(url) as response, open(tmp, "wb") as out:
            shutil.copyfileobj(response, out)
        tmp.rename(dest)
    return dest


def modrinth_file(project: str, version_number: str) -> Path:
    dest = CACHE / "modrinth" / f"{project}-{version_number}.jar"
    if dest.exists():
        return dest
    with urllib.request.urlopen(f"https://api.modrinth.com/v2/project/{project}/version") as response:
        versions = json.load(response)
    match = next((v for v in versions if v["version_number"] == version_number), None)
    if match is None:
        raise SystemExit(f"{version_number} not found in Modrinth project {project}")
    primary = next((f for f in match["files"] if f["primary"]), match["files"][0])
    return download(primary["url"], dest)


def runtime_test_jar(target: Target) -> Path:
    minecraft = target.runtime_test_minecraft or target.minecraft
    loader = "lexforge" if target.loader == "forge" else target.loader
    name = f"mc-runtime-test-{minecraft}-{RUNTIME_TEST_RELEASE}-{loader}-release.jar"
    url = f"https://github.com/headlesshq/mc-runtime-test/releases/download/{RUNTIME_TEST_RELEASE}/{name}"
    return download(url, CACHE / "mc-runtime-test" / name)


def stage_nodes(nodes: list[str]) -> None:
    tasks = [f":{node}:stageRuntimeTestMods" for node in nodes]
    # Stonecutter stops at the configuration stage unless the active node is included in the configuration
    active = (PROJECT / ".sc_active_version").read_text().strip()
    only = ",".join(sorted(set(nodes) | {active}))
    subprocess.run(["./gradlew", *tasks, f"-Pxaeronav.onlyNodes={only}", "--console=plain", "-q"],
                   cwd=PROJECT, check=True)


def staged_mods(node: str) -> list[Path]:
    return sorted((PROJECT / "build/runtime-test" / node / "mods").glob("*.jar"))


def components(target: Target) -> list[dict]:
    result = [{"uid": "net.minecraft", "version": target.minecraft, "important": True}]
    if target.loader == "fabric":
        result += [
            {"uid": "net.fabricmc.intermediary", "version": target.minecraft, "dependencyOnly": True},
            {"uid": "net.fabricmc.fabric-loader", "version": FABRIC_LOADER},
        ]
    elif target.loader == "forge":
        result.append({"uid": "net.minecraftforge", "version": target.version})
    else:
        result.append({"uid": "net.neoforged", "version": target.version})
    return result


def java_path(minecraft: str) -> Path:
    # Prism's global default Java may be 17. Explicitly pick the matching one from the Mojang runtimes Prism has
    # Prism only accepts the Java major versions the version metadata allows (passing 21 to 1.20.4 or earlier makes it refuse to launch)
    if minecraft.startswith("26."):
        runtime = "java-runtime-epsilon"
    elif minecraft.startswith("1.21") or minecraft in ("1.20.5", "1.20.6"):
        runtime = "java-runtime-delta"
    else:
        runtime = "java-runtime-gamma"
    path = PRISM_DATA / "java" / runtime / "bin/java"
    if not path.exists():
        raise SystemExit(f"{runtime} not found. Launch that Minecraft version in Prism once to install Java: {path}")
    return path


def ensure_options(game_dir: Path) -> None:
    # When the window loses focus the pause menu opens, and mc-runtime-test keeps waiting in the world.
    # The first-launch welcome screen also stops Quick Play
    wanted = {"pauseOnLostFocus": "false", "onboardAccessibility": "false"}
    options = game_dir / "options.txt"
    lines = options.read_text().splitlines() if options.exists() else []
    lines = [line for line in lines if line.split(":", 1)[0] not in wanted]
    lines += [f"{key}:{value}" for key, value in wanted.items()]
    options.write_text("\n".join(lines) + "\n")


def write_instance(target: Target, auto: bool) -> Path:
    root = PRISM_DATA / "instances" / target.instance_id
    mods = root / "minecraft/mods"
    mods.mkdir(parents=True, exist_ok=True)
    ensure_options(root / "minecraft")
    pack = root / "mmc-pack.json"
    # On launch, Prism appends resolved dependencies (LWJGL and such) to this file. Rewriting it every time can, as on first launch,
    # make the metadata fetch miss the launch and fail with "game not found"
    if not pack.exists():
        pack.write_text(json.dumps({"formatVersion": 1, "components": components(target)}, indent=4))
    config = {
        "ConfigVersion": "1.3",
        "InstanceType": "OneSix",
        "name": f"XaeroNav check {target.minecraft} {target.loader}",
        "iconKey": "default",
        "OverrideJavaLocation": "true",
        "JavaPath": str(java_path(target.minecraft)),
        "OverrideJavaArgs": "true" if auto else "false",
        "JvmArgs": PROBE_ARG if auto else "",
    }
    (root / "instance.cfg").write_text("[General]\n" + "".join(f"{k}={v}\n" for k, v in config.items()))

    for old in mods.glob("*.jar"):
        old.unlink()
    for jar in staged_mods(target.node):
        replaced = (target.worldmap is not None and jar.name.startswith("xaeroworldmap-")) \
            or (target.minimap is not None and jar.name.startswith("xaerominimap-")) \
            or (not target.xaerolib and jar.name.startswith("xaerolib-"))
        if not replaced:
            shutil.copy2(jar, mods)
    if target.worldmap is not None:
        shutil.copy2(modrinth_file("xaeros-world-map", target.worldmap), mods)
    if target.minimap is not None:
        shutil.copy2(modrinth_file("xaeros-minimap", target.minimap), mods)
    if target.loader == "fabric":
        shutil.copy2(modrinth_file("fabric-api", target.version), mods)
    if auto:
        shutil.copy2(runtime_test_jar(target), mods)
    return root


HOOKS = ["WORLD_MAP_RENDER", "MINIMAP_RENDER", "WORLD_MAP_KEY", "WORLD_MAP_MENU", "WAYPOINT_MENU"]


def quit_prism() -> None:
    # While running, Prism keeps the loaded instances' settings in memory and overwrites instance.cfg on every save.
    # When rewriting Java or JVM arguments from outside, it must be closed first or the changes get reverted
    if subprocess.run(["pgrep", "-f", str(PRISM_APP)], capture_output=True).returncode != 0:
        return
    print("Closing Prism Launcher (to rewrite the instance settings)", flush=True)
    subprocess.run(["osascript", "-e", 'tell application "Prism Launcher" to quit'], capture_output=True, check=False)
    for k in range(30):
        # If refused, e.g. by an exit confirmation dialog, send the process a termination signal
        if k == 5:
            subprocess.run(["pkill", "-TERM", "-f", str(PRISM_APP)], check=False)
        if subprocess.run(["pgrep", "-f", str(PRISM_APP)], capture_output=True).returncode != 0:
            return
        time.sleep(1)
    raise SystemExit("Prism Launcher won't close. Close it by hand and try again")


def launch(target: Target, offline: bool) -> None:
    # Prism doesn't fetch libraries for offline launches. A version launched for the first time is launched with Prism's default account
    extra = ["--offline", OFFLINE_NAME] if offline else []
    subprocess.Popen([str(PRISM_APP), "--launch", target.instance_id, *extra],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def stop_game(target: Target) -> None:
    # The check game has this instance's path in its arguments. If one left over from a previous aborted run is still around, the new launch
    # is ignored by Prism and no log can be captured
    subprocess.run(["pkill", "-f", f"instances/{target.instance_id}/"], check=False)
    time.sleep(2)


def game_running(target: Target) -> bool:
    return subprocess.run(["pgrep", "-f", f"instances/{target.instance_id}/"], capture_output=True).returncode == 0


# Lines that determine the result. How mc-runtime-test exits differs by version (1.20.2 closes without printing "No tests found"), so it isn't relied on
DECIDED = ("XAERONAV_RUNTIME_HOOK_PROBE_SUCCESS", "XAERONAV_RUNTIME_HOOK_PROBE_FAILED",
           "---- Minecraft Crash Report ----", "Incompatible mods found", "ModLoadingException")


def run_instance(target: Target, root: Path, offline: bool) -> str:
    log = root / "minecraft/logs/latest.log"
    loader_log = root / "minecraft/fabricloader.log"
    stop_game(target)
    log.unlink(missing_ok=True)
    loader_log.unlink(missing_ok=True)
    launch(target, offline)
    relaunched = False
    deadline = time.time() + STARTUP_TIMEOUT_SECONDS
    started = False
    gone = 0
    text = ""
    while time.time() < deadline:
        time.sleep(3)
        if not log.exists():
            # The first time a version is launched, Prism's metadata fetch may miss the launch and it may start without the game itself.
            # The fetch has completed by then, so relaunching gets through
            if not relaunched and loader_log.exists() and "couldn't locate the game" in loader_log.read_text(errors="replace"):
                loader_log.unlink()
                relaunched = True
                time.sleep(5)
                launch(target, offline)
            continue
        if not started:
            started = True
            deadline = time.time() + TIMEOUT_SECONDS
        text = log.read_text(errors="replace")
        if any(marker in text for marker in DECIDED):
            break
        # If it closed without printing the marker (finished without hanging), decide there
        gone = 0 if game_running(target) else gone + 1
        if gone >= 3:
            break
    else:
        stop_game(target)
        return "timed out (see the log: " + str(log) + ")"

    time.sleep(5)
    stop_game(target)
    text = log.read_text(errors="replace")

    if "XAERONAV_RUNTIME_HOOK_PROBE_SUCCESS" in text:
        return "OK (all hooks ran)"
    failed = [line for line in text.splitlines() if "XAERONAV_RUNTIME_HOOK_PROBE_FAILED" in line]
    if failed:
        return "NG: " + failed[0].split("XAERONAV_RUNTIME_HOOK_PROBE_FAILED", 1)[1].strip()
    lines = text.splitlines()
    for marker in ("Incompatible mods found", "ModLoadingException"):
        hit = next((k for k, line in enumerate(lines) if marker in line), None)
        if hit is not None:
            detail = next((line.strip() for line in lines[hit:] if line.strip().startswith("- Mod ")), lines[hit].strip())
            return f"NG (failed before launch): {detail}"
    if "---- Minecraft Crash Report ----" in text:
        return f"NG (crash): {log}"
    missing = [hook for hook in HOOKS if f"XAERONAV_HOOK_EXECUTED {hook}" not in text]
    return f"NG: not run {missing} ({log})"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--auto", action="store_true", help="launch and check the hook results")
    parser.add_argument("--offline", action="store_true",
                        help="launch without using an account (only if that version has been launched online once and its libraries are present)")
    parser.add_argument("names", nargs="*", help="target names (e.g. 1.21.9-fabric). All if omitted")
    args = parser.parse_args()

    targets = [t for t in TARGETS if not args.names or t.name in args.names]
    unknown = set(args.names) - {t.name for t in targets}
    if unknown:
        raise SystemExit(f"unknown targets: {sorted(unknown)}. Available: {[t.name for t in TARGETS]}")
    if not PRISM_APP.exists():
        raise SystemExit(f"Prism Launcher not found: {PRISM_APP}")

    stage_nodes(sorted({t.node for t in targets}))

    quit_prism()
    roots = {target.name: write_instance(target, args.auto) for target in targets}
    results = {}
    if args.auto:
        for target in targets:
            print(f"{target.name}: launching…", flush=True)
            results[target.name] = run_instance(target, roots[target.name], args.offline)
            print(f"{target.name}: {results[target.name]}", flush=True)
        quit_prism()
        for target in targets:
            write_instance(target, auto=False)
    else:
        for target in targets:
            print(f"{target.name}: {roots[target.name]}")

    if args.auto:
        print("\n| Version | Result |\n|---|---|")
        for name, result in results.items():
            print(f"| {name} | {result} |")
    print("\nPrism Launcher now has the \"XaeroNav check …\" instances. Launch them by hand to play and check.")
    if any("NG" in r or "timed out" in r for r in results.values()):
        sys.exit(1)


if __name__ == "__main__":
    main()
