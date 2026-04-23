# Android APK Crash Testing Guide

## How to Get Crash Logs

### Method 1: Using ADB (Android Debug Bridge)
```bash
# Connect your phone via USB with USB debugging enabled
adb logcat -c  # Clear previous logs
adb logcat *:E  # Show only errors

# Or save to file
adb logcat > crash_log.txt
```

### Method 2: Using Android Studio
1. Open Android Studio
2. Go to View → Tool Windows → Logcat
3. Connect your phone
4. Install and run the APK
5. Filter by "Error" or search for "FATAL EXCEPTION"

### Method 3: Check System Logs on Device
1. Enable Developer Options on your phone
2. Go to Settings → Developer Options → Take Bug Report
3. Share the bug report file

## New Features for Screen-Off Operation

### Wake Lock
The app now uses a PARTIAL_WAKE_LOCK to keep running even when the screen is off:
- Volume buttons work with screen off
- Network polling continues
- Minimal battery impact (only CPU stays awake, screen stays off)

### Battery Optimization Exemption
On first launch, the app will request to be exempted from battery optimization:
- Prevents Android from killing the app in the background
- Ensures the app stays responsive even after hours
- User can grant or deny this permission

### Permissions Required
- `WAKE_LOCK`: Keeps the app running when screen is off
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Asks to be exempted from battery optimization
- `FOREGROUND_SERVICE`: Runs as a foreground service
- `POST_NOTIFICATIONS`: Shows persistent notification (Android 13+)

## Common Crash Causes and Fixes

### 1. Permission Issues (Android 13+)
**Symptom:** App crashes immediately on start
**Cause:** Missing POST_NOTIFICATIONS permission for Android 13+
**Fix:** The app needs to request notification permission at runtime

### 2. Service Start Failure
**Symptom:** App crashes when trying to start foreground service
**Cause:** Foreground service type mismatch or missing permission
**Fix:** Check AndroidManifest.xml service configuration

### 3. Network Permission
**Symptom:** App crashes when trying to connect to bridge
**Cause:** Missing INTERNET permission
**Fix:** Already added in AndroidManifest.xml

## Test Checklist

- [ ] App installs successfully
- [ ] App opens without crashing
- [ ] Battery optimization dialog appears (first launch)
- [ ] Notification appears (check notification shade)
- [ ] Can enter bridge URL
- [ ] Can discover bridge automatically
- [ ] Volume buttons work when app is in foreground
- [ ] Volume buttons work when app is in background
- [ ] Volume buttons work when screen is OFF
- [ ] App survives screen lock/unlock
- [ ] App survives switching to other apps
- [ ] App stays running for extended periods (30+ minutes)

## Quick Test Build

To build a test APK with better error handling:

```bash
cd mobile_remote_android
./gradlew assembleDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

## Debugging Steps

1. **Check Android Version**
   - Android 13+ requires runtime notification permission
   - Android 12+ has stricter foreground service rules
   - Android 6+ supports battery optimization exemption

2. **Check Logcat for Specific Errors**
   ```bash
   adb logcat | grep -i "pptremote\|crash\|exception\|wakelock"
   ```

3. **Test Without Foreground Service**
   - Comment out `RemoteControlService.start(this)` in MainActivity
   - Rebuild and test if app starts

4. **Check Permissions**
   ```bash
   adb shell dumpsys package com.antigravity.pptremote | grep permission
   ```

5. **Check Battery Optimization Status**
   ```bash
   adb shell dumpsys deviceidle whitelist | grep pptremote
   ```

## Expected Behavior

### On First Launch
1. App opens
2. Battery optimization dialog appears (user can allow or deny)
3. Notification appears: "PowerPoint Remote - Remote control is active - Screen off OK"
4. Main screen shows "Searching for desktop bridge..."
5. If bridge is running, it auto-detects and shows "Connected"

### During Use
1. Volume Up = Next slide
2. Volume Down = Previous slide
3. Works even when app is minimized
4. Works even when screen is OFF
5. Notification stays visible
6. Wake lock keeps app responsive

### Battery Impact
- **Minimal**: Only CPU stays awake, screen stays off
- **Network polling**: Every 2 seconds (very light)
- **Expected battery drain**: ~1-2% per hour of active use

## Troubleshooting Screen-Off Issues

### Volume Buttons Don't Work When Screen Is Off
1. Check if battery optimization is disabled:
   - Settings → Apps → PowerPoint Remote → Battery → Unrestricted
2. Check if notification is visible (indicates service is running)
3. Check logcat for wake lock messages:
   ```bash
   adb logcat | grep "Wake lock"
   ```

### App Stops Working After Some Time
1. Disable battery optimization (see above)
2. Check if the service is still running:
   ```bash
   adb shell dumpsys activity services | grep RemoteControlService
   ```
3. Some manufacturers (Samsung, Xiaomi, Huawei) have aggressive battery management:
   - Samsung: Settings → Apps → PowerPoint Remote → Battery → Allow background activity
   - Xiaomi: Settings → Apps → Manage apps → PowerPoint Remote → Autostart → Enable
   - Huawei: Settings → Apps → PowerPoint Remote → Battery → App launch → Manage manually

## Report Crash Information

Please provide:
1. Android version (e.g., Android 13, Android 14)
2. Phone manufacturer and model
3. Exact error message from logcat
4. When the crash occurs (on start, when pressing button, screen off, etc.)
5. Screenshot of the crash if possible
6. Battery optimization status (allowed/denied)
