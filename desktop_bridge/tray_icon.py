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
    """Return a 64x64 RGBA icon generated programmatically."""
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Background circle — dark navy
    draw.ellipse([2, 2, 62, 62], fill=(28, 30, 50, 235))

    # 'P' letterform — vertical stem
    draw.rectangle([15, 13, 24, 51], fill=(255, 255, 255, 245))

    # 'P' letterform — top bowl
    draw.ellipse([20, 13, 44, 37], fill=(255, 255, 255, 245))
    draw.ellipse([24, 19, 40, 33], fill=(28, 30, 50, 235))

    # Status indicator dot
    dot_color = (0, 210, 90, 255) if running else (220, 55, 55, 255)
    draw.ellipse([40, 40, 60, 60], fill=dot_color)

    return img


class TrayIconManager:
    """Manages the Windows system tray icon for the bridge service."""

    def __init__(
        self,
        bridge_url: str,
        on_quit: Callable[[], None],
        on_next: Callable[[], None] | None = None,
        on_previous: Callable[[], None] | None = None,
        on_start_slideshow: Callable[[], None] | None = None,
        on_stop_slideshow: Callable[[], None] | None = None,
    ) -> None:
        self._bridge_url = bridge_url
        self._on_quit = on_quit
        self._on_next = on_next
        self._on_previous = on_previous
        self._on_start_slideshow = on_start_slideshow
        self._on_stop_slideshow = on_stop_slideshow
        self._icon: "pystray.Icon | None" = None

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def run(self) -> None:
        """Start the tray icon event loop. Blocks until Quit is selected."""
        if not PYSTRAY_AVAILABLE:
            logger.warning("TrayIconManager.run() called but pystray is unavailable")
            return

        menu_items = self._build_menu_items()
        img = _make_icon_image(running=True)
        self._icon = pystray.Icon(
            name="PptRemoteBridge",
            icon=img,
            title="PPT Remote Bridge — running",
            menu=pystray.Menu(*menu_items),
        )

        logger.info("Starting tray icon event loop")
        self._icon.run()

    def notify(self, title: str, message: str) -> None:
        """Show a balloon / toast notification."""
        if self._icon is not None and PYSTRAY_AVAILABLE:
            try:
                self._icon.notify(message, title)
            except Exception as exc:
                logger.debug("Tray notify failed: %s", exc)

    def stop(self) -> None:
        """Stop the tray icon from an external caller."""
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

    def set_bridge_url(self, bridge_url: str) -> None:
        """Update the bridge URL displayed in the tray menu."""
        if self._bridge_url == bridge_url:
            # Still update the menu because client_ip might have changed
            self.update_menu()
            return

        self._bridge_url = bridge_url
        logger.info("Updating tray menu with new bridge URL: %s", bridge_url)
        self.update_menu()

    def update_menu(self) -> None:
        """Force a rebuild and refresh of the system tray menu."""
        if self._icon is None or not PYSTRAY_AVAILABLE:
            return
        try:
            menu_items = self._build_menu_items()
            self._icon.menu = pystray.Menu(*menu_items)
        except Exception as exc:
            logger.debug("Tray menu update failed: %s", exc)

    def _build_menu_items(self) -> list["pystray.MenuItem"]:
        """Construct the list of menu items based on current state."""
        import main
        bridge_name = main.get_bridge_name()

        menu_items = [
            pystray.MenuItem(f"Bridge: {bridge_name}", None, enabled=False),
            pystray.MenuItem(f"  {self._bridge_url}", None, enabled=False),
            pystray.Menu.SEPARATOR,
        ]

        # Quick controls — only shown when callbacks are provided
        if any([self._on_previous, self._on_next, self._on_start_slideshow, self._on_stop_slideshow]):
            if self._on_previous:
                menu_items.append(pystray.MenuItem("⏮  Previous Slide", self._handle_previous))
            if self._on_next:
                menu_items.append(pystray.MenuItem("⏭  Next Slide", self._handle_next))
            if self._on_start_slideshow:
                menu_items.append(pystray.MenuItem("▶  Start Slideshow", self._handle_start_slideshow))
            if self._on_stop_slideshow:
                menu_items.append(pystray.MenuItem("⏹  Stop Slideshow", self._handle_stop_slideshow))
            menu_items.append(pystray.Menu.SEPARATOR)

        # FTP Feature - Multi-Client Support
        active_clients = main.client_registry.get_active_clients()
        if active_clients:
            client_subitems = []
            for client in active_clients:
                # Capture client in a closure for the handler
                def _make_handler(c=client):
                    return lambda i, m: main.open_ftp_explorer(c.ip_address)
                
                client_subitems.append(
                    pystray.MenuItem(f"📁 {client.device_name} ({client.ip_address})", _make_handler())
                )
            
            menu_items.append(pystray.MenuItem("Open Mobile Files", pystray.Menu(*client_subitems)))
            menu_items.append(pystray.MenuItem("🔄 Refresh Client List", lambda i, m: self.update_menu()))
            menu_items.append(pystray.Menu.SEPARATOR)
        else:
            # Show a placeholder to confirm the bridge is watching
            menu_items.append(pystray.MenuItem("⌛ Waiting for Mobile...", lambda i, m: self.update_menu(), enabled=True))
            menu_items.append(pystray.Menu.SEPARATOR)

        menu_items.append(pystray.MenuItem("Quit", self._handle_quit))
        return menu_items

    def _handle_open_ftp(self, icon: "pystray.Icon", item: "pystray.MenuItem") -> None:
        import main
        main.open_ftp_explorer()

    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # Internal handlers
    # ------------------------------------------------------------------

    def _handle_quit(self, icon: "pystray.Icon", item: "pystray.MenuItem") -> None:
        logger.info("Quit selected from tray menu")
        icon.stop()
        self._on_quit()

    def _handle_next(self, icon: "pystray.Icon", item: "pystray.MenuItem") -> None:
        logger.info("Next slide selected from tray menu")
        if self._on_next:
            try:
                self._on_next()
            except Exception as exc:
                logger.warning("Tray next slide failed: %s", exc)

    def _handle_previous(self, icon: "pystray.Icon", item: "pystray.MenuItem") -> None:
        logger.info("Previous slide selected from tray menu")
        if self._on_previous:
            try:
                self._on_previous()
            except Exception as exc:
                logger.warning("Tray previous slide failed: %s", exc)

    def _handle_start_slideshow(self, icon: "pystray.Icon", item: "pystray.MenuItem") -> None:
        logger.info("Start slideshow selected from tray menu")
        if self._on_start_slideshow:
            try:
                self._on_start_slideshow()
            except Exception as exc:
                logger.warning("Tray start slideshow failed: %s", exc)

    def _handle_stop_slideshow(self, icon: "pystray.Icon", item: "pystray.MenuItem") -> None:
        logger.info("Stop slideshow selected from tray menu")
        if self._on_stop_slideshow:
            try:
                self._on_stop_slideshow()
            except Exception as exc:
                logger.warning("Tray stop slideshow failed: %s", exc)
