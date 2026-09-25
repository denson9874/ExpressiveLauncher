# Google Play Release Runbook

This runbook applies to releasing **Expressive Launcher L3** (`lawnWithQuickstepExpressiveRelease`) to the **Google Play Store** under verified Google Developer **Daryl Denson** (Play Console Developer ID: `5547708187557586870`).

---

## 1. Verified Developer and Product Identity

- **Developer Name:** Daryl Denson
- **Play Console Developer ID:** `5547708187557586870`
- **Developer Contact:** `daryldenson0405@gmail.com`
- **Application ID:** `com.denson9874.Expressive_Launcher_L3`
- **App Name:** Expressive Launcher L3
- **Current Version Code:** `37`
- **Current Version Name:** `3.0.5`
- **Target SDK:** 37 (Android 17)
- **Minimum SDK:** 31 (Android 12)

---

## 2. Developer Signature and Upload Key

The release key is configured with the verified Google Play Developer signature:

- **Keystore Path:** `/Users/daryldenson/Documents/Expressive Launcher Signing/expressive-developer-release.jks`
- **Offline Credential Backup:** `/Users/daryldenson/Documents/Expressive Launcher Signing/developer-credentials.properties`
- **Local Gradle Configuration:** `keystore.properties` (ignored by Git)
- **Key Alias:** `expressive-developer-release`
- **Key Algorithm:** 4096-bit RSA with SHA256withRSA
- **Distinguished Name (DNAME):**  
  `CN=Daryl Denson, OU=5547708187557586870, O=Expressive Launcher, C=US`
- **Certificate Validity:** Fri Sep 25, 2026 through Tue Feb 10, 2054 (10,000 days)
- **Certificate Fingerprints:**
  - **SHA-256:** `2A:A9:F1:BF:3D:BD:2D:5B:D2:7A:D7:51:6F:1C:AF:1B:8A:18:E1:5F:6D:59:85:8A:37:28:27:84:BB:2A:CB:A7`
  - **SHA-1:** `E8:25:C2:BE:19:85:30:ED:EA:1E:B2:10:F3:46:9D:06:0B:81:0F:1E`

### Public Certificate Export

The public certificate is exported in RFC PEM format at:
- `upload_certificate.pem` (repository root, ignored by Git)
- `/Users/daryldenson/Documents/Expressive Launcher Signing/developer_certificate.pem`

If Google Play Console prompts you to register an upload certificate during Play App Signing setup, upload this `.pem` file.

To regenerate or re-export the certificate at any time:
```sh
./scripts/configure-play-developer-signing.sh
```

---

## 3. Privacy Policy and Data Safety

- **Public Privacy Policy URL:** `https://denson9874.github.io/ExpressiveLauncher/privacy`
- **Source Documents:**
  - `play/privacy-policy.md` (Markdown format)
  - `play/privacy-policy.html` and `docs/privacy.html` (Standalone HTML for web hosting)
  - `docs/PRIVACY_POLICY.md` (Repository documentation)
- **Data Safety Worksheet:**
  - Refer to `play/data-safety.md` for exact, field-by-field answers for the Play Console Data Safety questionnaire.

---

## 4. Building the Signed Play Release Bundle

To build the release bundle compliant with Google Play Store policies:

```sh
./gradlew bundleLawnWithQuickstepExpressiveRelease -PtargetPlayStore=true
```

Optional overrides:
```sh
./gradlew bundleLawnWithQuickstepExpressiveRelease \
  -PtargetPlayStore=true \
  -PexpressiveApplicationId=com.denson9874.Expressive_Launcher_L3 \
  -PexpressiveVersionCode=37 \
  -PexpressiveVersionName=3.0.5 \
  -PexpressivePrivacyPolicyUrl=https://denson9874.github.io/ExpressiveLauncher/privacy
```

### Generated Artifacts
- **Play Release Bundle (AAB):**  
  `build/outputs/bundle/lawnWithQuickstepExpressiveRelease/expressive-launcher-l3-lawn-withQuickstep-expressive-release.aab`
