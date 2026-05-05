# Feature Proposal: Android FTP Server & Desktop Explorer Integration

## Overview
This feature allows users to access their Android device's files directly from their Windows PC via a one-click FTP server on the mobile app and a corresponding "Open File Explorer" option in the Desktop Bridge tray icon.

## User Flow

### 1. Mobile App Setup
- User opens the **PPT Remote** Android app.
- User toggles the **FTP Server** switch in the main UI or enables **Auto-start FTP** in Settings.
- The app starts a background service running an FTP server (default port 2121).
- The app displays its current IP and FTP status.

### 2. Desktop Bridge Discovery
- As soon as the Android app sends a command (or heartbeat) to the Desktop Bridge, the Bridge captures the phone's LAN IP address.
- The Desktop Bridge tray icon menu dynamically updates to include: **📁 Open Android Files**.

### 3. File Access
- User right-clicks the Bridge tray icon and selects **📁 Open Android Files**.
- The Bridge launches Windows Explorer pointed at `ftp://<phone-ip>:2121`.
- User can now drag and drop files (like presentations) directly between their PC and Android device.

## Technical Implementation Details

### Android Side
- **Library**: `org.apache.ftpserver:ftpserver-core` (or a lightweight alternative).
- **Service**: `FtpServerService` to keep the server running even when the app is in the background.
- **Permissions**: `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (or `MANAGE_EXTERNAL_STORAGE` for Android 11+).
- **Settings**:
    - `ftp_enabled` (Boolean)
    - `ftp_auto_start` (Boolean) - Starts server on app launch.

### Desktop Side
- **IP Tracking**: Middleware in the FastAPI app (`main.py`) to store the most recent client IP.
- **Tray Menu**: Update `tray_icon.py` to add the "Open Android Files" option.
- **Process Launch**: Use `os.startfile()` or `subprocess.Popen` to open the FTP URL in Windows Explorer.

## Edge Cases & Considerations
- **Firewall**: Users may need to allow FTP traffic on their local network.
- **IP Changes**: The bridge must update the FTP link if the phone's IP changes.
- **Security**: For simplicity, it will be anonymous access on the LAN, but could be extended with a password.
- **Backgrounding**: The Android app should use a Foreground Service to ensure the FTP server isn't killed by the OS.
