# Google Discover inside Expressive

Expressive presents Google's native Discover overlay beside the first Home page. Setup and the
feature switch live in **Home settings → Home screen → Google Discover**. There is no separate
feed screen or helper icon in the app drawer.

## Setup and behavior

1. The Google app must be installed and enabled. Settings offers the appropriate Google-app action
   if it is missing or disabled.
2. Choose **Set up Google Discover**. Expressive verifies and extracts its included background
   support; Android asks for install-source permission if needed and confirms the installation.
3. After installation, **Show Google Discover** controls the native panel. Swipe right from the
   first Home page (left in RTL). Feed rendering, content and Google account behavior come from
   the installed Google app.

The setup survives returning from permission settings and refreshes after package installation,
removal, replacement, disabling or re-enabling. Cancelled setup can be retried. Existing Home data
is preserved. The switch is enabled by a completed, explicitly requested setup; merely inspecting
settings does not change the user's preference.

New launcher versions include matching support updates. **Update Discover support** runs the same
verified Android installation flow. An already newer compatible helper is retained. A disabled or
differently signed helper requires attention in Android App info; Expressive does not silently
uninstall it or bypass Android's signature rules.

## Packaging and trust

The generated assets are `expressive-feed/ExpressiveFeed.apk` and `expressive-feed/metadata.json`.
AGP builds the helper before merging launcher assets, using helper Debug for launcher Debug and
helper Release for Qa/Release. The version, size and SHA-256 come from the actual built artifact.
No binary is checked into source and no additional download is required for feed support.

Before proposing an installation, Expressive validates the bundled bytes, package/version,
signing certificate against its own installed certificate, and the protected bridge service.
The APK is shared only through the existing FileProvider's dedicated `expressive-feed/` cache path.
Android remains responsible for permission and installation confirmation.

From versionCode 10, Jenkins also requires the bundled support's identity, digest, version,
same signing certificate and service-only manifest during package verification. The main Qa APK
must remain non-debuggable. The small helper deliberately has its own debuggable application
identity and accepts only same-signed Expressive callers through a signature permission and UID
validation. Android lists it as **Expressive Feed** in installed applications, despite its lack of
launcher icon or separate UI.

## Google compatibility boundary

Google has no stable public Discover-overlay SDK for third-party launchers. Other launcher
maintainers document the companion approach, including [Nova](https://novalauncher.com/nowcompanion/)
and [Smart Launcher](https://www.smartlauncher.net/bridge). Android's `debuggable` flag applies to
the [whole application](https://developer.android.com/guide/topics/manifest/application-element),
so placing the bridge in another process inside the launcher would not reproduce its independent
application identity.

The September 6 compatibility probe on Android 17 QPR2 Beta 4 `CP41.260814.003.B1` observed Google
app `17.46.15.sa.arm64` / code `301786727`, overlay API 11. Identical diagnostic code received a
null binding as a non-debuggable app and a ready callback/native Discover window as a debuggable
app. The fresh guest showed Google's sign-in/no-content state; this establishes native overlay
connection/rendering, not personalized content availability. Evidence is retained under
`artifacts/embedded-feed-20260906/runtime/`.

Google-app updates, account state and network availability can affect the panel. The local setup
status confirms installation prerequisites; it does not claim live content has loaded. The exact
launcher candidate still requires Jenkins build/upgrade checks and device validation of its
setup, native overlay, cancellation and feature-toggle flows before a delivery claim.
