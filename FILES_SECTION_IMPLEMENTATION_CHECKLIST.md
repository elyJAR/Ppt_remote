# Files Section Implementation Checklist

Scope: add a Files section in the Android app that browses phone folders, and let the user open the currently selected folder directly on the PC over FTP without jumping back to the root.

## MVP

- [x] Add a Files section to the Android app UI.
- [x] Show phone storage roots first, then allow drilling into nested folders.
- [x] Add breadcrumb navigation for the current folder path.
- [x] Add an Open on PC action for each folder row.
- [x] Open the exact selected FTP folder in Windows Explorer, not the FTP root.
- [x] Keep the feature read-only for v1.

## Android App

- [x] Add folder and file models to the shared state layer.
- [x] Add current-path, loading, error, and breadcrumb state to the view model.
- [x] Implement folder listing for the selected path.
- [x] Implement navigation into subfolders and back to the parent folder.
- [x] Add a refresh action for the current folder.
- [ ] Persist the last browsed folder if that improves the UX.

## FTP Path Mapping

- [x] Confirm the FTP server home directory strategy.
- [x] Make sure nested folders map cleanly from the app to the FTP URL.
- [x] Handle spaces and special characters safely in FTP paths.
- [x] Verify that the selected folder opens directly on the PC.

## Desktop Bridge

- [x] Add or extend a bridge endpoint that opens a specific FTP folder path.
- [x] Validate and normalize folder paths before launching Explorer.
- [x] Reject invalid, missing, or unsafe paths.
- [x] Reuse the existing Explorer launch pattern already used for mobile FTP access.

## Permissions and Limits

- [ ] Confirm which Android storage permissions are needed for the target Android version.
- [ ] Decide whether scoped storage or SAF is required for newer devices.
- [ ] Add a fallback if some folders cannot be accessed directly.

## Tests

- [ ] Test browsing into nested folders.
- [x] Test opening a nested folder on the PC.
- [ ] Test folder names with spaces and special characters.
- [ ] Test invalid, missing, and denied paths.
- [ ] Test offline bridge behavior.

## Docs

- [ ] Update README with the Files section flow.
- [ ] Update FTP_FEATURE.md with the nested-folder behavior.
- [ ] Update ARCHITECTURE.md if the bridge/API changes.
- [ ] Add a short note about any storage limitations.
