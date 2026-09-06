# Expressive Launcher direct distribution

Expressive Launcher uses two independent update channels:

- `qa`: `dev.launcher.expressive.l3.debug`, served from the Drive **Debug Builds** folder.
- `release`: `dev.launcher.expressive.l3`, served from the Drive **Release Builds** folder.

Both APKs are signed by the same durable Expressive release key. The separate application IDs let
QA and release coexist on one device, while Android's same-signer rule protects each channel from
an untrusted replacement.

The **Debug Builds** folder is the QA distribution channel, not a request to publish the Gradle
`Debug` variant. Use `Qa`: it inherits release minification/resource shrinking and the durable signer.
A developer `Debug` APK uses a different certificate and cannot update an installed release-signed
QA build in place, even though its application ID is also suffixed `.debug`.

## One-time signing setup

Run `scripts/configure-expressive-release-signing.sh` once on the signing Mac. It creates:

- `/Users/daryldenson/Documents/Expressive Launcher Signing/expressive-launcher-release.jks`
- `/Users/daryldenson/Documents/Expressive Launcher Signing/release-credentials.properties`
- the ignored checkout file `keystore.properties`

The keystore and credential backup are the permanent update identity. Store encrypted offline
backups in two independent locations. Losing the key prevents future in-place updates. Never commit
the keystore or either properties file.

An older debug-certificate-signed QA build cannot be upgraded to the new release-signed QA track.
Uninstall that build once, install the first release-signed QA APK, and subsequent QA updates install
in place. Release builds should use the release key from their first installation onward.

## Publishing an update

The repeatable build and release stages are now executed by Jenkins. See [CI_PIPELINE.md](CI_PIPELINE.md)
for the build job, retained candidate, and independently retryable publication job. The contract below
remains the distribution policy enforced by those jobs.

1. Increment `expressiveVersionCode` and the patch component of `expressiveVersionName`.
2. Build `assembleLawnWithQuickstepExpressiveQa` and/or
   `assembleLawnWithQuickstepExpressiveRelease`. These tasks fail when release signing is absent.
3. Verify the APK signer, package name, version code, byte size, and SHA-256 digest.
   Compare the certificate against the prior delivered artifact, not just a successful signature check.
   On 2026-09-05, the connected Drive action rejected file-reference ingestion above 104857600 bytes
   (100 MiB) with HTTP 413. The 120439998-byte developer Debug candidate exceeded that limit; use the
   supported compact Qa build rather than repeatedly retrying it. This is the observed connector
   ingestion limit, not a Google Drive storage limit.
4. Upload the APK as a new retained file to its channel folder and enable read-only link access on
   that APK. Do not replace older APKs.
5. Update the channel's retained `latest.json` file with the new APK URL and verified metadata. Make
   the manifest readable by link, but leave folder listing private.
6. Install the prior signed build, open About, download the offered update, and confirm Android
   accepts the in-place upgrade without data loss.

The updater accepts only HTTPS, a matching channel and package, a strictly newer version code, the
declared byte size and SHA-256, and a signing lineage containing the installed certificate.

## Upgrade validation before publication

Before uploading a candidate, validate a true same-signer upgrade from the last delivered Qa APK
on an isolated, seeded emulator. Confirm the original install time, default HOME, folder contents,
settings, current package path/version, and clean new startup logs. Do not uninstall or clear app
data to hide a failed upgrade. A user-requested publication hold also keeps feeds and sharing unchanged.

On the Android 17 QPR2 Beta 4 guest `CP41.260814.003.B1`, replacing the APK through ADB while
Expressive HOME was foreground intermittently restarted it using the removed previous APK path.
This failed inside Android before Application initialization, including on a byte-identical reinstall.
For ADB-based validation, put Android Settings in front, force-stop only the isolated test launcher,
run the same-signer `install -r`, wait for success, read fresh package metadata, and then launch HOME.
Background/quiesced controls passed; this is a QA delivery-sequence mitigation, not a claim that the
Android foreground-replacement race is fixed. Also test the real system-installer UI flow used for
user delivery. Do not add app resource/class-loader workarounds for a pre-Application loading failure.

## Update notifications

The installed build selects its update source; users cannot accidentally cross channels:

- QA builds check only the retained `latest.json` in **Debug Builds**.
- Release builds check only the retained `latest.json` in **Release Builds**.
- An alert is eligible only when the matching manifest's `versionCode` is greater than the
  installed build's `versionCode`.

After the user enables update notifications from About, Android's persisted job scheduler checks
the selected channel immediately and approximately every six hours when a network is available.
Only one alert is posted for each offered version. Tapping it opens the verified update prompt with
**Download & install** and reminder presets for one hour, tomorrow, three days, or one week. A
newer version bypasses a reminder set for an older version.

Android requires a one-time per-source approval before this app can launch the package installer.
The APK is completely downloaded and validated first; the user still confirms installation in the
system-owned installer UI.
