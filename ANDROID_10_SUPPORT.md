# Android 10+ Support Guide

This document outlines the architecture and flows implemented to ensure PPT Remote is fully compatible with Android 10 (API 29) through Android 15 (API 35).

## 1. Storage & FTP (Scoped Storage)
Android 10 introduced **Scoped Storage**, which restricts how apps access the filesystem. To ensure the FTP server can share files from the entire device, the following strategies are used:

### Android 10 (API 29)
- **Strategy**: Legacy External Storage.
- **Implementation**: `android:requestLegacyExternalStorage="true"` is set in the `<application>` tag of the manifest.
- **Flow**: The app bypasses Scoped Storage and accesses files using standard absolute paths.

### Android 11+ (API 30 to 35)
- **Strategy**: All Files Access.
- **Permission**: `MANAGE_EXTERNAL_STORAGE`.
- **User Flow**: 
    1. The app checks if `Environment.isExternalStorageManager()` is true.
    2. If false, it opens `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`.
    3. The user must manually grant "All Files Access" to PPT Remote in the system settings.
- **Rationale**: This is the only way for an FTP server to provide access to the full SD card on modern Android versions.

---

## 2. Network & Hotspot Detection
Modern Android versions restrict access to network metadata (like SSID) and hidden tethering APIs.

### Permissions
- **Location**: `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` are required on Android 10+ to retrieve the SSID of the current WiFi network.
- **State**: `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` are required to monitor and toggle hotspot states.

### Implementation Details
- **ConnectivityManager**: Uses the modern `NetworkCallback` (Android 10+) to monitor network changes without polling.
- **Hotspot Detection**: Uses reflection to call `getTetheredIfaces`. On Android 11+, this logic includes filters for specific interface names (e.g., `wlan`, `ap`) to avoid false positives from USB tethering.

---

## 3. Background Services & Notifications
Android 13 and 14 introduced strict requirements for background tasks to improve battery life and privacy.

### Notification Permission (Android 13+)
- **Permission**: `POST_NOTIFICATIONS`.
- **Flow**: The app requests this permission at startup. If denied, the remote control notification (foreground service) will not be visible to the user.

### Foreground Service Types (Android 14+)
- **Implementation**: The `RemoteControlService` is declared with `android:foregroundServiceType="specialUse"` in the manifest.
- **Permission**: `FOREGROUND_SERVICE_SPECIAL_USE`.
- **Rationale**: Android 14+ requires every foreground service to have a declared type. Since our service manages multiple background tasks (Remote Control + FTP), "specialUse" is the most appropriate category.

---

## 4. Hardware Interaction (Haptics)
- **Android 12+**: Uses the modern `VibratorManager` API.
- **Android 8 to 11**: Uses the `VibrationEffect` API.
- **Pre-Android 8**: Falls back to legacy `vibrate(long)` method.

---

## 5. Summary of Manifest Requirements
```xml
<!-- Hardware & Network -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<!-- Background & Notifications -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Storage -->
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />

<application
    android:requestLegacyExternalStorage="true"
    ...>
    <service
        android:name=".RemoteControlService"
        android:foregroundServiceType="specialUse" />
</application>
```
