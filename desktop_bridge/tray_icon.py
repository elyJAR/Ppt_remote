"""System tray icon manager for PPT Remote Bridge.

Requires: pystray >= 0.19  and  Pillow >= 10
If either is missing the module still imports but PYSTRAY_AVAILABLE will be
False, and all public methods become no-ops so the bridge starts fine without
a tray icon.
"""

from __future__ import annotations

import logging
from typing import Callable

logger = logging.getLogger(__name__)

try:
    import pystray
    from PIL import Image, ImageDraw

    PYSTRAY_AVAILABLE = True
except ImportError:
    PYSTRAY_AVAILABLE = False
    logger.info("pystray / Pillow not installed — tray icon disabled")


def _make_icon_image(running: bool = True) -> "Image.Image":
    """Return a 64x64 RGBA icon generated programmatically.

    Layout:
      - Dark navy circle as background
      - White 'P' letterform (block letter, stands for PowerPoint)
      - Small status dot: green when running, red when stopped
    """
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Background circle — dark navy
    draw.ellipse([2, 2, 62, 62], fill=(28, 30, 50, 235))

    # 'P' letterform — vertical stem
    draw.rectangle([15, 13, 24, 51], fill=(255, 255, 255, 245))

    # 'P' letterform — top bowl (filled arc via two ellipses)
    draw.ellipse([20, 13, 44, 37], fill=(255, 255, 255, 245))  # outer bowl
    draw.ellipse([24, 19, 40, 33], fill=(28, 30, 50, 235))  # inner cutout

    # Status indicator dot
    dot_color = (0, 210, 90, 255) if running else (220, 55, 55, 255)
    draw.ellipse([40, 40, 60, 60], fill=dot_color)

    return img


class TrayIconManager:
    """Manages the Windows system tray icon for the bridge service.

    Example usage::

        def on_quit():
            sys.exit(0)

        tray = TrayIconManager(
            bridge_url="http://192.168.1.10:8787",
            on_quit=on_quit,
        )
        # Show a start-up toast (call before run())
        tray.notify("PPT Remote Bridge", "Bridge is running")
        # Blocks the calling thread until the user clicks Quit
        tray.run()
    """

    def __init__(self, bridge_url: str, on_quit: Callable[[], None]) -> None:
        self._bridge_url = bridge_url
        self._on_quit = on_quit
        self._icon: "pystray.Icon | None" = None

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def run(self) -> None:
        """Start the tray icon event loop.  Blocks until Quit is selected."""
        if not PYSTRAY_AVAILABLE:
            logger.warning("TrayIconManager.run() called but pystray is unavailable")
            return

        menu = pystray.Menu(
            pystray.MenuItem("PPT Remote Bridge", None, enabled=False),
            pystray.MenuItem(f"  {self._bridge_url}", None, enabled=False),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quit", self._handle_quit),
        )

        img = _make_icon_image(running=True)
        self._icon = pystray.Icon(
            name="PptRemoteBridge",
            icon=img,
            title="PPT Remote Bridge — running",
            menu=menu,
        )

        logger.info("Starting tray icon event loop")
        self._icon.run()

    def notify(self, title: str, message: str) -> None:
        """Show a balloon / toast notification.

        This is best-effort: silently ignored if the tray icon is not yet
        running or if the OS suppresses balloon notifications.
        """
        if self._icon is not None and PYSTRAY_AVAILABLE:
            try:
                self._icon.notify(message, title)
            except Exception as exc:
                logger.debug("Tray notify failed: %s", exc)

    def stop(self) -> None:
        """Stop the tray icon from an external caller (e.g. on server shutdown)."""
        if self._icon is not None:
            try:
                self._icon.stop()
            except Exception as exc:
                logger.debug("Tray stop failed: %s", exc)

    def set_running(self, running: bool) -> None:
        """Update the status dot colour on the tray icon (green / red)."""
        if self._icon is None or not PYSTRAY_AVAILABLE:
            return
        try:
            self._icon.icon = _make_icon_image(running=running)
            self._icon.title = (
                "PPT Remote Bridge — running"
                if running
                else "PPT Remote Bridge — stopped"
            )
        except Exception as exc:
            logger.debug("Tray icon update failed: %s", exc)

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _handle_quit(self, icon: "pystray.Icon", item: "pystray.MenuItem") -> None:
        logger.info("Quit selected from tray menu")
        icon.stop()
        self._on_quit()
