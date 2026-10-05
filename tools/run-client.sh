#!/usr/bin/env bash
set -euo pipefail

node="${1:-}"
# The list of nodes lives only in settings.gradle.kts. Only check the name's shape and let Gradle reject nodes that don't exist
if [[ ! "$node" =~ ^[0-9]+(\.[0-9]+)+-(fabric|forge|neoforge)$ ]]; then
    echo "Usage: $0 <MC version>-<fabric|forge|neoforge> [gradle args...]  (e.g. 1.21.1-neoforge)" >&2
    exit 2
fi
shift

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

# Passing both tasks to the same Gradle invocation makes Gradle 9 reject stonecutterMerge's
# update of src/ as an implicit dependency of compileJava. With separate processes, the second
# configuration treats the switched sources as normal inputs.
./gradlew ":stonecutterSwitchTo$node"
# macOS's stock bash 3.2 treats an empty "$@" as unset under set -u, so expand it with ${@+"$@"}
exec ./gradlew ":$node:runClient" ${@+"$@"}
