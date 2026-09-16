# Expressive Launcher direct distribution

Starting with 2.0.0, Expressive Launcher offers two selectable update channels in one app:

- `qa`: GitHub QA prereleases and `updates:qa-v2/latest.json`.
- `release`: GitHub stable releases and `updates:release/latest.json`.

Both APKs use `dev.launcher.expressive.l3` and the same durable Expressive release key. Changing
channels keeps the existing app and its data; a newer matching APK replaces it in place after the
user confirms Android's installer. The selected channel cannot bypass package, signature, digest,
or version checks, and a lower-version stable release is never offered as a downgrade.

The first QA 2.0.0 / code 18 candidate upgrades stable 1.0.16 / code 17 in place. Legacy 1.x QA
uses the separate `dev.launcher.expressive.l3.debug` package and `updates:qa/latest.json`.
That feed and its published assets remain intact. Moving a legacy QA installation to the unified
2.x app requires a one-time move to the canonical application; Android cannot transfer its private
data across package IDs. Keep the legacy installation until any desired backup/restore is completed.

The QA distribution channel uses the Gradle `Qa` variant: it inherits release minification/resource
shrinking and the durable signer.
A developer `Debug` APK keeps its `.debug` application ID and different certificate. It cannot
replace the signed 2.x QA/stable application or a legacy release-signed QA installation in place.

## Wallpaper and all-files access

Expressive is distributed directly through GitHub. Its QA and stable APKs declare Android’s
`MANAGE_EXTERNAL_STORAGE` special access so the user can allow reading the current wallpaper for
Blur wallpaper and wallpaper previews. Android still requires an explicit grant in Settings;
installing or updating the APK does not grant access automatically.

In **Settings → Experimental features → Blur wallpaper**, select **Open settings** and
enable **Allow access to manage all files** for Expressive. Returning after a successful grant
completes the requested blur toggle. Cancelling leaves blur off. Revoking access restores the normal
wallpaper when Home returns, while keeping the saved blur preference for a later grant.

This does not require a separate photo/video-library grant. A selected folder or selected photos
cannot authorize reading the current static wallpaper. File Search keeps its selected-folder option
until full access is granted, and retains that folder grant if full access is later revoked.
The separate upstream Play flavor continues to omit all-files access.

`verifyStoragePermissionManifests` checks the actual merged Expressive Qa/Release and Play manifests,
including their Home activity and channel-specific storage permissions. Expressive packaging runs
this check automatically.

## One-time signing setup

Run `scripts/configure-expressive-release-signing.sh` once on the signing Mac. It creates:

- `/Users/daryldenson/Documents/Expressive Launcher Signing/expressive-launcher-release.jks`
- `/Users/daryldenson/Documents/Expressive Launcher Signing/release-credentials.properties`
- the ignored checkout file `keystore.properties`

The keystore and credential backup are the permanent update identity. Store encrypted offline
backups in two independent locations. Losing the key prevents future in-place updates. Never commit
the keystore or either properties file.

An older debug-certificate-signed development build cannot replace the release-signed app.
Install the canonical signed application as a separate app and preserve the old installation until
its desired data has been backed up or moved. Release builds use the release key from their first
installation onward; never change that signing identity when changing channels.

## Publishing an update

The repeatable build and release stages are now executed by Jenkins. See [CI_PIPELINE.md](CI_PIPELINE.md)
for the build job, retained candidate, and independently retryable publication job. The contract below
remains the distribution policy enforced by those jobs.

1. Increment `expressiveVersionCode` and the patch component of `expressiveVersionName`.
2. Build `assembleLawnWithQuickstepExpressiveQa` and/or
   `assembleLawnWithQuickstepExpressiveRelease`. These tasks fail when release signing is absent.
3. Verify the APK signer, package name, version code, byte size, and SHA-256 digest.
   Compare the certificate against the prior delivered artifact, not just a successful signature check.
4. Stage a new versioned GitHub release in https://github.com/denson9874/ExpressiveLauncher with only
   its signed APK as a downloadable release asset. Retain QA reports, metadata and device results
   in the immutable local seal and Jenkins archives, and retain the stable branch evidence archive.
   QA uses prerelease tags `qa-vVERSION-CODE`; stable uses normal release tags `vVERSION-CODE`.
   Do not overwrite older releases or conflicting assets.
5. After verification, publish the requested channel's release and validate a complete public APK
   download. Advance only that channel's `latest.json` on the `updates` branch using blob-SHA conflict
   detection. Drafts are not advertised to installed clients.
6. Install the prior signed build, open About, download the offered update, and confirm Android
   accepts the in-place upgrade without data loss.

The APK-only policy applies to future release attachments, including stable seal and authorization
JSON files. Release prose stays in the release body; updater `latest.json` manifests and retained
verification evidence remain required. Existing release attachments are preserved.

The updater accepts only HTTPS, a matching channel and package, a strictly newer version code, the
declared byte size and SHA-256, and a signing lineage containing the installed certificate.

## Upgrade validation before publication

Before uploading a candidate, validate a true same-signer upgrade from the last delivered 2.x QA APK
on an isolated, seeded emulator. Confirm the original install time, default HOME, folder contents,
settings, current package path/version, and clean new startup logs. Do not uninstall or clear app
data to hide a failed upgrade. A user-requested publication hold also keeps feeds and sharing unchanged.
For the initial 2.0.0 QA candidate, use the published canonical stable 1.0.16 / code 17 as this baseline.

On the Android 17 QPR2 Beta 4 guest `CP41.260814.003.B1`, replacing the APK through ADB while
Expressive HOME was foreground intermittently restarted it using the removed previous APK path.
This failed inside Android before Application initialization, including on a byte-identical reinstall.
For ADB-based validation, put Android Settings in front, force-stop only the isolated test launcher,
run the same-signer `install -r`, wait for success, read fresh package metadata, and then launch HOME.
Background/quiesced controls passed; this is a QA delivery-sequence mitigation, not a claim that the
Android foreground-replacement race is fixed. Also test the real system-installer UI flow used for
user delivery. Do not add app resource/class-loader workarounds for a pre-Application loading failure.

## Update notifications

The user selects the update source from About; the preference survives app restarts:

- QA selects `https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa-v2/latest.json`.
- Stable selects `https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/release/latest.json`.
- An alert is eligible only when the selected manifest's `versionCode` is greater than the
  installed build's `versionCode`.

After the user enables update notifications from About, Android's persisted job scheduler checks
the selected channel immediately and approximately every six hours when a network is available.
Only one alert is posted for each offered version. Tapping it opens the verified update prompt with
**Download & install** and reminder presets for one hour, tomorrow, three days, or one week. A
newer version bypasses a reminder set for an older version.

Android requires a one-time per-source approval before this app can launch the package installer.
The APK is completely downloaded and validated first; the user still confirms installation in the
system-owned installer UI.


## GitHub migration and source selection

Builds beginning with 1.0.11 read public GitHub channel manifests and versioned Release assets.
No user or publisher credentials are stored in the app. Normal HTTPS CDN redirects are supported;
HTTP/HTTPS scheme-changing redirects and malformed/credential-bearing manifest URLs are rejected.
A channel manifest avoids GitHub's shared `latest` endpoint, which excludes QA prereleases.

Already-installed 1.0.10 and earlier binaries still have their original Drive manifest URL. During the
one-time QA migration, that existing manifest can point to the verified GitHub-hosted migration APK,
without uploading another Drive APK. After upgrade, all checks use GitHub. Preserve historical Drive
files. That history applies to legacy 1.x QA only. Starting with 2.0, QA and stable retain separate
manifests and share the canonical application ID described above.
