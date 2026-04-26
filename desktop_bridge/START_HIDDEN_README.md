# Starting PPT Remote Bridge Completely Hidden

## Problem
When you run `start_background.ps1` from a visible PowerShell window, the PowerShell window remains open even though the Python process runs hidden.

## Solutions

### Option 1: Use the Hidden Batch File (Recommended)
Double-click `start_hidden.bat` - this will start the bridge with no visible windows at all.

### Option 2: Use PowerShell with Hidden Window Style
Open Command Prompt (not PowerShell) and run:
```cmd
powershell.exe -WindowStyle Hidden -ExecutionPolicy Bypass -File "desktop_bridge\start_background.ps1"
```

### Option 3: Use the VBS Script
Double-click `start_hidden.vbs` - this will start the bridge completely silently.

### Option 4: Install Auto-Startup (Best for Regular Use)
Run `install_background_startup.ps1` once to set up automatic startup at Windows login. The bridge will start hidden every time you log in.

## Verification
After starting, check your system tray (bottom-right corner) for the PPT Remote Bridge icon. There should be no visible windows or console.

## Stopping the Bridge
Always use `stop_background.ps1` to properly stop the bridge service.