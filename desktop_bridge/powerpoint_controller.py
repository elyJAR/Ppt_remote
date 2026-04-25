from __future__ import annotations

import os
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Any

import pythoncom
import win32com.client


class PowerPointControllerError(Exception):
    """Raised when PowerPoint automation fails."""


@dataclass
class PresentationInfo:
    id: str
    name: str
    path: str
    in_slideshow: bool
    current_slide: int | None
    total_slides: int


@contextmanager
def com_context():
    pythoncom.CoInitialize()
    try:
        yield
    finally:
        pythoncom.CoUninitialize()


def _norm(path: str) -> str:
    """Normalise a path for comparison: lowercase, consistent slashes, no trailing slash.

    Also handles cloud URLs (https://...) by leaving them lowercase but not
    running os.path.normpath on them (which would mangle the slashes).
    """
    if path.lower().startswith(("http://", "https://")):
        return path.lower().rstrip("/")
    return os.path.normcase(os.path.normpath(path))


def _basename(path: str) -> str:
    """Return the filename portion of a path or URL."""
    # For URLs, take the last segment after the last slash
    return os.path.basename(path.replace("\\", "/").rstrip("/"))


def _paths_match(a: str, b: str) -> bool:
    """True if two presentation paths refer to the same file."""
    if a == b:
        return True
    if _norm(a) == _norm(b):
        return True
    # Last-resort: same filename (catches cloud-URL vs local-path mismatch)
    return _basename(a).lower() == _basename(b).lower()


