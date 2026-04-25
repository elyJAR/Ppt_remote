"""PPT Remote Bridge — Windows Service implementation.

Installs and runs the bridge as a proper Windows Service using pywin32.
This is more robust than a Scheduled Task: the SCM (Service Control Manager)
handles start/stop/restart, and the service survives user logoff.

Usage
-----
Install (run once as Administrator):
    python windows_service.py install

Start:
    python windows_service.py start
    -- or --
    net start PptRemoteBridge

Stop:
    python windows_service.py stop
    -- or --
    net stop PptRemoteBridge

Remove:
    python windows_service.py remove

Debug (runs in console, Ctrl+C to stop):
    python windows_service.py debug

Requirements
------------
    pip install pywin32
    (already in requirements.txt as pywin32>=311,<312)

Notes
-----
- The service runs as LocalSystem by default.  To run as a specific user,
  change _svc_username_ / _svc_password_ below or use the Services MMC snap-in.
- Logs are written to desktop_bridge/logs/bridge.log (same as background mode).
- The tray icon is NOT shown when running as a service (no interactive desktop).
"""

from __future__ import annotations

import logging
import os
import sys
import threading
import time
from pathlib import Path

# Ensure the desktop_bridge directory is on sys.path so bare imports work
_HERE = Path(__file__).parent.resolve()
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

import servicemanager  # pyright: ignore[reportMissingImports]
import win32event      # pyright: ignore[reportMissingImports]
import win32service    # pyright: ignore[reportMissingImports]
import win32serviceutil  # pyright: ignore[reportMissingImports]


# ---------------------------------------------------------------------------
# Service class
# ---------------------------------------------------------------------------

class PptRemoteBridgeService(win32serviceutil.ServiceFramework):
    _svc_name_        = "PptRemoteBridge"
    _svc_display_name_ = "PPT Remote Bridge"
    _svc_description_  = (
        "Exposes a local HTTP API so the PPT Remote Android app can control "
        "PowerPoint presentations over Wi-Fi."
    )

    def __init__(self, args):
        win32serviceutil.ServiceFramework.__init__(self, args)
        self._stop_event = win32event.CreateEvent(None, 0, 0, None)
        self._server_thread: threading.Thread | None = None

    # ------------------------------------------------------------------
    # SCM callbacks
    # ------------------------------------------------------------------

    def SvcStop(self):
        """Called by SCM when the service is asked to stop."""
        self.ReportServiceStatus(win32service.SERVICE_STOP_PENDING)
        win32event.SetEvent(self._stop_event)
        logging.getLogger(__name__).info("Stop signal received")

    def SvcDoRun(self):
        """Main service body — runs until SvcStop() is called."""
        servicemanager.LogMsg(
            servicemanager.EVENTLOG_INFORMATION_TYPE,
            servicemanager.PYS_SERVICE_STARTED,
            (self._svc_name_, ""),
        )
        self._run()
        servicemanager.LogMsg(
            servicemanager.EVENTLOG_INFORMATION_TYPE,
            servicemanager.PYS_SERVICE_STOPPED,
            (self._svc_name_, ""),
        )

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _run(self):
        _setup_logging()
        logger = logging.getLogger(__name__)
        logger.info("=" * 60)
        logger.info("PPT Remote Bridge Windows Service starting")

        from main import BRIDGE_PORT, app  # noqa: PLC0415

        bridge_url = f"http://localhost:{BRIDGE_PORT}"
        logger.info("Bridge URL: %s", bridge_url)

        # Start uvicorn in a daemon thread
        self._server_thread = threading.Thread(
            target=_run_server_with_restart,
            args=(app, "0.0.0.0", BRIDGE_PORT),
            daemon=True,
            name="UvicornServer",
        )
        self._server_thread.start()
        logger.info("HTTP server thread started")

        # Block until SCM sends a stop signal
        win32event.WaitForSingleObject(self._stop_event, win32event.INFINITE)
        logger.info("Service stopping")


# ---------------------------------------------------------------------------
# Helpers (shared with bridge_service.py logic)
# ---------------------------------------------------------------------------

def _setup_logging() -> None:
    from logging.handlers import RotatingFileHandler

    log_dir = _HERE / "logs"
    log_dir.mkdir(exist_ok=True)
    log_file = log_dir / "bridge.log"

    fmt = logging.Formatter(
        "%(asctime)s  %(levelname)-8s  %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    fh = RotatingFileHandler(
        log_file, maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8"
    )
    fh.setFormatter(fmt)
    fh.setLevel(logging.INFO)

    root = logging.getLogger()
    root.setLevel(logging.INFO)
    if not root.handlers:
        root.addHandler(fh)


def _run_server_with_restart(app, host: str, port: int) -> None:
    import uvicorn

    logger = logging.getLogger(__name__)
    while True:
        try:
            logger.info("Starting uvicorn on %s:%d", host, port)
            uvicorn.run(
                app,
                host=host,
                port=port,
                log_level="warning",
                access_log=False,
                use_colors=False,
            )
            logger.info("Uvicorn exited cleanly")
            break
        except Exception as exc:
            logger.error("HTTP server crashed: %s — restarting in 5 s", exc)
            time.sleep(5)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    if len(sys.argv) == 1:
        # Called by SCM with no arguments — hand off to servicemanager
        servicemanager.Initialize()
        servicemanager.PrepareToHostSingle(PptRemoteBridgeService)
        servicemanager.StartServiceCtrlDispatcher()
    else:
        win32serviceutil.HandleCommandLine(PptRemoteBridgeService)
