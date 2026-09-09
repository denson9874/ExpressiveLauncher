# Google Play release runbook

This runbook applies only to `lawnWithQuickstepExpressiveRelease`. It does not publish the
upstream Lawnchair `play` flavor.

## 1. Lock the permanent product identity

Before creating the app in Play Console, choose the final application ID. The current default is
`dev.launcher.expressive.l3`. Google Play does not allow an application ID to be changed after the
first artifact is uploaded.

This package has already been installed on a certified test device with an Android debug
certificate. Under Android developer verification, Play Console may treat it as an existing
package and request proof using that known private key. The cleanest production path is to choose
a new, brand-owned application ID before the first Play upload. If the current ID is retained,
keep the existing debug keystore until package-name registration is complete; do not use that key
to sign production bundles.

Set the selected ID on every release build:

```sh
-PexpressiveApplicationId=your.permanent.application.id
```

## 2. Create and protect the upload key

Use Play App Signing and keep a separate private upload key. Generate it once with Android Studio
(`Build` > `Generate Signed Bundle / APK` > `Create new`) or `keytool`, then back it up in a secure
password manager or encrypted vault. Never commit the keystore or its passwords.

Copy `keystore.properties.example` to the ignored `keystore.properties` file and fill all four
values, or provide these environment variables:

- `EXPRESSIVE_UPLOAD_STORE_FILE`
- `EXPRESSIVE_UPLOAD_STORE_PASSWORD`
- `EXPRESSIVE_UPLOAD_KEY_ALIAS`
- `EXPRESSIVE_UPLOAD_KEY_PASSWORD`

Export the public upload certificate for Play Console without exposing the private key:

```sh
keytool -export -rfc \
  -keystore /absolute/path/to/expressive-upload.jks \
  -alias expressive-upload \
  -file upload_certificate.pem
```

`upload_certificate.pem` is ignored by this repository, even though the public certificate is safe
to share.

## 3. Publish the privacy policy

Customize `play/privacy-policy-template.md`, obtain appropriate legal review, and publish it as a
public, non-editable HTTPS web page. The page must identify the developer shown in the store
listing and include a working privacy contact. The release URL is compiled into Settings > About,
so the same URL is visible both in the app and Play Console.

## 4. Build the signed bundle

Choose a `versionCode` that has never been uploaded for this application ID. It must increase for
every Play release.

```sh
./gradlew bundleLawnWithQuickstepExpressiveRelease \
  -PexpressiveApplicationId=your.permanent.application.id \
  -PexpressiveVersionCode=1 \
  -PexpressiveVersionName=1.0.0 \
  -PexpressivePrivacyPolicyUrl=https://example.com/expressive-launcher/privacy
```

The build refuses to create the Play bundle if signing is missing, the privacy URL is not HTTPS,
the inherited Lawnchair privacy policy is used, or the version name contains `Dev`.

Upload these artifacts from `build/outputs`:

- `bundle/lawnWithQuickstepExpressiveRelease/*.aab`: Play Console release artifact
- `mapping/lawnWithQuickstepExpressiveRelease/mapping.txt`: R8 deobfuscation mapping

Keep both artifacts for every release. The GitHub workflow
`.github/workflows/build_expressive_play_bundle.yml` performs the same guarded build and archives
them. Configure these repository secrets before using it:

- `EXPRESSIVE_UPLOAD_KEYSTORE_BASE64`
- `EXPRESSIVE_UPLOAD_STORE_PASSWORD`
- `EXPRESSIVE_UPLOAD_KEY_ALIAS`
- `EXPRESSIVE_UPLOAD_KEY_PASSWORD`

## 5. Complete Play Console setup

Create an app (default language English US, app, free unless a paid launch is intended), enroll in
Play App Signing, and start with Internal testing. Complete every App content declaration before
submitting for review:

- Privacy policy and Data safety, using `play/data-safety.md` as the engineering worksheet
- Ads declaration: no ads, if the shipping configuration remains ad-free
- App access: all core screens are accessible without an account
- Target audience and content rating questionnaire
- `QUERY_ALL_PACKAGES`: core launcher functionality requires enumerating installed launchable apps
- Accessibility service: optional gesture actions only; it subscribes to no accessibility events
- Foreground service and notification declarations for user-initiated crash-report upload
- Contact and media permissions: optional search/customization features, accurately disclosed

The current target is API 37, above the August 31, 2026 Play requirement of API 36. The minimum is
also API 37, intentionally limiting availability to Android 17 devices.

Use only `play/listing/en-US` for the initial Expressive listing. The inherited
`fastlane/metadata/android` translations describe upstream Lawnchair and must not be uploaded for
this product until they are rewritten and reviewed.

## 6. Release sequence

Before uploading a production candidate, run the release-readiness commands in
`docs/PLAY_RELEASE_READINESS.md`. The August 25, 2026 audit built and validated the bundle and
passed all focused tests, but full release lint still reports inherited errors. Those errors are
not baselined or suppressed and remain a production gate.

1. Upload the AAB to Internal testing and resolve automated pre-launch, policy, and bundle checks.
2. Install from Google Play on the Android 17 test device; verify Home-role selection, first-run
   layout, widget bind/configure, folders, search, icon packs, Smartspacer fallback, rotation,
   process recreation, and upgrade retention.
3. Promote the same tested build to Closed testing.
4. For personal developer accounts created after November 13, 2023, keep at least 12 testers opted
   in continuously for 14 days, then apply for production access.
5. Stage production rollout and monitor Android vitals before increasing availability.

Do not enable automatic production publishing until package registration, listing ownership,
privacy review, and the first closed test are complete.
