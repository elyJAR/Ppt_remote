# Background Execution Guide

This guide explains how to run both the Android app and Desktop Bridge in the background.

## Android App - Background Execution

### Features
- **Foreground Service**: Keeps the app running even when minimized
- **Persistent Notification**: Shows that the remote control is active
- **Volume Button Control**: Works even when the app is in the background
- **Auto-start**: Service starts automatically when you open the app

### How It Works
1. When you launch the app, a foreground service starts automatically
2. A persistent notification appears showing "Remote control is active"
3. The app continues to listen for commands even when:
   - You switch to other apps
   - The screen is locked
   - The phone is in your pocket

### Permissions Required
- **FOREGROUND_SERVICE**: Allows the app to run in the background
- **POST_NOTIFICATIONS**: Shows the persistent notification (Android 13+)

### User Experience
- Tap the notification to return to the app
- The service continues running until:
  - You force-stop the app from Settings
  - You restart your phone
  - The system kills it due to low memory (rare)

### Battery Impact
The foreground service is designed to be lightweight and should have minimal battery impact. The notification ensures Android gives the app priority to stay running.

---

## Desktop Bridge - Background Execution

### Quick Start

#### Option 1: Manual Background Start
```powershell
# Start the bridge in background (no console window)
.\start_background.ps1

# Stop the bridge
.\stop_background.ps1
```

#### Option 2: Automatic Startup on Login
```powershell
# Install automatic startup (runs at Windows login)
.\install_background_startup.ps1

# Remove automatic startup
.\remove_background_startup.ps1
```

### Features
- **Hidden Console**: Runs without showing a command prompt window
- **Auto-start on Login**: Optional scheduled task to start at Windows login
- **Minimal Logging**: Reduced log output for cleaner background operation
- **Easy Management**: Simple scripts to start, stop, and configure

### How It Works

#### Background Execution
The `start_background.ps1` script uses `pythonw.exe` (Python without console) to run the bridge service invisibly. The service:
- Listens on port 8787 for API requests
- Responds to UDP discovery on port 8788
- Runs with minimal logging (warnings only)

#### Automatic Startup
The `install_background_startup.ps1` creates a Windows Scheduled Task that:
- Runs at user login
- Starts the bridge automatically
- Runs hidden in the background
- Works even on battery power
- Requires network connectivity

### Management Scripts

| Script | Purpose |
|--------|---------|
| `start_background.ps1` | Start the bridge in background mode |
| `stop_background.ps1` | Stop all running bridge processes |
| `install_background_startup.ps1` | Enable automatic startup at login |
| `remove_background_startup.ps1` | Disable automatic startup |
| `start_bridge.ps1` | Start with visible console (for debugging) |

### Checking If It's Running

**Method 1: Test the API**
```powershell
curl http://localhost:8787/api/health
```

**Method 2: Check Task Manager**
- Open Task Manager (Ctrl+Shift+Esc)
- Look for `pythonw.exe` or `python.exe` processes
- Check the command line includes `run_background.py`

**Method 3: Check from Android App**
- Open the PowerPoint Remote app
- It should automatically discover the bridge
- Status should show "Connected" or list available presentations

### Troubleshooting

#### Bridge Won't Start
1. Ensure Python is installed and in PATH
2. Check if port 8787 is already in use:
   ```powershell
   netstat -ano | findstr :8787
   ```
3. Try starting with visible console for error messages:
   ```powershell
   .\start_bridge.ps1
   ```

#### Automatic Startup Not Working
1. Check if the scheduled task exists:
   ```powershell
   Get-ScheduledTask -TaskName "PowerPointBridgeBackground"
   ```
2. Verify the task is enabled
3. Check task history in Task Scheduler (taskschd.msc)

#### Can't Stop the Bridge
1. Use the stop script:
   ```powershell
   .\stop_background.ps1
   ```
2. If that fails, use Task Manager to end the process
3. As last resort, restart your computer

### Security Notes
- The bridge listens on all network interfaces (0.0.0.0)
- Ensure your firewall is configured appropriately
- Only use on trusted networks
- The bridge has no authentication (designed for local network use)

### Performance
- **CPU Usage**: Minimal when idle, brief spikes during commands
- **Memory**: ~50-100 MB depending on Python environment
- **Network**: Only active when receiving commands or during discovery
- **Startup Time**: ~2-3 seconds

---

## Testing Background Execution

### Android App Test
1. Open the PowerPoint Remote app
2. Connect to your desktop bridge
3. Press the Home button (app goes to background)
4. Pull down notification shade - you should see "Remote control is active"
5. Use volume buttons to control slides
6. Tap notification to return to app

### Desktop Bridge Test
1. Start the bridge in background:
   ```powershell
   .\start_background.ps1
   ```
2. Verify no console window appears
3. Test the API:
   ```powershell
   curl http://localhost:8787/api/health
   ```
4. Open the Android app and verify it discovers the bridge
5. Stop the bridge:
   ```powershell
   .\stop_background.ps1
   ```

### End-to-End Test
1. Install automatic startup on desktop
2. Restart your computer
3. Wait 10-15 seconds after login
4. Open Android app
5. Verify automatic discovery works
6. Control a PowerPoint presentation
7. Minimize the Android app
8. Verify volume buttons still work

---

## Comparison: Old vs New Behavior

### Android App

| Aspect | Before | After |
|--------|--------|-------|
| Background execution | Killed when minimized | Stays running with foreground service |
| Volume buttons | Only work when app is visible | Work even when app is in background |
| Notification | None | Persistent notification shows status |
| User awareness | No indication app is running | Clear notification shows active status |

### Desktop Bridge

| Aspect | Before | After |
|--------|--------|-------|
| Console window | Visible command prompt | Hidden background process |
| Startup | Manual start required | Optional automatic startup at login |
| Logging | Verbose output | Minimal logging (warnings only) |
| Management | Manual process management | Simple start/stop scripts |
| User experience | Intrusive console window | Invisible background service |

---

## Uninstalling Background Features

### Android App
The foreground service is part of the app. To disable:
1. Uninstall the app, or
2. Force-stop the app from Android Settings > Apps

### Desktop Bridge
```powershell
# Remove automatic startup
.\remove_background_startup.ps1

# Stop the service
.\stop_background.ps1

# Delete the background scripts (optional)
Remove-Item start_background.ps1, stop_background.ps1, run_background.py, install_background_startup.ps1, remove_background_startup.ps1
```
