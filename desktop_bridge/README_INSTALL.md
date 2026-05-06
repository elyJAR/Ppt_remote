# Installation and Running Guide - PPT Remote Bridge

This document explains how to install and run the **PPT Remote Bridge** on your Windows computer.

## Method 1: Portable Version (No Installation)
The portable version allows you to run the bridge without modifying your system files.

1. **Download/Extract**: Copy the `PptRemoteBridge` folder to a location on your PC (e.g., `Documents` or `C:\Tools`).
2. **Run**: Double-click `PptRemoteBridge.exe` inside the folder.
3. **Tray Icon**: Look for the PowerPoint icon in your system tray (bottom-right corner).
4. **Usage**: Right-click the icon to see your IP address and control options.

---

## Method 2: Installer Version (Inno Setup)
If you used the installer:

1. **Run Setup**: Double-click `PptRemoteBridgeSetup.exe`.
2. **Follow Prompts**: Choose the installation directory and whether to create a desktop shortcut.
3. **Finish**: The bridge will launch automatically after installation.

---

## How to Run & Connect

### 1. Start the Bridge
Ensure the bridge is running. You should see the PowerPoint icon in your system tray. If it's not there, run `PptRemoteBridge.exe`.

### 2. Check Connection
*   **IP Address**: Right-click the tray icon. It will display the bridge URL (e.g., `http://192.168.1.5:8787`).
*   **Network**: Ensure your phone and PC are on the same Wi-Fi network.

### 3. Connect from Mobile App
*   Open the PPT Remote app on your Android device.
*   The app should automatically discover the PC.
*   If discovery fails, enter the IP address shown in the PC tray icon manually.

### 4. Firewall Note
If the phone cannot connect, you may need to allow the bridge through Windows Firewall:
1. Go to **Windows Security** > **Firewall & network protection**.
2. Click **Allow an app through firewall**.
3. Ensure `PptRemoteBridge` has both **Private** and **Public** checked.

---

## Troubleshooting

### Bridge won't start
*   Check `logs/bridge.log` for error messages.
*   Ensure no other application is using port `8787`.

### PowerPoint not detected
*   Ensure PowerPoint is open with at least one presentation.
*   If you just opened PowerPoint, give the bridge a few seconds to detect it.

### Commands not working
*   Ensure the slideshow is actually active in PowerPoint.
*   Some versions of PowerPoint may require "Enable Content" if there are macros.
