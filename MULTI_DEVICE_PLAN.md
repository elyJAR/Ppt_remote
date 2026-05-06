# Multi-Device Support Implementation Plan

This document outlines the strategy for supporting multiple PCs (Bridges) and multiple Android devices on the same network.

## 1. Objectives
- **PC Discovery**: Android devices must see a list of all active PCs on the network.
- **Explicit Selection**: Users select which PC to control via a dropdown/list.
- **Device Identification**: PCs must recognize which phone is sending commands.
- **Reverse Explorer**: PCs must allow selecting which phone's files to open in Windows Explorer.

---

## 2. Technical Architecture

### A. Enhanced Discovery (UDP)
Currently, the discovery response is a simple JSON with a URL. We will expand this:
- **New Response Schema**:
  ```json
  {
    "bridge_id": "unique-uuid-or-mac",
    "bridge_name": "ElyJah's Workstation",
    "bridge_url": "http://192.168.1.5:8787",
    "version": "1.9.6"
  }
  ```
- **Bridge Name**: Defaults to the Windows Hostname, but overridable in Bridge settings (via a new `.bridge_name` config file or environment variable).

### B. Android Bridge Management
- **State Change**: `MainViewModel` will transition from a single `bridgeUrl` to a `SelectedBridge` model and a list of `AvailableBridges`.
- **Persistence**: Store the last selected `bridge_id` in `RemotePrefs` to auto-reconnect on startup.

### C. Client Registration (Heartbeat)
To allow the PC to "see" multiple phones:
- **Registration API**: New endpoint `POST /api/clients/register`.
- **Payload**:
  ```json
  {
    "device_id": "android-uuid",
    "device_name": "Samsung S24 Ultra",
    "ftp_port": 2121
  }
  ```
- **Tracking**: The Bridge will maintain an in-memory registry of active clients with TTL (Time-To-Live) logic.

---

## 3. User Interface Flows

### Android App Flow
1. **Discovery**: App broadcasts UDP discovery packet.
2. **Selection**: A "Connect to PC" dropdown appears at the top of the screen.
3. **Control**: Once selected, the app begins polling and sending commands *only* to that PC.
4. **Switching**: User can tap the dropdown at any time to switch to a different PC.

### Desktop Bridge Flow
1. **Tray Menu**: The "Open Android Files" menu item becomes a sub-menu.
2. **List Clients**: The sub-menu lists all phones that have "registered" recently (e.g., "Open Samsung S24", "Open Pixel 8").
3. **Explorer**: Selecting a device triggers `explorer.exe ftp://<phone_ip>:2121/`.

---

## 4. Implementation Steps

### Phase 1: Bridge Enhancements (Python)
- Update `main.py` and `bridge_service.py` to include `hostname` in discovery.
- Add support for a `custom_bridge_name` in the Bridge configuration.
- Implement `/api/clients/register` endpoint.
- Update `TrayIconManager` to support dynamic sub-menus for multiple clients.

### Phase 2: Android Core Refactor (Kotlin)
- Update `BridgeClient` to return `List<BridgeInfo>`.
- Update `MainViewModel` to manage the bridge list and selection logic.
- Implement background registration/heartbeat to the selected PC.

### Phase 3: UI Updates (Compose)
- Create a `BridgeSelector` component.
- Integrate the selector into the `MainActivity` header/warning area.

---

## 5. Security & Stability
- **API Keys**: Each bridge can have its own API key. The phone will store a map of `BridgeID -> APIKey`.
- **Conflict Handling**: If two phones control the same PC, the PC follows the "last command wins" principle.
- **Network Changes**: If the phone switches WiFi, it triggers a fresh discovery and re-validates the selection.
