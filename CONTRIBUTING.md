# Contributing to PPT Remote

Thank you for your interest in contributing! This guide covers everything you need to get both
subsystems running locally, how to write and run tests, code style expectations, and how to
submit your changes.

---

## Table of Contents

1. [Project Structure Overview](#1-project-structure-overview)
2. [Desktop Bridge — Environment Setup](#2-desktop-bridge--environment-setup)
3. [Android App — Environment Setup](#3-android-app--environment-setup)
4. [Running the Desktop Bridge Locally](#4-running-the-desktop-bridge-locally)
5. [Running the Android App](#5-running-the-android-app)
6. [Running the Test Suite](#6-running-the-test-suite)
7. [Code Style Guidelines](#7-code-style-guidelines)
8. [Submitting Changes](#8-submitting-changes)
9. [Environment Variables Reference](#9-environment-variables-reference)
10. [Two-Part Architecture — Important Notes](#10-two-part-architecture--important-notes)

---

## 1. Project Structure Overview

```
Ppt_remote/
├── desktop_bridge/                  # Windows Python service (FastAPI + COM automation)
│   ├── main.py                      # FastAPI app, all HTTP routes, DiscoveryResponder
│   ├── powerpoint_controller.py     # PowerPoint COM automation (pywin32)
│   ├── network_detector.py          # Detects WiFi / hotspot / cellular network type
│   ├── bridge_service.py            # Entry point used by background/EXE mode
│   ├── run_background.py            # Launches bridge via pythonw.exe (no console)
│   ├── tray_icon.py                 # System tray icon (pystray + Pillow)
│   ├── requirements.txt             # Python dependencies
│   ├── tests/                       # pytest unit + integration tests
│   │   ├── conftest.py
│   │   ├── test_api.py              # FastAPI TestClient integration tests
│   │   └── test_network_detector.py
│   ├── start_bridge.ps1             # Start with visible console (dev mode)
│   ├── start_background.ps1         # Start hidden (background mode)
│   ├── stop_background.ps1          # Stop background service (PID + WMI + EXE)
│   ├── install_startup_task.ps1     # Install Windows Task Scheduler startup
│   ├── remove_startup_task.ps1      # Remove startup task
│   ├── install_background_startup.ps1
│   ├── remove_background_startup.ps1
│   ├── build_exe.ps1                # Build standalone EXE via PyInstaller
│   └── PptRemoteBridge.spec         # PyInstaller spec file
│
├── mobile_remote_android/           # Android app (Kotlin + Jetpack Compose)
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       ├── main/java/com/antigravity/pptremote/
│   │       │   ├── MainActivity.kt          # UI entry point, volume key capture
│   │       │   ├── MainViewModel.kt         # State management, polling, bridge actions
│   │       │   ├── BridgeClient.kt          # OkHttp3 HTTP client for bridge API
│   │       │   ├── RemoteControlService.kt  # Foreground service + MediaSession
│   │       │   ├── BootReceiver.kt          # Auto-restart service after reboot
│   │       │   ├── NetworkDetector.kt       # WiFi / hotspot / cellular detection
│   │       │   ├── RemotePrefs.kt           # SharedPreferences wrapper
│   │       │   └── Models.kt               # Data classes (PresentationDto, etc.)
│   │       └── test/java/com/antigravity/pptremote/
│   │           ├── BridgeClientTest.kt      # MockWebServer unit tests
│   │           ├── ModelsTest.kt
│   │           └── RemotePrefsTest.kt
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── TEST_INSTRUCTIONS.md
│
├── .github/
│   └── workflows/
│       └── android-prerelease.yml   # GitHub Actions: build APK, publish pre-release
│
├── README.md
├── BACKGROUND_EXECUTION.md
├── ARCHITECTURE.md
├── CHANGELOG.md
└── CONTRIBUTING.md                  # ← this file
```

The project is intentionally split into two independent subsystems that communicate over HTTP
and UDP. Changes to the API contract affect **both sides** — see
[Section 10](#10-two-part-architecture--important-notes) before touching any endpoints.

---

## 2. Desktop Bridge — Environment Setup

### Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Windows | 10 / 11 | COM automation requires Windows |
| Microsoft PowerPoint | Any recent version | Must be installed, not just Office Online |
| Python | 3.10 or newer | `python --version` to check |
| pip | Bundled with Python 3.10+ | |

### Step 1 — Clone and enter the directory

```powershell
git clone https://github.com/<your-fork>/Ppt_remote.git
cd Ppt_remote\desktop_bridge
```

### Step 2 — Create and activate a virtual environment (recommended)

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
```

> If you see an execution-policy error, run:
> ```powershell
> Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
> ```

### Step 3 — Install dependencies

```powershell
pip install -r requirements.txt
```

Key packages installed:

| Package | Purpose |
|---|---|
| `fastapi` | HTTP API framework |
| `uvicorn[standard]` | ASGI server |
| `pywin32` | PowerPoint COM automation |
| `pystray` | System tray icon |
| `Pillow` | Image support for tray icon |
| `slowapi` | Rate limiting middleware |
| `pytest` | Test runner |
| `httpx` | Async HTTP client used by TestClient |

### Step 4 — Verify the installation

```powershell
python -c "import fastapi, uvicorn, win32com.client; print('OK')"
```

---

## 3. Android App — Environment Setup

### Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Android Studio | Giraffe (2022.3.1) or newer | Hedgehog / Iguana also work |
| Android SDK | API 26 (Android 8.0) minimum, API 34 target | Install via SDK Manager |
| JDK | 17 | Bundled with recent Android Studio |
| Kotlin | 1.9.x | Managed automatically by Gradle |
| Gradle | Wrapper included | Do not install separately |

### Step 1 — Open the project in Android Studio

1. Launch Android Studio.
2. Choose **File → Open**.
3. Navigate to `Ppt_remote\mobile_remote_android` and click **OK**.
4. Wait for the initial Gradle sync to finish (this may take a few minutes on first open).

### Step 2 — Sync Gradle

If the sync does not start automatically:

- Click **File → Sync Project with Gradle Files**, or
- Click the elephant icon in the toolbar.

### Step 3 — Enable USB debugging on your phone (first install)

1. On your Android device, go to **Settings → About Phone**.
2. Tap **Build Number** seven times to unlock Developer Options.
3. Go to **Settings → Developer Options** and enable **USB Debugging**.
4. Connect your phone via USB and accept the RSA key prompt.

Wireless debugging (Android 11+) also works — enable it under **Developer Options → Wireless
Debugging**.

---

## 4. Running the Desktop Bridge Locally

For development, always use the **visible-console mode** so you can see logs and errors in real
time.

### Dev mode (visible console)

```powershell
cd desktop_bridge

# Option A: via the convenience script
powershell -ExecutionPolicy Bypass -File .\start_bridge.ps1

# Option B: directly with uvicorn (useful for --reload hot-reloading)
uvicorn main:app --host 0.0.0.0 --port 8787 --reload
```

The bridge will print its startup URL:

```
INFO:     Uvicorn running on http://0.0.0.0:8787 (Press CTRL+C to quit)
```

### Verify it is running

```powershell
curl http://localhost:8787/api/health
# Expected: {"status":"ok","network_type":"wifi","is_hotspot":false}
```

### Overriding ports for local testing

```powershell
# PowerShell syntax:
$env:PPT_BRIDGE_PORT = "9000"
$env:PPT_DISCOVERY_PORT = "9001"
uvicorn main:app --host 0.0.0.0 --port 9000
```

### Background mode (no console window)

Background mode is intended for daily use, not for active development. Use it only when you
want to test the hidden-process or auto-startup behaviour:

```powershell
.\start_background.ps1   # starts hidden
.\stop_background.ps1    # stops it
```

See [`BACKGROUND_EXECUTION.md`](BACKGROUND_EXECUTION.md) for full details.

---

## 5. Running the Android App

### On a physical device

1. Ensure the device appears in the **Device Manager** toolbar in Android Studio.
2. Press **Run ▶** (Shift+F10) or use **Run → Run 'app'**.
3. The APK is compiled and installed automatically.

### On the Android Emulator

1. Open **Device Manager** (Tools → Device Manager).
2. Create a virtual device with API 26+ (API 34 recommended).
3. Start the emulator, then press **Run ▶**.

> **Note:** UDP broadcast discovery will **not** work between the emulator and the host
> machine by default. When testing on an emulator, set the bridge URL manually to
> `http://10.0.2.2:8787` (the emulator's alias for the host loopback).

### Connecting to the bridge

- **Auto-discovery**: The app broadcasts a UDP probe on port `8788`. If the desktop bridge
  is running on the same LAN, it responds with its URL automatically.
- **Manual**: Tap the **Desktop Bridge URL** field and enter `http://<PC-IP>:8787`.
  Find your PC IP with `ipconfig` → look for the IPv4 address on your Wi-Fi adapter.

---

## 6. Running the Test Suite

### Desktop Bridge (Python / pytest)

All tests live in `desktop_bridge/tests/`. They use FastAPI's `TestClient` (backed by
`httpx`) and mock out the PowerPoint COM layer so no real PowerPoint installation is needed
to run them.

```powershell
# From the project root:
cd desktop_bridge

# Run all tests:
pytest tests/

# Run with verbose output:
pytest tests/ -v

# Run a single test file:
pytest tests/test_api.py -v

# Run with coverage (install pytest-cov first):
pip install pytest-cov
pytest tests/ --cov=. --cov-report=term-missing
```

### Android (JUnit / MockK / MockWebServer)

Unit tests live in `app/src/test/` and use JUnit 4, MockK, and OkHttp's `MockWebServer`.
They run on the JVM — no device or emulator required.

```powershell
# From mobile_remote_android/:
cd ..\mobile_remote_android

# Run all unit tests:
.\gradlew :app:test

# Run lint checks:
.\gradlew :app:lint

# Run both (useful before opening a PR):
.\gradlew :app:test :app:lint

# On Linux/macOS (CI environment):
./gradlew :app:test :app:lint
```

Test reports are written to:

```
app/build/reports/tests/testDebugUnitTest/index.html
app/build/reports/lint-results-debug.html
```

### Continuous Integration

The GitHub Actions workflow (`.github/workflows/android-prerelease.yml`) runs lint and unit
tests automatically on every push. A PR will not be merged if CI is red.

---

## 7. Code Style Guidelines

### Python (desktop_bridge)

- Follow **[PEP 8](https://peps.python.org/pep-0008/)** strictly.
- Maximum line length: **88 characters** (compatible with `black`).
- Use **type annotations** on all function signatures (`from __future__ import annotations`
  is already at the top of `main.py` — keep it there).
- Use `f-strings` for string formatting; avoid `%`-style or `.format()`.
- All public functions must have a **docstring**.
- Sort imports with `isort` (stdlib → third-party → local), separated by blank lines.

Recommended local tools:

```powershell
pip install black isort flake8
black .
isort .
flake8 . --max-line-length 88
```

### Kotlin (mobile_remote_android)

- Follow the official **[Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)**.
- Use **Jetpack Compose** idioms: stateless composables, state hoisting, `remember` /
  `collectAsState`.
- Prefer `val` over `var`; keep mutation inside the `ViewModel`.
- Use `StateFlow` / `MutableStateFlow` for UI state; avoid `LiveData` for new code.
- Write **KDoc** comments on all public classes and functions.
- Keep composable functions small and focused; extract sub-composables when a function
  exceeds ~40 lines.
- Use `Result` / sealed classes for error handling — do not swallow exceptions silently.

Android Studio's built-in formatter (Ctrl+Alt+L) applies most of these automatically.

---

## 8. Submitting Changes

### Workflow

1. **Fork** the repository on GitHub.

2. **Clone** your fork locally:
   ```bash
   git clone https://github.com/<your-username>/Ppt_remote.git
   cd Ppt_remote
   ```

3. **Create a feature branch** off `main`:
   ```bash
   git checkout -b feature/my-descriptive-name
   # or for bug fixes:
   git checkout -b fix/issue-short-description
   ```

4. **Make your changes.** Keep commits focused — one logical change per commit.

5. **Write or update tests** for any new behaviour.

6. **Run the full test suite** before pushing:
   ```powershell
   # Desktop:
   cd desktop_bridge && pytest tests/ -v

   # Android:
   cd ..\mobile_remote_android && .\gradlew :app:test :app:lint
   ```

7. **Push** your branch and open a **Pull Request** against `main`:
   ```bash
   git push origin feature/my-descriptive-name
   ```

8. Fill out the PR template:
   - What problem does this solve?
   - Which subsystem(s) are affected (`desktop_bridge`, `mobile_remote_android`, or both)?
   - Link any related issues.
   - Attach screenshots for UI changes.

9. CI will run automatically. Address any failures before requesting review.

### Branch naming conventions

| Prefix | Use for |
|---|---|
| `feature/` | New functionality |
| `fix/` | Bug fixes |
| `docs/` | Documentation-only changes |
| `refactor/` | Internal restructuring with no behaviour change |
| `ci/` | CI/CD workflow changes |

### Commit message style

Use the **imperative mood** in the subject line (50 chars or fewer):

```
Add rate limiting to action endpoints
Fix isBusy not reset after successful command
Update CONTRIBUTING with Android setup steps
```

Include a body if the change is non-obvious, and reference issue numbers (`Fixes #42`).

---

## 9. Environment Variables Reference

These variables are read at **startup** by the desktop bridge (`main.py`). Set them in your
shell or in the process environment before launching the bridge.

| Variable | Default | Description |
|---|---|---|
| `PPT_BRIDGE_PORT` | `8787` | TCP port the FastAPI HTTP server listens on. Change this if `8787` is already in use on your machine. The Android app must be pointed at the same port. |
| `PPT_DISCOVERY_PORT` | `8788` | UDP port the `DiscoveryResponder` listens on. The Android app broadcasts to this port to auto-detect the bridge. Both sides must agree on this value. |
| `PPT_API_KEY` | *(unset)* | When set, all API endpoints (except `GET /api/health`) require the caller to send an `X-Api-Key: <value>` HTTP header. Leave unset for open/local-only mode. |

### Setting environment variables — PowerShell (temporary, current session only)

```powershell
$env:PPT_BRIDGE_PORT     = "9090"
$env:PPT_DISCOVERY_PORT  = "9091"
$env:PPT_API_KEY         = "my-secret-key"
```

### Setting environment variables — System-wide (persistent)

```powershell
[System.Environment]::SetEnvironmentVariable("PPT_BRIDGE_PORT", "9090", "User")
```

Or via **System Properties → Advanced → Environment Variables** in the Windows UI.

---

## 10. Two-Part Architecture — Important Notes

PPT Remote is a **two-part system**: the desktop bridge (Python/Windows) and the Android app
(Kotlin) communicate over a shared HTTP API. This has a critical implication for contributors:

> **Any change to an API endpoint must be matched by a corresponding change on the other side.**

### Specific rules

| Change | Desktop file(s) to update | Android file(s) to update |
|---|---|---|
| Add a new endpoint | `main.py` (route + DTO) | `BridgeClient.kt` (new call) + `MainViewModel.kt` (trigger logic) |
| Rename or re-path an endpoint | `main.py` | `BridgeClient.kt` |
| Change a request or response body | `main.py` (Pydantic model) | `Models.kt` (data class) + `BridgeClient.kt` |
| Change `PPT_BRIDGE_PORT` default | `main.py` | `BridgeClient.kt` / `RemotePrefs.kt` default value |
| Change `PPT_DISCOVERY_PORT` default | `main.py` (`DiscoveryResponder`) | `RemoteControlService.kt` or wherever the UDP probe is sent |
| Add an `X-Api-Key` header requirement | `main.py` (`verify_api_key` dep) | `BridgeClient.kt` (add header to all requests) |

### Port firewall rules

Windows Firewall must have **inbound** rules open for both ports. Users who have trouble
connecting should check:

```powershell
# Test HTTP API from the PC itself:
curl http://localhost:8787/api/health

# Check if ports are listening:
netstat -ano | findstr ":8787"
netstat -ano | findstr ":8788"
```

If the Android app cannot reach the bridge, the most common cause is a missing inbound
firewall rule for port `8787` (TCP) or `8788` (UDP). Direct users to add rules via
**Windows Defender Firewall → Advanced Settings → Inbound Rules**.

### Discovery protocol (UDP)

The Android app sends the ASCII string `PPT_REMOTE_DISCOVER` as a UDP broadcast to port
`8788`. The desktop bridge `DiscoveryResponder` listens on that port, recognises the token,
and replies with a JSON payload:

```json
{ "bridge_url": "http://192.168.1.10:8787" }
```

The IP in the response is the local interface address that routes back to the requesting
device — not necessarily `0.0.0.0`. Do not change the discovery token string without
updating both sides.