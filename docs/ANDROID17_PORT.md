# Android 17 Launcher3 port

This project is based on Lawnchair's Launcher3 fork. Android 17 must be ported as an
upstream tree change, not copied into the project as a second launcher implementation.
This document records the verified provenance, merge strategy, gates, and definition
of done. It does not claim that the Android 17 port is complete.

## Provenance

| Source | Revision |
| --- | --- |
| Lawnchair `16-dev` baseline | `eed2baf4efe4cf49540cf4ec474942dc743b83cc` |
| Lawnchair merge of Android 16 r3 | `dd4a08be61c75943da1301f72ad5f81eaf98c71a` |
| AOSP `android-16.0.0_r3` | `d38622493a79982c1bd7df5eeeb3e5f4edc4114d` |
| AOSP `android-17.0.0_r1` annotated tag | `ee30795afbaf8b968cdbad48bdb7c5272f42f46d` |
| AOSP `android-17.0.0_r1` peeled commit | `c612e6ece389f21c40f8cb9cd9a4b44239f00009` |
| Verified local vendor bridge | `bd184f26a75183bcffaa5dfda9346caf02fa3334` |

The canonical remotes are:

- Lawnchair: `https://github.com/LawnchairLauncher/lawnchair.git`
- Launcher3: `https://android.googlesource.com/platform/packages/apps/Launcher3`

The natural merge base between the Lawnchair baseline and Android 17 r1 is
`f8d94de717303505d0a41b8b5ce156cfa4e18aa6`, which predates the Android 16 r3
integration. A direct `git merge android-17.0.0_r1` therefore replays unrelated release
branch history and produced 427 conflicts in the audit. Do not use that merge.

## Vendor-bridge procedure

Create a transparent bridge commit whose tree exactly matches Android 17 r1, whose
first parent is Android 16 r3, and whose second parent is the official Android 17 r1
commit. The first parent gives Git the correct vendor delta; the second retains official
AOSP history and provenance.

```sh
AOSP16_COMMIT=d38622493a79982c1bd7df5eeeb3e5f4edc4114d
AOSP17_COMMIT=c612e6ece389f21c40f8cb9cd9a4b44239f00009
AOSP17_TREE="$(git rev-parse "${AOSP17_COMMIT}^{tree}")"

AOSP17_BRIDGE="$({
  printf '%s\n\n' 'Vendor bridge: Android 17 Launcher3 r1 tree'
  printf '%s\n' 'Tree matches android-17.0.0_r1; first-parent delta is android-16.0.0_r3..android-17.0.0_r1.'
} | git commit-tree "$AOSP17_TREE" -p "$AOSP16_COMMIT" -p "$AOSP17_COMMIT")"

git branch codex/vendor-aosp-launcher3-android17-r1 "$AOSP17_BRIDGE"
```

Before merging, both checks must succeed:

```sh
test "$(git merge-base HEAD codex/vendor-aosp-launcher3-android17-r1)" = "$AOSP16_COMMIT"
git diff --quiet codex/vendor-aosp-launcher3-android17-r1 "$AOSP17_COMMIT"
```

Merge the bridge only on the dedicated Android 17 port branch. Resolve conflicts by
subsystem and commit each coherent gate separately. Never resolve the model, widget,
QSB, or profile migrations with a blanket `ours` or `theirs` selection.

## Verified conflict inventory

An explicit Android 16 r3 base produced 248 conflicting paths:

| Area | Paths |
| --- | ---: |
| Quickstep | 118 |
| Launcher3 core under `src/` | 117 |
| Resources | 7 |
| Modules/build files | 3 |
| Tests | 2 |
| `src_no_quickstep` | 1 |

Conflict types were 196 content conflicts, 51 modify/delete conflicts, one
rename/delete conflict, and one file-location conflict. Many modify/delete cases are
semantic replacements, including Java-to-Kotlin migrations in the icon cache, model
writer/database, display, popup, and widget systems. Android 17 also replaces the old
QSB and widget-picker paths with new architectures. These require re-porting Lawnchair
hooks to the new APIs rather than preserving deleted Android 16 classes.

## Required subsystem gates

Each gate must compile and have its focused tests passing before the next begins:

1. **Baseline and provenance:** preserve the branded Lawnchair build, exact upstream
   revisions, Apache notices, and an Android 16 behavior baseline.
2. **Build bridge:** translate Android 17 Soong source groups into Gradle modules;
   update framework/SystemUI/WMShell compatibility inputs, generated flags, Dagger,
   Compose, `modules/concurrent`, widget-picker, and workspace-functions dependencies.
3. **Model and persistence:** port `LauncherProvider`, `LauncherModel`, database helpers,
   `IModelWriter`/model tasks, restore, grid migration, and multi-profile lifecycle.
   Launcher3 remains the only workspace database and model authority.
4. **Launcher surfaces:** port `Launcher`, `Workspace`, `CellLayout`, All Apps, folders,
   drag/drop, state transitions, previews, and device-profile calculations.
5. **Icons and theme:** reattach Lawnchair icon packs, Lawnicons fallback, Minimal icons,
   shapes, icon cache invalidation, wallpaper colors, and Material 3 Expressive tokens to
   the Android 17 APIs.
6. **Search, widgets, and organizer:** port home/All Apps QSB providers, the Android 17
   widget picker, widget binding/configuration/previews, settings previews, and the
   Launcher3 organizer/workspace-functions flow.
7. **Profiles and compatibility:** validate Personal, Work, clone, and Private Space
   behavior plus supported older-Android compatibility paths.
8. **Standard-Home policy and QA:** enforce the product policy below, then run device,
   accessibility, performance, restore, and process-death gates.

## Standard-Home product policy

Expressive Launcher L3 is an ordinary Home-role APK. SystemUI owns Recents and system
gesture navigation. The standard release must not depend on root, QuickSwitch, being a
privileged/system app, or being selected as the system Recents component.

Lawnchair currently uses `QuickstepLauncher` as an implementation base even when Recents
is unavailable. Retaining shared Quickstep classes is acceptable only when the release
manifest does not register the Recents `TouchInteractionService`, request the protected
Quickstep/Recents permissions, or expose root/QuickSwitch setup as a supported product
path. All such code must remain disabled when Lawnchair is not the system Recents
component. A future privileged flavor, if ever created, must be a separate artifact.

## Completion criteria

The Android 17 port is complete only when all of the following are true:

- The vendor bridge has the two parent commits above, its tree is byte-for-byte equal to
  Android 17 r1, and the resolved integration retains that provenance in branch history.
- No conflict markers, Android 16 compatibility placeholders masquerading as Android 17
  implementations, or duplicate bespoke launcher model/database classes remain.
- The Gradle build consumes every required Android 17 source group or a documented,
  tested third-party-safe adapter; it does not silently compile new code against stale
  Android 16 hidden-API/SystemUI inputs.
- Launcher3 owns workspace persistence, folders, widgets, drag/drop, All Apps, profiles,
  restore, and state transitions; Lawnchair customizations enter through explicit
  providers, delegates, or preference hooks.
- The merged release manifest satisfies the Standard-Home policy, and installation does
  not require root or privileged permission grants.
- Unit and instrumentation tests for each gate pass, followed by Pixel 8 Pro validation
  of Home, All Apps, search, icons, folders, widgets, wallpaper/theme response, settings
  previews, supported profiles, rotation, process death, and restart.
- Reduced motion, font scaling, TalkBack, RTL, drawer scroll behavior, startup, frame
  pacing, and memory checks meet the project acceptance criteria.
