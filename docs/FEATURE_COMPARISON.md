# Expressive, Lawnchair and Launcher3

Expressive Launcher builds on two substantial upstream projects. Launcher3 supplies
the Home application foundation; Lawnchair adds extensive customization and
Pixel-inspired features. Expressive combines that work with a focused settings
experience, dedicated widget corner radius and layout controls, instant settings search,
reliable backup restore, root QuickSwitch support, targeted behavior fixes, and verified GitHub delivery.

This comparison describes **Expressive Launcher 2.0.8**, the **Lawnchair 16 baseline used by
Expressive** (`eed2baf4efe4cf49540cf4ec474942dc743b83cc`), and **AOSP Launcher3 as a
platform foundation**. Upstream projects evolve independently.

## Feature map

| Capability | AOSP Launcher3 | Lawnchair 16 baseline | **Expressive Launcher 2.0.8** |
| --- | --- | --- | --- |
| **Home foundation** | Core implementation | Builds on Launcher3 | **Retains the authoritative Launcher3 foundation** |
| **Platform compatibility** | Depends on AOSP revision | Android 16-derived baseline | **Android 12 through Android 17 (API 31–37) with v1/v2/v3 signatures** |
| **Material 3 Expressive Theming** | Platform theming foundation | Included | **Included through Lawnchair with dynamic wallpaper palettes** |
| **Icon customization** | Platform defaults and extension points | Extensive icon controls | **Lawnicons as default with system fallback, adaptive masks, and calendar icons** |
| **Widget customization** | Fixed system widget bounds | Basic widget support | **Dedicated Widget settings: 0–40 dp custom corner radius slider, padding factor, force rounded** |
| **Settings experience** | Platform settings | Lawnchair preferences | **Pixel-first dashboard, Advanced grouping, animated category glyphs, and Settings Search** |
| **Settings search** | None | None | **Instant multi-token search across all 12 preference sections with deep links** |
| **At a Glance** | Extension points; provider dependent | Included | **Included, with date-first fallback and weather setup recovery** |
| **Google Discover** | Overlay integration points | Feed-provider integration | **Guided setup with bundled background support component** |
| **Backup and restore** | System backup / restore | Launcher3 backup | **Nova backup import with permanent persistence across reboots** |
| **System Recents & Gestures** | Privileged system integration | Optional QuickSwitch path | **Safe dual-mode: clean unrooted operation + full Quickstep/QuickSwitch recents when rooted** |
| **Update delivery** | System / distributor | Lawnchair release channels | **Verified GitHub channels (Release & QA) with automated hash/signature validation** |

## What Expressive adds and refines in 2.0.8

1. **Broad Android 12+ Compatibility**: Lowered minimum SDK to 31 with full APK Signature Schemes v1, v2, and v3, ensuring smooth installation on Android 12 through Android 17 (Baklava).
2. **Dedicated Widget Controls**: Dynamic corner radius slider (0..40 dp) enforced via `RoundedCornerEnforcement`, padding factor adjustment (0%..200%), force-rounded corners, and resize overrides under **Settings > Widgets**.
3. **Settings Search**: Fast multi-token search bar indexing titles, summaries, and categories across all preference screens.
4. **Quickstep & QuickSwitch Root Support**: Silky system recents integration for rooted devices in `/system/priv-app`, protected by `STATUS_BAR_SERVICE` permission checks to avoid any battery drain or crashes on unrooted devices.
5. **Nova Backup Persistence**: Restored Nova Launcher backups permanently persist across device restarts.
6. **Stability Fixes**: Resolved workspace crash when resizing grid or changing display settings with active widgets.
