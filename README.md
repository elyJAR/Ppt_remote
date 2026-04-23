# PPT Remote (Phone Volume Buttons)

This project has two parts:

1. `desktop_bridge` (Windows): Detects open PowerPoint files, knows which are already in slideshow mode, and exposes an API to start slideshow / next / previous.
2. `mobile_remote_android` (Android app): Connects to your PC over Wi-Fi. Volume Up sends **next slide**, Volume Down sends **previous slide**.

## How It Works

- The desktop bridge uses PowerPoint COM automation (`pywin32`) to inspect the active `PowerPoint.Application` process.
- It lists open presentations and slideshow windows.
- If you press next/previous for a file that is not in slideshow, it auto-starts slideshow first.
- The Android app polls the bridge every 2 seconds, auto-selects a slideshow file if one exists, and lets you pick a different file manually.
- The Android app auto-detects the desktop bridge over LAN (UDP discovery on port `8788`) and connects automatically.

## 1) Start The Desktop Bridge (Windows)

Prerequisites:

- Windows with Microsoft PowerPoint installed.
- Python 3.10+.
- Open at least one `.pptx` in PowerPoint first.

Steps:

```powershell
# If you are at the project root:
cd desktop_bridge

# If you are already inside desktop_bridge, skip the cd command:
powershell -ExecutionPolicy Bypass -File .\start_bridge.ps1
```

Bridge API runs at:

- `http://0.0.0.0:8787`
- On your network, use your PC IP, for example `http://192.168.1.10:8787`

## 2) Run The Android App

Prerequisites:

- Android Studio (Giraffe+ recommended).
- Phone and PC on the same Wi-Fi.
- USB debugging enabled for first install (or wireless debugging).

Steps:

1. Open `mobile_remote_android` in Android Studio.
2. Let Gradle sync.
3. Run the app on your phone.
4. Wait a few seconds for auto-detection. The app should fill and use `Desktop Bridge URL` automatically.
5. If auto-detection fails, manually set `Desktop Bridge URL` to your PC IP, for example `http://192.168.1.10:8787`.
6. Select the presentation you want to control.
7. Keep this app in foreground and press phone volume buttons:
   - Volume Up -> Next slide
   - Volume Down -> Previous slide

## API Endpoints

- `GET /api/health`
- `GET /api/presentations`
- `POST /api/presentations/{presentation_id}/start`
- `POST /api/presentations/{presentation_id}/next`
- `POST /api/presentations/{presentation_id}/previous`

`presentation_id` is the full PowerPoint file path and is URL-encoded by the Android app automatically.

## Notes

- Android volume key capture works while the app is focused.
- iOS does not allow this same behavior in a straightforward way for App Store apps.
- If your phone cannot reach the bridge, check Windows firewall for inbound ports `8787` (HTTP API) and `8788` (UDP discovery).

## Build On GitHub (No Android Studio Needed)

This repository includes a GitHub Actions workflow that builds the Android APK in the cloud and publishes it as a GitHub pre-release.

Workflow file:

- `.github/workflows/android-prerelease.yml`

How to run:

1. Push your latest code to GitHub.
2. Open GitHub -> Actions -> "Android Build and Pre-Release".
3. Click "Run workflow".
4. Optionally set `release_tag` (example: `pre-v0.1.0`).
5. After completion, open the "Releases" page and download `app-debug.apk` from the latest pre-release.

Automatic trigger:

- If you push a tag that starts with `pre-v` (for example `pre-v0.2.0`), the workflow runs automatically and updates/creates that pre-release.
