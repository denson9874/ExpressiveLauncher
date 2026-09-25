# Privacy Policy for Expressive Launcher L3

Effective date: September 25, 2026  
Developer: Daryl Denson (Google Play Developer ID: 5547708187557586870)  
Contact: daryldenson0405@gmail.com  

Daryl Denson ("we", "us", or "our") provides Expressive Launcher L3, an independent Android Home application. This Privacy Policy describes how Expressive Launcher L3 handles user data.

## 1. Information Handled by the Application

Expressive Launcher L3 operates primarily as an on-device application. All core launcher preferences, Home screen layouts, folders, hidden application lists, icon customizations, and widget configurations are stored locally on your device in private application storage.

Eligible app settings and layouts may be backed up to your personal Google Drive account via the standard Android Backup Service, governed by your device and Google account settings.

### Permissions and On-Device Access

Expressive Launcher L3 requests and uses the following permissions only when required to provide features you explicitly choose:

- **Installed Applications (`QUERY_ALL_PACKAGES`)**: Expressive Launcher L3 enumerates installed applications on your device to display your Home screen shortcuts, All Apps drawer, and app search results. This data is processed strictly on-device in real time and is never transmitted or sold.
- **Contacts (`READ_CONTACTS`)**: If you grant this optional permission, the launcher searches matching contact names and phone numbers directly on your device when you type queries into launcher search. Contact details remain on your device and are never sent to external servers.
- **Storage and Files (Storage Access Framework)**: If you choose to enable local file search or custom wallpaper features, the app uses Android's secure Storage Access Framework (SAF) to let you select specific folders. The app reads only files inside the user-selected folder.
- **Usage Statistics (`PACKAGE_USAGE_STATS`)**: If granted in Android system settings, on-device usage access is used solely by Android's AppPredictor engine to rank recently or frequently used apps in your app predictions.
- **Smartspacer Integration**: If you have the independently developed Smartspacer app installed and enable integration, glanceable targets are exchanged locally on your device via Android IPC to display At a Glance information on the Home screen.
- **Accessibility Service (`AccessibilityService`)**: If you explicitly enable gesture shortcuts (such as double-tap to lock screen or swipe down for notifications), the launcher uses Android's AccessibilityService API exclusively to invoke system global actions (`GLOBAL_ACTION_LOCK_SCREEN`, `GLOBAL_ACTION_NOTIFICATIONS`). The service subscribes to no accessibility events, does not monitor screen contents, and collects no data.
- **Notifications (`POST_NOTIFICATIONS`)**: Used to display launcher-related system notices or updates if enabled by the user.

## 2. Network Usage and Third-Party Services

Expressive Launcher L3 does not require an account, does not use advertising SDKs, does not use tracking SDKs, and does not sell personal information.

Optional features may make network requests:
- **Web Search**: If you type a query and choose to search the web, your query is sent directly to your selected search provider (such as Google Search or DuckDuckGo) via browser intent or direct API. Requests are handled under the respective provider's privacy policy.
- **Google Fonts**: If you select an online font in launcher appearance settings, the font file is downloaded directly from Google Fonts over HTTPS.
- **User-Initiated Crash Reporting**: If a crash occurs and you explicitly tap "Upload Crash Report", an anonymized diagnostic report (containing launcher stack trace and Android OS version) is uploaded to the user-selected diagnostic paste service over encrypted HTTPS. No automated background crash uploading takes place without user action.

## 3. Data Retention and Deletion

All launcher configuration, layout data, and preferences remain on your device until you:
1. Clear application storage in Android Settings (**Settings → Apps → Expressive Launcher → Storage & cache → Clear storage**), or
2. Uninstall the application from your device.

Because we do not store your personal information on our own remote servers, uninstalling the app or clearing local data completely removes all your local data.

## 4. Security

Expressive Launcher L3 relies on Android's application sandbox security, uses modern encrypted HTTPS transport for all network requests, and excludes unnecessary legacy storage and privileged platform permissions.

## 5. Children's Privacy

Expressive Launcher L3 is a general audience utility application intended for users of all ages. We do not knowingly collect personal identifiable information from children under 13 years of age.

## 6. Changes to This Privacy Policy

We may update this Privacy Policy from time to time. Any updates will be published on this page with a revised effective date.

## 7. Contact Us

If you have any questions or requests regarding this Privacy Policy or Expressive Launcher L3, please contact:

**Daryl Denson**  
Google Play Console Developer ID: 5547708187557586870  
Email: [daryldenson0405@gmail.com](mailto:daryldenson0405@gmail.com)  
Project Repository: [https://github.com/denson9874/ExpressiveLauncher](https://github.com/denson9874/ExpressiveLauncher)