class PowerPointController:
    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _get_app(self) -> Any:
        try:
            return win32com.client.GetActiveObject("PowerPoint.Application")
        except Exception as exc:
            raise PowerPointControllerError(
                "PowerPoint is not running. Open at least one presentation first."
            ) from exc

    def _slideshow_map(self, app: Any) -> dict[str, Any]:
        """Return a map of normalised FullName -> SlideShowWindow for every active slideshow."""
        windows: dict[str, Any] = {}
        try:
            for i in range(1, app.SlideShowWindows.Count + 1):
                window = app.SlideShowWindows(i)
                try:
                    fullname = str(window.Presentation.FullName)
                except Exception:
                    continue
                # Index by original, normalised, and basename so every lookup strategy works
                windows[fullname] = window
                windows[_norm(fullname)] = window
                windows[_basename(fullname).lower()] = window
        except Exception:
            return {}
        return windows

    def _find_slideshow_window(self, app: Any, presentation_id: str) -> Any | None:
        """Find the active slideshow window for presentation_id using flexible matching."""
        windows = self._slideshow_map(app)
        # Strategy 1: exact match
        if presentation_id in windows:
            return windows[presentation_id]
        # Strategy 2: normalised path match
        norm_id = _norm(presentation_id)
        if norm_id in windows:
            return windows[norm_id]
        # Strategy 3: basename match (cloud URL vs local path)
        base_id = _basename(presentation_id).lower()
        if base_id in windows:
            return windows[base_id]
        return None

    def _find_presentation(self, app: Any, presentation_id: str) -> Any:
        """Find an open Presentation by ID using flexible path matching."""
        # Strategy 1: exact FullName match
        for i in range(1, app.Presentations.Count + 1):
            pres = app.Presentations(i)
            try:
                if str(pres.FullName) == presentation_id:
                    return pres
            except Exception:
                continue

        # Strategy 2: normalised path match
        norm_id = _norm(presentation_id)
        for i in range(1, app.Presentations.Count + 1):
            pres = app.Presentations(i)
            try:
                if _norm(str(pres.FullName)) == norm_id:
                    return pres
            except Exception:
                continue

        # Strategy 3: basename match (cloud URL vs OneDrive local path)
        base_id = _basename(presentation_id).lower()
        for i in range(1, app.Presentations.Count + 1):
            pres = app.Presentations(i)
            try:
                if _basename(str(pres.FullName)).lower() == base_id:
                    return pres
            except Exception:
                continue

        raise PowerPointControllerError(
            f"Presentation not found among open files. "
            f"Make sure it is open in PowerPoint and not in Protected View."
        )

    def _check_protected_view(self, app: Any, presentation_id: str) -> bool:
        """Return True if the file is open in Protected View (cannot be controlled)."""
        try:
            base_id = _basename(presentation_id).lower()
            for i in range(1, app.ProtectedViewWindows.Count + 1):
                pv = app.ProtectedViewWindows(i)
                try:
                    pv_name = _basename(str(pv.Caption)).lower()
                    if pv_name == base_id or base_id in pv_name:
                        return True
                except Exception:
                    continue
        except Exception:
            pass
        return False

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def list_presentations(self) -> list[PresentationInfo]:
        with com_context():
            app = self._get_app()

            # Build slideshow map once; reuse for all presentations
            slideshow_windows = self._slideshow_map(app)

            # Helper: find window for a given FullName using flexible matching
            def _find_window_for(fullname: str) -> Any | None:
                if fullname in slideshow_windows:
                    return slideshow_windows[fullname]
                norm = _norm(fullname)
                if norm in slideshow_windows:
                    return slideshow_windows[norm]
                base = _basename(fullname).lower()
                if base in slideshow_windows:
                    return slideshow_windows[base]
                return None

            items: list[PresentationInfo] = []

            for i in range(1, app.Presentations.Count + 1):
                pres = app.Presentations(i)
                try:
                    full_name = str(pres.FullName)
                    name = str(pres.Name)
                    total_slides = int(pres.Slides.Count)
                except Exception:
                    continue

                window = _find_window_for(full_name)
                in_show = window is not None
                current_slide: int | None = None
                if in_show and window is not None:
                    try:
                        current_slide = int(window.View.CurrentShowPosition)
                    except Exception:
                        current_slide = None

                # Annotate read-only cloud files so the user knows
                try:
                    read_only = bool(pres.ReadOnly)
                except Exception:
                    read_only = False

                display_name = name
                if read_only and not in_show:
                    display_name = (
                        f"{name} [OneDrive — will auto-enable editing on Start]"
                    )

                items.append(
                    PresentationInfo(
                        id=full_name,
                        name=display_name,
                        path=full_name,
                        in_slideshow=in_show,
                        current_slide=current_slide,
                        total_slides=total_slides,
                    )
                )

            # Report files stuck in Protected View so users know why they appear uncontrollable
            try:
                for i in range(1, app.ProtectedViewWindows.Count + 1):
                    pv = app.ProtectedViewWindows(i)
                    try:
                        pv_name = str(pv.Caption)
                        items.append(
                            PresentationInfo(
                                id=f"__protected__{pv_name}",
                                name=f"{pv_name} [Protected View — click Enable Editing]",
                                path="",
                                in_slideshow=False,
                                current_slide=None,
                                total_slides=0,
                            )
                        )
                    except Exception:
                        continue
            except Exception:
                pass

            return items

    def _try_enable_editing(self, pres: Any) -> bool:
        """Attempt to enable editing on a read-only / cloud presentation.

        OneDrive/SharePoint files open as read-only until the user (or COM)
        calls EnableEditing().  Returns True if the file is now editable.
        """
        try:
            if not bool(pres.ReadOnly):
                return True  # already editable
            # PowerPoint exposes EnableEditing() on the Presentation object
            pres.EnableEditing()
            # Give PowerPoint a moment to sync with OneDrive
            import time
            time.sleep(1.0)
            return not bool(pres.ReadOnly)
        except Exception:
            return False

    def start_slideshow(self, presentation_id: str) -> None:
        if presentation_id.startswith("__protected__"):
            raise PowerPointControllerError(
                "This file is in Protected View. Click 'Enable Editing' in PowerPoint first."
            )
        with com_context():
            app = self._get_app()

            # Check Protected View before wasting a COM lookup
            if self._check_protected_view(app, presentation_id):
                raise PowerPointControllerError(
                    "This file is open in Protected View and cannot be controlled. "
                    "Click 'Enable Editing' in PowerPoint, then try again."
                )

            pres = self._find_presentation(app, presentation_id)

            # For OneDrive/cloud files: try to enable editing automatically
            try:
                if bool(pres.ReadOnly):
                    enabled = self._try_enable_editing(pres)
                    if not enabled:
                        raise PowerPointControllerError(
                            "This presentation is read-only (OneDrive/cloud file). "
                            "In PowerPoint, click 'Enable Editing' on the yellow bar, then try again."
                        )
            except PowerPointControllerError:
                raise
            except Exception:
                pass  # ReadOnly check failed; attempt slideshow anyway

            try:
                pres.SlideShowSettings.Run()
            except Exception as exc:
                raise PowerPointControllerError(
                    f"Could not start slideshow: {exc}. "
                    f"If the file was downloaded from cloud, click 'Enable Editing' in PowerPoint first."
                ) from exc

    def stop_slideshow(self, presentation_id: str) -> None:
        with com_context():
            app = self._get_app()
            window = self._find_slideshow_window(app, presentation_id)
            if window is None:
                raise PowerPointControllerError(
                    "Presentation is not currently in slideshow mode."
                )
            window.View.Exit()

    def next_slide(self, presentation_id: str) -> None:
        with com_context():
            app = self._get_app()
            window = self._find_slideshow_window(app, presentation_id)
            if window is None:
                # Auto-start slideshow if not already running
                pres = self._find_presentation(app, presentation_id)
                # Enable editing for OneDrive/cloud files
                try:
                    if bool(pres.ReadOnly):
                        self._try_enable_editing(pres)
                except Exception:
                    pass
                try:
                    pres.SlideShowSettings.Run()
                except Exception as exc:
                    raise PowerPointControllerError(
                        f"Could not auto-start slideshow: {exc}"
                    ) from exc
                window = self._find_slideshow_window(app, presentation_id)

            if window is None:
                raise PowerPointControllerError(
                    "Could not enter slideshow mode. "
                    "If the file was downloaded from cloud, click 'Enable Editing' in PowerPoint first."
                )
            window.View.Next()

    def previous_slide(self, presentation_id: str) -> None:
        with com_context():
            app = self._get_app()
            window = self._find_slideshow_window(app, presentation_id)
            if window is None:
                pres = self._find_presentation(app, presentation_id)
                # Enable editing for OneDrive/cloud files
                try:
                    if bool(pres.ReadOnly):
                        self._try_enable_editing(pres)
                except Exception:
                    pass
                try:
                    pres.SlideShowSettings.Run()
                except Exception as exc:
                    raise PowerPointControllerError(
                        f"Could not auto-start slideshow: {exc}"
                    ) from exc
                window = self._find_slideshow_window(app, presentation_id)

            if window is None:
                raise PowerPointControllerError(
                    "Could not enter slideshow mode. "
                    "If the file was downloaded from cloud, click 'Enable Editing' in PowerPoint first."
                )
            window.View.Previous()

    def get_all_speaker_notes(self, presentation_id: str) -> list[str]:
        """Return speaker-notes text for every slide (empty string if none)."""
        with com_context():
            app = self._get_app()
            pres = self._find_presentation(app, presentation_id)
            notes: list[str] = []
            for i in range(1, pres.Slides.Count + 1):
                slide = pres.Slides(i)
                try:
                    text = str(
                        slide.NotesPage.Shapes.Placeholders(2).TextFrame.TextRange.Text
                    )
                    notes.append(text.strip())
                except Exception:
                    notes.append("")
            return notes

    def get_current_slide_notes(self, presentation_id: str) -> tuple[int, str]:
        """Return (1-based slide index, notes text) for the active slideshow slide."""
        with com_context():
            app = self._get_app()
            window = self._find_slideshow_window(app, presentation_id)
            if window is None:
                raise PowerPointControllerError(
                    "Presentation is not currently in slideshow mode."
                )
            slide_index = int(window.View.CurrentShowPosition)
            pres = self._find_presentation(app, presentation_id)
            slide = pres.Slides(slide_index)
            try:
                text = str(
                    slide.NotesPage.Shapes.Placeholders(2).TextFrame.TextRange.Text
                )
            except Exception:
                text = ""
            return slide_index, text.strip()
