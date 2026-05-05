from __future__ import annotations

from collections import OrderedDict
import logging
import os
import pathlib
import threading as _threading
import time
from contextlib import contextmanager
from dataclasses import dataclass
from functools import wraps
from typing import Any

import pythoncom
import win32com.client

_logger = logging.getLogger(__name__)


class PowerPointControllerError(Exception):
    """Raised when PowerPoint automation fails."""


def _com_retry(fn):
    """Decorator: retry once on COMError in case PowerPoint briefly hiccupped."""
    @wraps(fn)
    def wrapper(*args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except PowerPointControllerError:
            raise  # our own errors — don't retry
        except Exception as exc:
            _logger.warning("COM call failed (%s), retrying once: %s", fn.__name__, exc)
            try:
                return fn(*args, **kwargs)
            except PowerPointControllerError:
                raise
            except Exception as exc2:
                raise PowerPointControllerError(
                    f"PowerPoint COM error in {fn.__name__}: {exc2}. "
                    "PowerPoint may have crashed — please reopen it."
                ) from exc2
    return wrapper


@dataclass
class PresentationInfo:
    id: str
    name: str
    path: str
    in_slideshow: bool
    current_slide: int | None
    total_slides: int


import queue as _queue
import threading as _threading


class _ComWorker:
    """Single dedicated STA thread that handles ALL COM operations.

    PowerPoint registers itself in the Windows Running Object Table (ROT)
    from its own STA thread.  GetActiveObject only succeeds when called from
    an STA that pumps Windows messages — which uvicorn/FastAPI threadpool
    threads do NOT do.  This worker maintains one permanent STA thread that
    calls pythoncom.PumpWaitingMessages() in a tight loop, so COM proxies
    can be created and Office calls can flow correctly.
    """

    def __init__(self) -> None:
        self._q: _queue.Queue = _queue.Queue()
        t = _threading.Thread(target=self._loop, daemon=True, name="com-worker")
        t.start()

    def _loop(self) -> None:
        pythoncom.CoInitialize()  # STA — required for Office/GetActiveObject
        try:
            while True:
                # Pump any pending COM/Windows messages so cross-apartment
                # calls from PowerPoint back to this STA can complete.
                pythoncom.PumpWaitingMessages()
                try:
                    fn, args, kwargs, result_q = self._q.get(timeout=0.02)
                except _queue.Empty:
                    continue
                try:
                    result_q.put(("ok", fn(*args, **kwargs)))
                except Exception as exc:
                    result_q.put(("err", exc))
        finally:
            pythoncom.CoUninitialize()

    def call(self, fn, *args, **kwargs):
        """Execute fn(*args, **kwargs) on the COM STA thread and return its result."""
        result_q: _queue.SimpleQueue = _queue.SimpleQueue()
        self._q.put((fn, args, kwargs, result_q))
        kind, value = result_q.get(timeout=30)
        if kind == "err":
            raise value
        return value


# Module-level singleton — created once when the module is imported.
_com_worker = _ComWorker()


def _on_com_thread(fn):
    """Decorator: run the wrapped method on the COM STA worker thread.

    Combines dispatch-to-STA-thread with one automatic retry so each public
    controller method is both thread-safe and resilient to transient COM hiccups.
    """
    @wraps(fn)
    def wrapper(*args, **kwargs):
        def _execute():
            try:
                return fn(*args, **kwargs)
            except PowerPointControllerError:
                raise  # our own errors — don't retry
            except Exception as exc:
                _logger.warning("COM call failed (%s), retrying once: %s", fn.__name__, exc)
                try:
                    return fn(*args, **kwargs)
                except PowerPointControllerError:
                    raise
                except Exception as exc2:
                    raise PowerPointControllerError(
                        f"PowerPoint COM error in {fn.__name__}: {exc2}. "
                        "PowerPoint may have crashed — please reopen it."
                    ) from exc2
        return _com_worker.call(_execute)
    return wrapper


@contextmanager
def com_context():
    """No-op context manager kept for backward compatibility."""
    yield



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
    def __init__(self) -> None:
        # Persistent disk cache setup
        self._cache_dir = pathlib.Path(os.getenv("APPDATA", "")) / "PptRemoteBridge" / "thumbnails"
        self._cache_dir.mkdir(parents=True, exist_ok=True)

        # In-memory LRU cache for speed
        self._thumbnail_cache: OrderedDict[tuple[str, int, int, float], bytes] = OrderedDict()
        self._thumbnail_warmup_inflight: set[tuple[str, int, int]] = set()
        self._thumbnail_warmup_complete: set[tuple[str, int, int]] = set()
        self._thumbnail_warmup_lock = _threading.Lock()
        self._thumbnail_warmup_width = 720

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _get_app(self) -> Any:
        """Get the running PowerPoint COM object, reconnecting if it crashed."""
        try:
            app = win32com.client.GetActiveObject("PowerPoint.Application")
            # Probe the app to confirm it's still responsive
            _ = app.Presentations.Count
            return app
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

    @_on_com_thread
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
            warmup_targets: list[tuple[str, int]] = []

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

                if total_slides > 0:
                    warmup_targets.append((full_name, total_slides))

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

            for presentation_id, total_slides in warmup_targets:
                self._schedule_thumbnail_warmup(
                    presentation_id,
                    total_slides,
                    width=self._thumbnail_warmup_width,
                )

            return items

    def _try_enable_editing(self, pres: Any) -> bool:
        """Attempt to enable editing on a read-only / cloud presentation.

        OneDrive/SharePoint files open as read-only until the user (or COM)
        calls EnableEditing().  Returns True if the file is now editable.
        """
        import time
        try:
            if not bool(pres.ReadOnly):
                return True  # already editable
            pres.EnableEditing()
            # Poll until editable or timeout (OneDrive sync can take several seconds)
            for _ in range(10):
                time.sleep(0.5)
                try:
                    if not bool(pres.ReadOnly):
                        return True
                except Exception:
                    pass
            return not bool(pres.ReadOnly)
        except Exception:
            return False

    def _wait_for_slideshow_window(self, app: Any, presentation_id: str, timeout: float = 5.0) -> Any | None:
        """Poll until the slideshow window appears or timeout expires."""
        import time
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            window = self._find_slideshow_window(app, presentation_id)
            if window is not None:
                return window
            time.sleep(0.2)
        return None

    @_on_com_thread
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
            # Wait for the slideshow window to actually open (important for OneDrive files)
            self._wait_for_slideshow_window(app, presentation_id, timeout=6.0)

    @_on_com_thread
    def stop_slideshow(self, presentation_id: str) -> None:
        with com_context():
            app = self._get_app()
            window = self._find_slideshow_window(app, presentation_id)
            if window is None:
                raise PowerPointControllerError(
                    "Presentation is not currently in slideshow mode."
                )
            window.View.Exit()

    @_on_com_thread
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
                        enabled = self._try_enable_editing(pres)
                        if not enabled:
                            raise PowerPointControllerError(
                                "This presentation is read-only (OneDrive/cloud file). "
                                "Click 'Enable Editing' in PowerPoint, then try again."
                            )
                except PowerPointControllerError:
                    raise
                except Exception:
                    pass
                try:
                    pres.SlideShowSettings.Run()
                except Exception as exc:
                    raise PowerPointControllerError(
                        f"Could not auto-start slideshow: {exc}"
                    ) from exc
                window = self._wait_for_slideshow_window(app, presentation_id)

            if window is None:
                raise PowerPointControllerError(
                    "Could not enter slideshow mode. "
                    "If the file was downloaded from cloud, click 'Enable Editing' in PowerPoint first."
                )
            window.View.Next()

    @_on_com_thread
    def previous_slide(self, presentation_id: str) -> None:
        with com_context():
            app = self._get_app()
            window = self._find_slideshow_window(app, presentation_id)
            if window is None:
                pres = self._find_presentation(app, presentation_id)
                # Enable editing for OneDrive/cloud files
                try:
                    if bool(pres.ReadOnly):
                        enabled = self._try_enable_editing(pres)
                        if not enabled:
                            raise PowerPointControllerError(
                                "This presentation is read-only (OneDrive/cloud file). "
                                "Click 'Enable Editing' in PowerPoint, then try again."
                            )
                except PowerPointControllerError:
                    raise
                except Exception:
                    pass
                try:
                    pres.SlideShowSettings.Run()
                except Exception as exc:
                    raise PowerPointControllerError(
                        f"Could not auto-start slideshow: {exc}"
                    ) from exc
                window = self._wait_for_slideshow_window(app, presentation_id)

            if window is None:
                raise PowerPointControllerError(
                    "Could not enter slideshow mode. "
                    "If the file was downloaded from cloud, click 'Enable Editing' in PowerPoint first."
                )
            window.View.Previous()

    def _schedule_thumbnail_warmup(self, presentation_id: str, total_slides: int, width: int = 720) -> None:
        cache_key = (presentation_id, total_slides, width)
        with self._thumbnail_warmup_lock:
            if cache_key in self._thumbnail_warmup_complete or cache_key in self._thumbnail_warmup_inflight:
                return
            self._thumbnail_warmup_inflight.add(cache_key)

        def _worker() -> None:
            try:
                self._warm_thumbnail_cache(presentation_id, total_slides, width)
            finally:
                with self._thumbnail_warmup_lock:
                    self._thumbnail_warmup_inflight.discard(cache_key)
                    self._thumbnail_warmup_complete.add(cache_key)

        _threading.Thread(target=_worker, daemon=True, name=f"thumb-warmup-{_basename(presentation_id)}").start()

    def _warm_thumbnail_cache(self, presentation_id: str, total_slides: int, width: int = 720) -> None:
        """Pre-render every slide once so later requests hit the in-memory cache."""
        for slide_index in range(1, total_slides + 1):
            try:
                self.get_slide_thumbnail(presentation_id, slide_index, width)
            except PowerPointControllerError:
                pass

    @_on_com_thread
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

    @_on_com_thread
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

    def _get_slide_thumbnail_impl(self, app: Any, presentation_id: str, slide_index: int, width: int = 320) -> bytes:
        import hashlib
        import os as _os

        pres = self._find_presentation(app, presentation_id)
        
        # Get last modified time to ensure cache freshness
        try:
            mtime = float(pres.LastSavedTime)
        except Exception:
            mtime = 0.0

        cache_key = (presentation_id, slide_index, width, mtime)
        
        # 1. Check in-memory cache
        cached = self._thumbnail_cache.get(cache_key)
        if cached is not None:
            self._thumbnail_cache.move_to_end(cache_key)
            return cached

        # 2. Check disk cache
        # Create a stable hash for the presentation path to use as a filename
        path_hash = hashlib.md5(presentation_id.encode("utf-8")).hexdigest()
        disk_filename = f"{path_hash}_{slide_index}_{width}_{int(mtime)}.png"
        disk_path = self._cache_dir / disk_filename
        
        if disk_path.exists():
            try:
                with open(disk_path, "rb") as f:
                    png_bytes = f.read()
                # Populate in-memory cache
                self._thumbnail_cache[cache_key] = png_bytes
                return png_bytes
            except Exception:
                pass # Fallback to export if disk read fails

        total = int(pres.Slides.Count)
        if slide_index < 1 or slide_index > total:
            raise PowerPointControllerError(
                f"Slide index {slide_index} is out of range (1–{total})."
            )

        slide = pres.Slides(slide_index)

        # Calculate height preserving the slide aspect ratio
        try:
            slide_width  = float(pres.PageSetup.SlideWidth)
            slide_height = float(pres.PageSetup.SlideHeight)
            height = int(width * slide_height / slide_width)
        except Exception:
            height = int(width * 9 / 16)  # fallback: 16:9

        # Export directly to disk cache
        tmp_path = str(disk_path) + ".tmp"
        try:
            # ppShapeFormatPNG = 2
            slide.Export(tmp_path, "PNG", width, height)
            
            # Read back for returning to caller
            with open(tmp_path, "rb") as f:
                png_bytes = f.read()
            
            # Atomic move to final disk path
            if disk_path.exists():
                _os.remove(disk_path)
            _os.rename(tmp_path, disk_path)

            # Update in-memory cache
            self._thumbnail_cache[cache_key] = png_bytes
            self._thumbnail_cache.move_to_end(cache_key)
            while len(self._thumbnail_cache) > 512:
                self._thumbnail_cache.popitem(last=False)
                
            return png_bytes
        except Exception as exc:
            if _os.path.exists(tmp_path):
                try: _os.remove(tmp_path)
                except: pass
            raise PowerPointControllerError(
                f"Could not export slide {slide_index} as PNG: {exc}"
            ) from exc

    @_on_com_thread
    def get_slide_thumbnail(self, presentation_id: str, slide_index: int, width: int = 320) -> bytes:
        """Export a single slide as a PNG and return the raw bytes.

        Uses PowerPoint's Export() COM method to render the slide at the
        requested pixel width (height is calculated to preserve aspect ratio).

        Args:
            presentation_id: FullName / path of the open presentation.
            slide_index:      1-based slide number.
            width:            Output image width in pixels (default 720).

        Returns:
            PNG image bytes.

        Raises:
            PowerPointControllerError: if the presentation or slide is not found,
                or if the export fails.
        """
        with com_context():
            app = self._get_app()
            return self._get_slide_thumbnail_impl(app, presentation_id, slide_index, width)

    @_on_com_thread
    def get_current_slide_thumbnail(self, presentation_id: str, width: int = 320) -> tuple[int, bytes]:
        """Export the currently displayed slideshow slide as PNG bytes.

        Returns:
            (slide_index, png_bytes) — slide_index is 1-based.

        Raises:
            PowerPointControllerError: if no slideshow is active.
        """
        with com_context():
            app = self._get_app()
            window = self._find_slideshow_window(app, presentation_id)
            if window is None:
                raise PowerPointControllerError(
                    "Presentation is not currently in slideshow mode."
                )
            slide_index = int(window.View.CurrentShowPosition)

        # Delegate to the internal undecorated method to avoid deadlocking the COM worker thread
        png_bytes = self._get_slide_thumbnail_impl(app, presentation_id, slide_index, width)
        return slide_index, png_bytes
