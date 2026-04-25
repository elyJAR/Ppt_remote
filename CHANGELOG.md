# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

### Added

#### Desktop Bridge
- **System tray icon** — bridge now runs with a `pystray` tray icon (built with `Pillow`);
  the console window can be fully hidden while the tray icon confirms the service is alive.
- **Rotating file logging** — all bridge output is written to `logs/bridge.log`
  (5 MB per file, 3 rotating backups) so background-mode crashes are diagnosable
  without a visible console.
- **Speaker notes endpoints** — two new read-only GET routes:
  - `GET /api/presentations/{id}/notes` — returns notes for every slide.
  - `GET /api/presentations/{id}/current-notes` — returns notes for the slide
    currently displayed in an active slideshow.
- **API key authentication** — set the `PPT_API_KEY` environment variable to enable
  bearer-token protection on all non-health endpoints (`X-Api-Key` header).
  When the variable is absent the bridge operates in open (unauthenticated) mode.
- **Rate limiting** — action endpoints (`/start`, `/stop`, `/next`, `/previous`) are
  now limited to **30 requests per minute** per client IP via `slowapi`.
- **Input validation on `presentation_id`** — the path parameter is URL-decoded and
  checked for length and emptiness before being passed to the COM controller;
  invalid values receive a `400` response immediately.
- **Configurable ports via environment variables** — `PPT_BRIDGE_PORT` (default `8787`)
  and `PPT_DISCOVERY_PORT` (default `8788`) are now read at startup so the ports can
  be changed without editing source files.
- **FastAPI lifespan context manager** — the `DiscoveryResponder` start/stop lifecycle
  is now managed with `@asynccontextmanager lifespan(app)`, replacing the deprecated
  `@app.on_event` handlers.
- **Windows EXE build CI workflow** — GitHub Actions job that produces
  `PptRemoteBridge.exe` via PyInstaller and attaches it to the GitHub Release.
- **Desktop bridge unit tests** — `pytest` suite using `FastAPI TestClient` covering
  all API routes, rate-limit behaviour, authentication, and `network_detector` logic
  (`desktop_bridge/tests/`).

#### Android App
- **Network warning banners** — an amber `WarningBanner` composable is shown when
  `networkWarning` (phone-side) or `bridgeNetworkWarning` (desktop-side) is non-null,
  alerting the user to potentially unstable hotspot connections.
- **Loading indicator** — a `LinearProgressIndicator` is displayed below the status
  line whenever `isBusy` is `true` (a bridge command is in-flight).
- **Empty state card** — when the bridge returns an empty presentation list, a
  friendly card with an emoji and setup instructions is shown instead of a blank list.
- **Slide progress bar** — each `PresentationItem` card now shows a
  `LinearProgressIndicator` visualising the current slide position within the deck.
- **Dynamic notification text** — the foreground service notification body updates
  to show the active presentation name and current slide number (e.g.
  *"My Deck.pptx — Slide 3 / 12"*).
- **`BootReceiver`** — a `BroadcastReceiver` listening for `BOOT_COMPLETED` and
  `QUICKBOOT_POWERON` (HTC/OnePlus) automatically restarts `RemoteControlService`
  after a phone reboot, provided a bridge URL was previously saved in `RemotePrefs`.
- **Android unit tests** — JUnit 4 test suite using MockK and OkHttp `MockWebServer`
  covering `BridgeClient`, `RemotePrefs`, and `Models`
  (`app/src/test/`).
- **Lint and unit test steps in Android CI** — the `android-prerelease.yml` workflow
  now runs `./gradlew :app:lint` and `./gradlew :app:test` before building the APK;
  the workflow fails fast if either step reports errors.
- **Signed release APK support in CI** — the workflow optionally produces a signed
  release APK when the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and
  `KEY_PASSWORD` repository secrets are present; falls back to a debug APK otherwise.

### Fixed

- **`isBusy` stuck `true` after a successful bridge action** (`MainViewModel.kt`) —
  the success path inside `runBridgeAction()` now correctly resets `isBusy = false`
  in the state copy, unblocking the UI after every completed command.
- **`stop_background.ps1` failing silently for non-admin users** — the script
  previously relied on `$_.CommandLine` via `Get-Process`, which requires elevated
  privileges. It has been rewritten with a three-layer fallback strategy:
  1. Read the PID written to `.bridge.pid` by `start_background.ps1` and kill directly.
  2. Fall back to `Get-WmiObject Win32_Process` (works without admin rights).
  3. Final fallback: kill `PptRemoteBridge.exe` by name if the EXE build is in use.

---

## [1.0.0] — Initial Release

