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
- [ ] Notification appears (check notification shade)
- [ ] Can enter bridge URL
- [ ] Can discover bridge automatically
- [ ] Volume buttons work when app is in foreground
- [ ] Volume buttons work when app is in background
- [ ] App survives screen lock/unlock
- [ ] App survives switching to other apps

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

2. **Check Logcat for Specific Errors**
   ```bash
   adb logcat | grep -i "pptremote\|crash\|exception"
   ```

3. **Test Without Foreground Service**
   - Comment out `RemoteControlService.start(this)` in MainActivity
   - Rebuild and test if app starts

4. **Check Permissions**
   ```bash
   adb shell dumpsys package com.antigravity.pptremote | grep permission
   ```

## Expected Behavior

### On First Launch
1. App opens
2. Notification appears: "PowerPoint Remote - Remote control is active"
3. Main screen shows "Searching for desktop bridge..."
4. If bridge is running, it auto-detects and shows "Connected"

### During Use
1. Volume Up = Next slide
2. Volume Down = Previous slide
3. Works even when app is minimized
4. Notification stays visible

## Report Crash Information

Please provide:
1. Android version (e.g., Android 13, Android 14)
2. Phone model
3. Exact error message from logcat
4. When the crash occurs (on start, when pressing button, etc.)
5. Screenshot of the crash if possible
