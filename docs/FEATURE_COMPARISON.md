# Expressive, Lawnchair and Launcher3

Expressive Launcher builds on two substantial upstream projects. Launcher3 supplies
the Home application foundation; Lawnchair adds extensive customization and
Pixel-inspired features. Expressive combines that work with a focused settings
experience, its own Discover setup, targeted behavior fixes and verified GitHub
delivery.

This comparison describes **Expressive 1.0.12**, the **Lawnchair 16 baseline used by
Expressive** (`eed2baf4efe4cf49540cf4ec474942dc743b83cc`), and **AOSP Launcher3 as a
platform foundation**. It is not a benchmark or a claim about every release, fork,
OEM build or installation of these projects. Upstream evolves independently.

## Feature map

| Capability | AOSP Launcher3 | Lawnchair 16 baseline | **Expressive 1.0.12** |
| --- | --- | --- | --- |
| Home screens, app drawer, folders, widgets, drag and drop | Core implementation | Builds on Launcher3 | **Retains the same foundation** |
| Material 3 Expressive and wallpaper/system color theming | Platform theming foundation; varies by revision | Included | **Included through Lawnchair** |
| Icon packs, shapes, fonts and layout customization | Platform defaults and extension points | Extensive controls | **Retained, with Lawnicons as an optional first-run default** |
| At a Glance and optional Smartspacer integration | Smartspace extension points; provider dependent | Included | **Included, with date-first fallback and weather setup recovery** |
| Search across apps, contacts and web results | App search foundation; additional providers vary | Included | **Included, with clearer accessible contact actions** |
| File search | Depends on implementation and providers | Local search infrastructure | **User-selected folder access, plus supported media access** |
| Everyday settings grouped ahead of advanced customization | Platform settings | Lawnchair settings | **Pixel-inspired dashboard with Advanced grouping and animated category icons** |
| Google Discover beside Home | Overlay integration points; Google content is separate | Feed-provider integration | **Guided setup with bundled background support and an in-settings switch** |
| Update distribution | Supplied by the platform or distributor | Lawnchair's own release/nightly distribution | **Channel-specific GitHub checks, notifications, reminders and validated downloads** |
| Platform focus | Determined by the AOSP revision and system integration | Android 16 Launcher3-derived development baseline | **Android 17 / API 37 minimum, with focused Android 17 QA** |
| System Recents and navigation | Depends on privileged system integration | Optional QuickSwitch path on supported rooted configurations | **Standard Home app; Android SystemUI retains Recents and navigation** |

Shared features are intentional strengths. In particular, Material 3 Expressive,
At a Glance/Smartspacer, global search and rich appearance controls are documented
by [Lawnchair's own README at the exact baseline](https://github.com/LawnchairLauncher/lawnchair/blob/eed2baf4efe4cf49540cf4ec474942dc743b83cc/README.md).
They are not inventions exclusive to Expressive. AOSP's primary source is
[the Launcher3 project](https://android.googlesource.com/platform/packages/apps/Launcher3/).

## What Expressive adds and refines

**Settings that prioritize everyday use.** Wallpaper & style, appearance, Home,
At a Glance, the drawer and search are easy to reach. Dock, folders, gestures and
backup tools remain available under Advanced. Category icons respond to interaction
and honor Android's animation setting. The drawer's scrollbar fades after use.
Sources: [settings dashboard](../lawnchair/src/app/lawnchair/ui/preferences/destinations/PreferencesDashboard.kt),
[category animation](../lawnchair/src/app/lawnchair/ui/preferences/components/controls/PreferenceCategory.kt),
[transient scrollbar](../src/com/android/launcher3/views/RecyclerViewFastScroller.java).

**Discover setup inside Home settings.** Expressive includes a matching support APK
and checks its identity and bytes before handing installation to Android. After
setup, a switch controls Google's native panel beside the first Home page. The
support component has no separate launcher icon. Sources:
[settings integration](../lawnchair/src/app/lawnchair/ui/preferences/components/ExpressiveFeedPreferences.kt),
[support installer](../lawnchair/src/app/lawnchair/feed/ExpressiveFeedSetup.kt),
[integration details](GOOGLE_DISCOVER.md).

**At a Glance refinements.** The built-in fallback leads with the date and available
weather. Version 1.0.12 coordinates setup across open settings screens and recovers
a valid saved weather-widget binding when an in-memory ID is stale. Sources:
[target policy](../lawnchair/src/app/lawnchair/smartspace/provider/SmartspaceProvider.kt),
[setup coordination](../lawnchair/src/app/lawnchair/smartspace/provider/SmartspaceSetupCoordinator.kt),
[widget binding recovery](../lawnchair/src/app/lawnchair/HeadlessWidgetsManager.kt).

**Search access and clarity.** Expressive retains Lawnchair's search providers and
offers a user-selected folder through Android's folder picker. Contact actions
have distinct message/call accessibility descriptions. Sources:
[file access settings](../lawnchair/src/app/lawnchair/ui/preferences/components/search/FileSearchProvider.kt),
[contact result actions](../lawnchair/src/app/lawnchair/allapps/views/SearchResultRightLeftIcon.kt).

**Updates with a clear channel and verification path.** QA installations check the
QA manifest; release installations use a separate release manifest. With notification
permission, periodic checks notify users about newer matching builds and support
reminders. Downloads are checked for size, SHA-256, package, version and signing
lineage before Android's installer is offered. Sources:
[update policy](../lawnchair/src/app/lawnchair/ui/preferences/about/ExpressiveUpdatePolicy.kt),
[notification and reminder policy](../lawnchair/src/app/lawnchair/ui/preferences/about/ExpressiveUpdateNotifications.kt),
[download verification](../lawnchair/src/app/lawnchair/ui/preferences/about/NightlyBuildsRepository.kt).

## Dependencies and scope

- **Android 17 / API 37 is required.** This is the minimum SDK in
  [the build configuration](../build.gradle), not just a suggested test version.
  Android 17 device support does not mean the full AOSP Android 17 core port is
  finished; [the port notes](ANDROID17_PORT.md) describe that separate work.
- **Discover comes from Google.** It needs the installed and enabled Google app,
  the bundled Expressive Feed component installed with Android's confirmation,
  and suitable account/network conditions. Google-app changes can affect it.
  There is no stable public third-party Discover SDK.
- **Weather and Smartspacer have providers.** Built-in weather uses Google's
  At a Glance widget connection and may require Android widget approval.
  Smartspacer is a separate optional application. Displayed content depends on
  the selected provider and permissions.
- **Optional search results need the relevant access.** Contacts require contacts
  permission; folder search covers the location selected through Android;
  web suggestions use the selected provider and network.
- **Updates remain user-controlled.** Notification permission enables alerts, and
  Android schedules background work. Downloads and installation require user
  action. Separate stable support does not imply a stable build is already
  published; check [available releases](https://github.com/denson9874/ExpressiveLauncher/releases).
- **No root is required for the Home app.** Expressive does not replace SystemUI's
  Recents or system navigation. The [product manifest](../expressive/AndroidManifest-launcher.xml)
  implements that boundary. Performance superiority, complete Pixel feature
  parity and compatibility with every Android 17 device are not claimed.

For source setup, see [Building Expressive](BUILDING.md). For the current package
and download links, see the [repository overview](../README.md).
