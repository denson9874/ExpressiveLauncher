# Expressive Launcher L3 data-safety worksheet

This is an engineering inventory, not a completed legal declaration. Re-check the exact release
bundle and every enabled SDK before submitting the Play Console Data safety form.

## Product behavior

- No account creation, advertising SDK, payment SDK, or analytics SDK was found in the current
  Expressive release configuration.
- Launcher workspace, folders, hidden-app settings, icon choices, and preferences are stored on the
  device. Android backup may copy eligible application data to the user's configured backup
  transport.
- The launcher enumerates installed applications to provide its core Home and All Apps surfaces.
- Contact search is optional and reads contacts only after the user grants permission.
- File search and customization can read user-selected or permission-granted media.
- Usage access is optional and is used on-device to rank app predictions.
- Smartspacer integration exchanges glanceable state with the separately installed Smartspacer
  app when the user enables that integration.
- Google Fonts and configured web-search providers may receive normal network request data when
  the user selects those features.
- A crash report is kept on-device by default. Upload occurs only after the user taps the upload
  action; the report can contain device/app state and is sent to the configured paste service.

## Play Console answers requiring owner confirmation

- Confirm whether any production endpoint logs IP addresses or persistent identifiers.
- Confirm the crash-report host, retention period, deletion process, and third-party processor.
- Confirm the final search providers and whether queries leave the device.
- Confirm whether Android backup is enabled for every category of user-created launcher data.
- Confirm the developer legal name, privacy contact, retention policy, and deletion-request method.
- Re-run dependency and network-traffic review for the exact AAB uploaded to Play.

The Play declaration, public privacy policy, permission disclosures, and observed application
behavior must agree.
