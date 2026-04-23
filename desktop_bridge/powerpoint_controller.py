from __future__ import annotations

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


class PowerPointController:
    def _get_app(self):
        try:
            return win32com.client.GetActiveObject("PowerPoint.Application")
        except Exception as exc:  # pragma: no cover - COM exceptions vary
            raise PowerPointControllerError(
                "PowerPoint is not running. Open at least one presentation first."
            ) from exc

    def _slideshow_map(self, app: Any) -> dict[str, Any]:
        windows_by_fullname: dict[str, Any] = {}
        try:
            for i in range(1, app.SlideShowWindows.Count + 1):
                window = app.SlideShowWindows(i)
                fullname = str(window.Presentation.FullName)
                windows_by_fullname[fullname] = window
        except Exception:
            # If no slideshow windows exist, COM may throw depending on Office version.
            return {}
        return windows_by_fullname

    def list_presentations(self) -> list[PresentationInfo]:
        with com_context():
            app = self._get_app()
            slide_windows = self._slideshow_map(app)
            items: list[PresentationInfo] = []

            for i in range(1, app.Presentations.Count + 1):
                presentation = app.Presentations(i)
                full_name = str(presentation.FullName)
                name = str(presentation.Name)
                total_slides = int(presentation.Slides.Count)

                slide_window = slide_windows.get(full_name)
                in_show = slide_window is not None
                current_slide = None
                if in_show:
                    current_slide = int(slide_window.View.CurrentShowPosition)

                items.append(
                    PresentationInfo(
                        id=full_name,
                        name=name,
                        path=full_name,
                        in_slideshow=in_show,
                        current_slide=current_slide,
                        total_slides=total_slides,
                    )
                )

            return items

    def _find_presentation(self, app: Any, presentation_id: str):
        for i in range(1, app.Presentations.Count + 1):
            presentation = app.Presentations(i)
            if str(presentation.FullName) == presentation_id:
                return presentation
        raise PowerPointControllerError("Presentation not found among open files.")

    def _get_slideshow_window(self, app: Any, presentation_id: str):
        return self._slideshow_map(app).get(presentation_id)

    def start_slideshow(self, presentation_id: str) -> None:
        with com_context():
            app = self._get_app()
            presentation = self._find_presentation(app, presentation_id)
            presentation.SlideShowSettings.Run()

    def stop_slideshow(self, presentation_id: str) -> None:
        with com_context():
            app = self._get_app()
            window = self._get_slideshow_window(app, presentation_id)
            if window is None:
                raise PowerPointControllerError(
                    "Presentation is not currently in slideshow mode."
                )
            window.View.Exit()

    def next_slide(self, presentation_id: str) -> None:
        with com_context():
            app = self._get_app()
            window = self._get_slideshow_window(app, presentation_id)
            if window is None:
                presentation = self._find_presentation(app, presentation_id)
                presentation.SlideShowSettings.Run()
                window = self._get_slideshow_window(app, presentation_id)

            if window is None:
                raise PowerPointControllerError("Could not enter slideshow mode.")

            window.View.Next()

    def previous_slide(self, presentation_id: str) -> None:
        with com_context():
            app = self._get_app()
            window = self._get_slideshow_window(app, presentation_id)
            if window is None:
                presentation = self._find_presentation(app, presentation_id)
                presentation.SlideShowSettings.Run()
                window = self._get_slideshow_window(app, presentation_id)

            if window is None:
                raise PowerPointControllerError("Could not enter slideshow mode.")

            window.View.Previous()
