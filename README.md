# PPT Remote (Phone Volume Buttons)

This project has two parts:

1. `desktop_bridge` (Windows): Detects open PowerPoint files, knows which are already in slideshow mode, and exposes an API to start slideshow / next / previous.
2. `mobile_remote_android` (Android app): Connects to your PC over Wi-Fi. Volume Up sends **next slide**, Volume Down sends **previous slide**.

## ✨ What's New in v2.0.2

- **Modern Sidebar Navigation**: Access all connection status, settings, and bridge selection from a sleek, slide-out drawer.
- **Multi-Device Support**: The bridge now manages multiple connected mobile devices, and the Android app can discover and switch between multiple PCs on the network.
- **Improved Tray Feedback**: The Windows system tray icon now shows exactly how many mobile devices are currently connected.
- **Remote FTP Trigger**: Open your phone's storage in Windows File Explorer directly from the Android UI or the bridge tray.

## How It Works

- The desktop bridge uses PowerPoint COM automation (`pywin32`) to inspect the active `PowerPoint.Application` process.
- It lists open presentations and slideshow windows.
- The Android app auto-detects all available desktop bridges over LAN (UDP discovery on port `8788`).
- You can manage multiple PC connections and switch between them via the sidebar.
- If you press next/previous for a file that is not in slideshow, it auto-starts slideshow first.
- The Android app polls the bridge every 2 seconds to keep the slide preview and status in sync.

## 1) Start The Desktop Bridge (Windows)

Prerequisites:

- Windows with Microsoft PowerPoint installed.
- Python 3.10+.
- Open at least one `.pptx` in PowerPoint first.

Steps:

```powershell
# If you are at the project root:
cd desktop_bridge

# Start the bridge (interactive mode):
powershell -ExecutionPolicy Bypass -File .\start_bridge.ps1
```

Bridge API runs at: `http://0.0.0.0:8787`

## 2) Run The Android App

Prerequisites:

- Android Phone and PC on the same Wi-Fi.
- [Latest APK](https://github.com/elyJAR/Ppt_remote/releases) installed.

Steps:

1. Open the PPT Remote app.
2. The app will auto-discover your PC. If multiple PCs are found, you can pick one from the **Sidebar**.
3. Use the **Sidebar** (swipe from left or tap menu icon) to manage connections and access **Settings**.
4. Use the on-screen controls or your phone **Volume Buttons**:
   - Volume Up -> Next slide
   - Volume Down -> Previous slide
5. Controls work even with the **screen off** via a persistent background notification.

## API Endpoints

- `GET /api/health` - Check bridge status
- `GET /api/presentations` - List open presentations
- `POST /api/presentations/{id}/start` - Start slideshow
- `POST /api/presentations/{id}/stop` - Stop slideshow
- `POST /api/presentations/{id}/next` - Next slide
- `POST /api/presentations/{id}/previous` - Previous slide
- `GET /api/presentations/{id}/notes` - Get all speaker notes
- `GET /api/presentations/{id}/current-notes` - Get notes for current slide
- `GET /api/presentations/{id}/current-thumbnail` - Get PNG preview of active slide
- `POST /api/ftp/open` - Open mobile FTP in Windows Explorer
- `POST /api/clients/register` - Register a new mobile client
- `POST /api/bridge/rename` - Change the bridge's display name

## Background Execution

### Android App
- **Foreground Service**: Keeps the app active when minimized.
- **Volume capture**: Control slides without unlocking your phone.
- **Settings**: Adjust polling intervals, theme, and API keys.

### Desktop Bridge
- **Hidden Mode**: Run in the system tray without a console window.
- **Tray Menu**: Quick access to presentation controls and "Open Android Files".
- **Auto-start**: Use `.\install_background_startup.ps1` to run on Windows login.

## 📁 Android File Access (FTP)

You can access your phone's files directly from Windows Explorer:

1. **Enable on Phone**: Toggle **FTP Server** in the main app screen.
2. **Open on PC**: 
   - Tap **Open on PC** in the Android app (if connected).
   - OR Right-click the **Bridge Icon** in your PC tray and select **📁 Open Android Files**.
3. Windows Explorer will open the phone's storage (port 2121) as a network folder.

## Build On GitHub (APK)

This repository automatically builds the Android APK in the cloud.

How to trigger a build:
- Push a tag starting with `pre-v` (e.g., `pre-v2.0.2`).
- The GitHub Action will compile the project and publish the APK as a pre-release.

---
Developed by **Antigravity AI**