- **R8 Deobfuscation Mapping:**  
  `build/outputs/mapping/lawnWithQuickstepExpressiveRelease/mapping.txt` (also packaged directly inside the bundle's `BUNDLE-METADATA`)
- **Resource Shrinking Report:**  
  `build/outputs/mapping/lawnWithQuickstepExpressiveRelease/resources.txt`

---

## 5. Automated Google Play Publishing via API

Expressive Launcher supports automated Google Play publishing directly through the Google Play Developer API using Gradle.

### Publishing Command
To build and publish the release bundle in one command:

```sh
./gradlew publishExpressivePlayRelease -PtargetPlayStore=true
```

Aliases supported:
```sh
./gradlew publishReleaseBundle -PtargetPlayStore=true
./gradlew publishLawnWithQuickstepExpressiveReleaseBundle -PtargetPlayStore=true
```

### Selecting Release Track
By default, the task deploys to the `internal` testing track. You can specify a different track (e.g. `alpha`, `beta`, `production`):
```sh
./gradlew publishExpressivePlayRelease -PtargetPlayStore=true -PplayTrack=alpha
```

### Automated Flow
1. Automatically verifies Play Store manifest safety, privacy policy, and developer signature requirements.
2. Builds and signs the release `.aab` bundle with R8 optimization.
3. Automatically backs up the signed `.aab` to `/Users/daryldenson/Documents/Expressive Launcher Signing/`.
4. Authenticates with Google Cloud via Service Account JSON (`play-service-account.json`).
5. Initiates a Google Play Developer API edit session.
6. Performs resumable upload of the `.aab` bundle.
7. Sets release notes from `play/listing/en-US/changelogs/<versionCode>.txt`.
8. Assigns the bundle to the target track and commits the edit to Play Console.

---

## 6. Google Play Console Setup Step-by-Step

### A. Create the App
1. Log into [Google Play Console](https://play.google.com/console) with Developer ID `5547708187557586870`.
2. Click **Create app**:
   - **App name:** `Expressive Launcher L3`
   - **Default language:** English (United States) - `en-US`
   - **App or game:** App
   - **Free or paid:** Free (or select Paid if applicable)
3. Accept the declarations and click **Create app**.

### B. App Integrity & Play App Signing
In the current Google Play Console UI (as seen on your screen):
1. Notice the prompt: *"App Integrity settings have moved"*.
2. Click the blue button **Go to Protected with Play →** (or click **Protected with Play** in the left sidebar, 4th item from the top).
3. Under **Play App Signing**:
   - If opting for Google-managed key: Google Play manages your key, and you can register your upload key using `upload_certificate.pem` or by uploading your signed bundle.
   - If setting your upload certificate manually, upload [`upload_certificate.pem`](file:///Users/daryldenson/Documents/ChatGPT/New%20project/upload_certificate.pem).
   - Verify that your registered certificate SHA-256 fingerprint matches:  
     `2A:A9:F1:BF:3D:BD:2D:5B:D2:7A:D7:51:6F:1C:AF:1B:8A:18:E1:5F:6D:59:85:8A:37:28:27:84:BB:2A:CB:A7`.

### C. Upload Release Artifact to Testing Track
1. In the left sidebar, under **Test and release**, click **Testing** (expand the dropdown) → click **Internal testing**.
2. Click **Create new release** in the top right.
3. In the App bundles section, upload:  
   [`build/outputs/bundle/lawnWithQuickstepExpressiveRelease/expressive-launcher-l3-lawn-withQuickstep-expressive-release.aab`](file:///Users/daryldenson/Documents/ChatGPT/New%20project/build/outputs/bundle/lawnWithQuickstepExpressiveRelease/expressive-launcher-l3-lawn-withQuickstep-expressive-release.aab)
   *(Play Console will automatically register your upload key from the bundle signature if not previously registered!)*
4. Copy the release notes from [`play/listing/en-US/changelogs/36.txt`](file:///Users/daryldenson/Documents/ChatGPT/New%20project/play/listing/en-US/changelogs/36.txt).
5. Click **Next**, review the bundle, and save.

### D. Store Listing & Graphics
In the left sidebar, navigate to **Grow users > Store presence > Main store listing**:
- **App title:** `Expressive Launcher L3` (from [`play/listing/en-US/title.txt`](file:///Users/daryldenson/Documents/ChatGPT/New%20project/play/listing/en-US/title.txt))
- **Short description:** From [`play/listing/en-US/short_description.txt`](file:///Users/daryldenson/Documents/ChatGPT/New%20project/play/listing/en-US/short_description.txt)
- **Full description:** From [`play/listing/en-US/full_description.txt`](file:///Users/daryldenson/Documents/ChatGPT/New%20project/play/listing/en-US/full_description.txt)
- **App icon:** Upload [`play/listing/en-US/graphics/icon.png`](file:///Users/daryldenson/Documents/ChatGPT/New%20project/play/listing/en-US/graphics/icon.png) (512x512 PNG)
- **Feature graphic:** Upload [`play/listing/en-US/graphics/featureGraphic.png`](file:///Users/daryldenson/Documents/ChatGPT/New%20project/play/listing/en-US/graphics/featureGraphic.png) (1024x500 PNG)
- **Phone screenshots:** Upload `01-home.jpg` and `02-settings.jpg` from [`play/listing/en-US/graphics/phoneScreenshots/`](file:///Users/daryldenson/Documents/ChatGPT/New%20project/play/listing/en-US/graphics/phoneScreenshots/)

### E. App Content & Policy Declarations
Scroll down the left sidebar to **Policy and programs** (or **App content**):
1. **Privacy Policy:** Enter `https://denson9874.github.io/ExpressiveLauncher/privacy`.
2. **App Access:** Select "All functionality is available without special access".
3. **Ads:** Select "No, my app does not contain ads".
4. **Content Rating (IARC):** Complete questionnaire (Utilities category, no violence, no location sharing → Rated Everyone / PEGI 3).
5. **Target Audience:** Select 18 and over (or 13+).
6. **Data Safety:** Complete using [`play/data-safety.md`](file:///Users/daryldenson/Documents/ChatGPT/New%20project/play/data-safety.md).
7. **Permission Declarations:**
   - **`QUERY_ALL_PACKAGES`:** State: *"Expressive Launcher is a replacement Android Home application. It must query all installed launchable applications to populate the Home screen, All Apps drawer, and app search."*
   - **Accessibility Service:** State: *"Expressive Launcher uses Android AccessibilityService API exclusively for optional user-configured gesture shortcuts (such as double-tap to lock screen or open notifications). It subscribes to no accessibility events and reads no user content."*
