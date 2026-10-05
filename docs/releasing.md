# Release process

## First-time setup

Create the XaeroNav projects on Modrinth and CurseForge. In the GitHub repository, register the following under
**Settings → Secrets and variables → Actions**.

| Type | Name | Value |
| --- | --- | --- |
| Variable | `MODRINTH_PROJECT_ID` | Modrinth project ID |
| Variable | `CURSEFORGE_PROJECT_ID` | CurseForge numeric project ID |
| Secret | `MODRINTH_TOKEN` | Modrinth Personal Access Token (Create versions, Read versions, Write versions) |
| Secret | `CURSEFORGE_TOKEN` | CurseForge API token |

The GitHub Release uses Actions' built-in `GITHUB_TOKEN`, so no additional GitHub token is needed.
Never write tokens or IDs into files in the repository.

## Every release

1. Write the public changes in `changelogs/<X.Y.Z>.md`. This becomes the shared body for Modrinth, CurseForge and the GitHub Release. If the file is missing or empty, the process doesn't start. `CHANGELOG.md` at the repository root only points to this directory, so it doesn't need updating per release.
2. Get the commit containing the changes onto `main`.
3. In GitHub Actions, run **Release → Run workflow** and enter `X.Y.Z`. Alternatively, push a `vX.Y.Z` tag to that commit.
4. After the build and the distribution JAR checks, each loader / Minecraft version is uploaded separately to Modrinth and CurseForge. Once all 10 succeed, the GitHub Release is published directly, without a draft.

Published JAR names are `xaeronav-X.Y.Z-<loader>-<minecraft>.jar`. Unlike regular development builds, they carry no commit hash.
Version numbers on Modrinth and CurseForge are `X.Y.Z-<loader>-<minecraft>`, so the 5 don't collide within the same project.

If some jobs fail during publishing, use **Re-run failed jobs** from the same Actions run. Starting a new run that includes the successful uploads may upload the same version twice. Right after a network error, check whether the upload was created on the target site before re-running.

To check the configuration locally, put the already-published target JARs in `build/libs`, set placeholder IDs and tokens, and pass `-Ppublish_dry_run=true`. A dry run doesn't perform the actual upload.
