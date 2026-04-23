# PPT Remote (Phone Volume Buttons)

This project has two parts:

1. `desktop_bridge` (Windows): Detects open PowerPoint files, knows which are already in slideshow mode, and exposes an API to start slideshow / next / previous.
2. `mobile_remote_android` (Android app): Connects to your PC over Wi-Fi. Volume Up sends **next slide**, Volume Down sends **previous slide**.

## How It Works

- The desktop bridge uses PowerPoint COM automation (`pywin32`) to inspect the active `PowerPoint.Application` process.
- It lists open presentations and slideshow windows.
- If you press next/previous for a file that is not in slideshow, it auto-starts slideshow first.
- You can start or stop slideshow directly from the phone app.
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
7. Use the on-screen controls as needed:
   - `Start Slideshow` starts slideshow for selected presentation.
   - `Stop` exits slideshow for selected presentation.
8. Keep this app in foreground and press phone volume buttons:
   - Volume Up -> Next slide
   - Volume Down -> Previous slide

## API Endpoints

- `GET /api/health`
- `GET /api/presentations`
- `POST /api/presentations/{presentation_id}/start`
- `POST /api/presentations/{presentation_id}/stop`
- `POST /api/presentations/{presentation_id}/next`
- `POST /api/presentations/{presentation_id}/previous`

`presentation_id` is the full PowerPoint file path and is URL-encoded by the Android app automatically.

## Background Execution

Both the Android app and desktop bridge now support background execution:

### Android App
- **Foreground Service**: Keeps the app running even when minimized
- **Volume buttons work in background**: Control slides even when the app is not visible
- **Persistent notification**: Shows when the remote control is active
- Automatically starts when you open the app

### Desktop Bridge
- **Hidden background mode**: Run without showing a console window
- **Auto-start on login**: Optional automatic startup when Windows starts
- **Easy management**: Simple PowerShell scripts to start/stop

For detailed setup instructions, see [BACKGROUND_EXECUTION.md](BACKGROUND_EXECUTION.md)

Quick start for background mode:
```powershell
cd desktop_bridge

# Start in background (no console window)
.\start_background.ps1

# Stop background service
.\stop_background.ps1

# Install automatic startup at login
.\install_background_startup.ps1
```

## Notes

- Android volume key capture now works even when the app is in the background (thanks to foreground service).
- iOS does not allow this same behavior in a straightforward way for App Store apps.
- If your phone cannot reach the bridge, check Windows firewall for inbound ports `8787` (HTTP API) and `8788` (UDP discovery).

## Run Bridge As Background EXE At Startup

This setup builds a standalone Windows executable and configures it to auto-start when you log in.

From `desktop_bridge`:

```powershell
# Build standalone executable
powershell -ExecutionPolicy Bypass -File .\build_exe.ps1

# Install startup task and run it now
powershell -ExecutionPolicy Bypass -File .\install_startup_task.ps1
```

If EXE build fails (for example DNS/network errors downloading PyInstaller), you can still install background startup using Python mode:

```powershell
powershell -ExecutionPolicy Bypass -File .\install_startup_task.ps1 -Mode Python
```

`-Mode Auto` (default) uses the EXE when available and falls back to Python when it is not.

The executable path is:

- `desktop_bridge\dist\PptRemoteBridge.exe`

To remove startup behavior later:

```powershell
powershell -ExecutionPolicy Bypass -File .\remove_startup_task.ps1
```

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