### Added

#### Desktop Bridge
- **PowerPoint COM automation** via `pywin32` — list open presentations,
  query slideshow state, and issue `start`, `stop`, `next`, and `previous` commands
  to `PowerPoint.Application` through the Windows COM interface.
- **Auto-start slideshow on navigation** — if `next` or `previous` is called on a
  presentation that is not yet in slideshow mode, the bridge starts the slideshow
  automatically before advancing the slide.
- **FastAPI HTTP bridge** — RESTful API served by `uvicorn` on port `8787`
  with CORS middleware allowing all origins (designed for trusted LAN use).
- **UDP auto-discovery** — a `DiscoveryResponder` thread listens on port `8788`
  for `PPT_REMOTE_DISCOVER` broadcast packets and replies with the bridge's
  local HTTP URL, allowing the Android app to find the PC without manual IP entry.
- **Network type detection** — `network_detector.py` classifies the desktop
  connection as WiFi, hotspot-providing, hotspot-using, or cellular and exposes
  the result via `GET /api/network/status`.
- **Background execution scripts** — PowerShell scripts for headless operation:
  - `start_background.ps1` — launches the bridge via `pythonw.exe` (no console).
  - `stop_background.ps1` — terminates the background process.
  - `install_background_startup.ps1` / `remove_background_startup.ps1` — manage
    a Windows Scheduled Task that starts the bridge at user login.
- **Auto-startup at Windows login** — `install_startup_task.ps1` registers a
  Scheduled Task (`PptRemoteBridge`) in `-Mode Auto` (EXE if built, Python
  otherwise), `-Mode Exe`, or `-Mode Python`; falls back to HKCU Run registry
  when Task Scheduler access is restricted.
- **Standalone EXE build** — `build_exe.ps1` produces `dist/PptRemoteBridge.exe`
  via PyInstaller so end users can run the bridge without a Python installation.
- **Visible-console dev mode** — `start_bridge.ps1` starts the bridge with a
  normal console window for debugging and development.

#### Android App
- **Jetpack Compose UI** — fully declarative Material 3 interface built with
  Compose (BOM `2024.09.00`) and targeting Android API 26–34.
- **`MainViewModel`** — state holder that drives polling, presentation selection,
  bridge URL discovery, and command dispatch with coroutine-based concurrency.
- **`BridgeClient`** — OkHttp 4 HTTP client wrapping all bridge API calls
  (`list`, `start`, `stop`, `next`, `previous`) with network-aware retry logic.
- **`NetworkDetector`** — classifies the phone's active connection (standard WiFi,
  hotspot, cellular) and feeds the result back into polling-frequency decisions.
- **`RemotePrefs`** — `SharedPreferences` wrapper that persists the bridge URL
  across app restarts.
- **Volume button control** — `onKeyDown` in `MainActivity` intercepts
  `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN` while the app is in the foreground;
  `MediaSession` + `VolumeProvider` extend this to the background.
- **`RemoteControlService`** (foreground service) — keeps the bridge connection
  alive when the app is minimised; holds a `WakeLock` and requests battery
  optimisation exemption on first launch.
- **Persistent notification with actions** — the foreground service notification
  exposes *Previous*, *Next*, *Start*, and *Stop* action buttons so slides can be
  advanced directly from the notification shade.
- **Auto-discovery from Android** — the app broadcasts `PPT_REMOTE_DISCOVER` over
  UDP on port `8788` and fills the bridge URL field automatically on receipt of
  the desktop's reply.
- **2-second polling loop** — `MainViewModel` polls `GET /api/presentations` every
  2 seconds and auto-selects a presentation that is already in slideshow mode.
- **Adaptive polling frequency** — polling interval adjusts based on the detected
  network type (shorter on WiFi, longer on cellular/hotspot) to balance
  responsiveness against battery usage.
- **Network-aware retry with exponential back-off** — failed HTTP requests are
  retried with increasing delays before surfacing an error to the UI.

#### CI / Release
- **GitHub Actions Android CI** — `.github/workflows/android-prerelease.yml`
  builds `app-debug.apk` and publishes it as a GitHub pre-release.
- **Automatic trigger on `pre-v*` tags** — pushing a tag matching `pre-v*`
  (e.g. `pre-v0.1.0`) runs the workflow automatically and attaches the APK to
  the matching pre-release.
- **Manual dispatch** — the workflow can also be triggered on demand from the
  GitHub Actions UI with an optional custom `release_tag` input.

---

[Unreleased]: https://github.com/your-org/ppt-remote/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/your-org/ppt-remote/releases/tag/v1.0.0