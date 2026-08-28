# Expressive Launcher Pixel parity ledger

This ledger records verified Pixel Launcher behavior, the public-API-compatible Expressive
implementation, and validation evidence. Pixel-only private APIs and privileged system behavior are
out of scope for a third-party HOME app.

## Reference environment

- Reference date: 2026-08-28
- Latest public beta: Android 17 QPR2 Beta 3
- Guest build: `CP41.260731.005.B1` (`dev-keys`), Android security patch `2026-08-05`
- System image: `system-images;android-37.2-beta3;google_apis_playstore_ps16k;arm64-v8a`, revision 3
- AVD: `Pixel_8_Pro_Android_17_QPR2_Beta3`, Pixel 8 Pro, ARM64, 16 KB page size
- Android Emulator: 37.2.5.0, build 16079175
- Pixel Launcher: `com.google.android.apps.nexuslauncher`, versionCode 907, versionName `DEV`
- Rollback retained: the prior `Pixel_8_Pro` Android 17 QPR2 Beta 2 AVD and image were not removed

The guest build, package metadata, UI hierarchy, and screenshots are captured from the running AVD;
the AVD display name alone is not accepted as evidence.

## Implemented parity improvements

### 2026-08-28 — Date-first Smartspace fallback

**Observed gap.** On the same clean QPR2 Beta 3 guest, Pixel Launcher displayed `Fri, Aug 28` at the
top of the first home frame. Expressive 1.0.4 displayed a permanent local onboarding card instead
when Smartspacer was unavailable: `Welcome to Expressive Launcher L3` / `Swipe up to open the app
drawer`.

**Implementation.** The Expressive product now removes the local onboarding target and promotes the
weather/date target above setup prompts. Live weather, when available, uses the same promoted target;
battery, torch, Now Playing, setup, and other local targets remain available. The policy applies only
to the Expressive product and does not alter a healthy Smartspacer feed.

**Automated coverage.** Unit coverage verifies Expressive ordering, live-weather promotion, target
preservation, and unchanged non-Expressive behavior. Device coverage verifies that the built-in
fallback renders a non-empty date instead of onboarding when Smartspacer is absent.

**Device result.** Verified on `CP41.260731.005.B1`: the first frame and a persisted cold restart both
rendered `Fri, Aug 28`; the onboarding copy was absent. Cold/warm startup, home, drawer, search,
folder, widget picker, Wallpaper & style, rotation handling, feed gesture, and persistence passed
without a launcher crash, fatal exception, or ANR.

## Open limitations

- Expressive cannot use Pixel Launcher's private Smartspace, Google feed, or system-only recents APIs.
- Weather requires an available public/local provider; the deterministic fallback is the current date.
- The Android development image reports Pixel Launcher versionName `DEV`, so behavior and build ID
  are recorded together rather than treating that label as a production release version.
