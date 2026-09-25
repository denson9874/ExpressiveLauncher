# Expressive Launcher L3 - Google Play Data Safety Form Worksheet

This worksheet provides exact, verified answers for the **Data safety** section in Google Play Console for **Expressive Launcher L3** by **Daryl Denson (Developer ID: 5547708187557586870)**.

---

## Section 1: Data Collection and Security Overview

1. **Does your app collect or share any of the required user data types?**
   - **Answer:** **No**
   - *Explanation:* The launcher stores all settings, preferences, and layouts exclusively on the user's local device. No user data is automatically collected or sent to remote developer servers.

2. **Is all of the user data collected by your app encrypted in transit?**
   - **Answer:** **Yes**
   - *Explanation:* Any network requests made by optional features (e.g., Google Fonts or user-initiated crash upload) use encrypted HTTPS.

3. **Do you provide a way for users to request that their data is deleted?**
   - **Answer:** **Yes**
   - *Explanation:* All data is stored locally. Users can delete all app data at any time via Android System Settings (**Settings → Apps → Expressive Launcher → Storage & cache → Clear storage**) or by uninstalling the application.

---

## Section 2: Detailed Data Types Breakdown

### Personal info
- **Name, Email, Address, Phone number, User IDs:** **Not collected**

### Financial info
- **Payment info, Purchase history, Credit score:** **Not collected**

### Location
- **Approximate location, Precise location:** **Not collected**

### Photos and videos
- **Photos, Videos:** **Not collected**
  - *Note:* Custom wallpaper selection uses Android's Storage Access Framework (SAF) locally on-device. No images or videos are collected or transmitted.

### Audio files
- **Voice recordings, Music, Audio files:** **Not collected**

### Health and fitness
- **Not collected**

### Messages
- **Emails, SMS/MMS:** **Not collected**

### Contacts
- **Contacts (`READ_CONTACTS`):**
  - **Collected?** **No** (Processed ephemerally on-device only)
  - **Shared?** **No**
  - **Purpose:** App functionality (launcher search). When the user types a contact's name into search, matching names are queried locally via Android's Contacts Provider. Contact data never leaves the device.

### App activity
- **App interactions / App info:** **Not collected**
  - *Note:* Installed apps (`QUERY_ALL_PACKAGES`) and App usage statistics (`PACKAGE_USAGE_STATS`) are processed ephemerally on-device to render the app drawer and rank predictions. They are never collected or sent off-device.

### App info and performance
- **Crash logs:**
  - **Collected?** **Optional / User-initiated only**
  - **Shared?** **No**
  - **Processed ephemerally?** Yes
  - **Required or Optional?** Optional (only if user explicitly taps "Upload Crash Report")
  - **Purpose:** Analytics / Diagnostics
- **Diagnostics:** **Not collected**

### Device or other identifiers
- **Device ID / Advertising ID:** **Not collected** (No advertising or analytics SDKs present)

---

## Section 3: Summary for Play Console Reviewers

- **Developer Name:** Daryl Denson
- **Google Play Developer ID:** 5547708187557586870
- **App Name:** Expressive Launcher L3
- **Application ID:** `com.denson9874.Expressive_Launcher_L3`
- **Privacy Policy URL:** `https://denson9874.github.io/ExpressiveLauncher/privacy`
- **Contact:** `daryldenson0405@gmail.com`
