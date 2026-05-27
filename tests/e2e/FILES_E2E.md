
# Manual E2E Checklist — Files / FTP

This checklist documents manual end-to-end scenarios and quick verification steps for the
Files browsing feature and the Desktop Bridge `POST /api/ftp/open` flow.

Runbook (preconditions)

- PC and Android phone are on the same Wi-Fi network (or phone hotspot + PC client).
- Desktop Bridge is running on the PC (see `start_bridge.ps1`).
- The Android app is installed and the FTP feature is enabled in Settings.

Test matrix

- Android OS: 11 (R) and above — verify `MANAGE_EXTERNAL_STORAGE` flow
- Android OS: 10 and below — verify SAF `OpenDocumentTree` flow
- Devices: at least one physical device and one emulator if possible

Scenarios

1) Discovery & FTP open (happy path)

   - Start Desktop Bridge on PC.
   - Enable FTP on the phone and set files root to a known folder containing a few files (e.g. `DCIM/Camera`).
   - From phone Files UI, tap `Open on PC` for that folder.
   - Verify: Windows Explorer opens and shows the FTP location with the expected files.

2) Encoding (spaces, unicode, percent)

   - Create a folder on the phone named `My Folder/Project & Stuff` (or similar with spaces and an ampersand).
   - Use `Open on PC` and verify Explorer opens the folder path; note that Explorer is given a percent-encoded FTP URL.

3) Permission flow (Android 11+)

   - On Android 11+, revoke All files access for the app via Settings.
   - Open the app, go to Files, tap `Grant access` and follow the Settings flow to enable 'All files access'.
   - Return to the app and confirm the Files list populates and the Grant CTA disappears.

4) Permission flow (SAF fallback)

   - On Android 10 or when All files access is not available, tap `Grant access` to open the SAF picker.
   - Select a folder and grant persistable permission.
   - Confirm the app lists files and persists the selection across app restarts.

5) Offline bridge / no client IP

   - Stop the Desktop Bridge or ensure the PC is unreachable.
   - From the phone, attempt `Open on PC` and confirm the app surfaces a friendly error or the bridge returns `400`.

6) Explorer launch failure (simulated)

   - On the PC, temporarily block `explorer.exe` execution or simulate failure in the bridge dev build.
   - Attempt `Open on PC` and verify the bridge returns `400` and the phone displays an error.

Notes

- Use `adb logcat` and bridge logs (`desktop_bridge/logs/bridge.log`) to capture failures.
- If discovery fails, test manual entry of the bridge URL in the app Settings, then repeat the tests.
