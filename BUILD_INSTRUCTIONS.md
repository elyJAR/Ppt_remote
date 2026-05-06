# Build and Release Instructions

This document outlines the standardized build process for the PPT Remote project.

## 📱 Android App (APK)

The Android app is built automatically using GitHub Actions.

- **Process**: Triggered on every tag push matching `pre-v*` or manually via the "Actions" tab.
- **Workflow**: `.github/workflows/android-prerelease.yml`
- **Output**: A signed release APK attached to a GitHub Pre-release.
- **Note**: The Gradle wrapper is generated on-the-fly in CI; local builds require `gradle wrapper` if not already initialized. Local builds are not allowed.

## 🌉 Desktop Bridge (EXE)

There are two versions of the Windows executable: the **Installer** and the **Portable EXE**.

### 1. Windows Installer (NSIS)

The installer version is built **locally** and then manually uploaded to GitHub.

- **Requirement**: [NSIS (Nullsoft Scriptable Install System)](https://nsis.sourceforge.io/Download) must be installed.
- **Build Script**: `desktop_bridge/build_nsis.ps1`
- **Process**:
  1. Ensure the bridge is built (using `build_portable.ps1` or similar to populate `dist/`).
  2. Run `powershell -File build_nsis.ps1`.
  3. The output `dist\PptRemoteBridgeSetup_NSIS.exe` should be uploaded to the corresponding GitHub Release.

### 2. Portable EXE (Standalone)

The portable version is built automatically on GitHub.

- **Process**: Triggered on every tag push matching `pre-v*` or manually via the "Actions" tab.
- **Workflow**: `.github/workflows/windows-exe-build.yml`
- **Output**: A standalone `PptRemoteBridge.exe` attached to the GitHub Pre-release.

---

## 🛠️ Summary Table

| Artifact          | Build Environment | Tooling         | Release Method               |
| :---------------- | :---------------- | :-------------- | :--------------------------- |
| **Android APK**   | GitHub Actions    | Gradle / JDK 21 | Auto-attached to Pre-release |
| **Portable EXE**  | GitHub Actions    | PyInstaller     | Auto-attached to Pre-release |
| **Installer EXE** | Local Machine     | NSIS (makensis) | Manual Upload to Release     |
