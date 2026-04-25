"""Entry point for the PPT Remote Bridge background service.

This module is used by:
  - PyInstaller (EXE build) as the entry point (bridge_service.py -> main())
  - run_background.py (pythonw.exe background mode) -> imports main()
  - start_bridge.ps1 (visible console dev mode) -> uvicorn main:app directly

Features added here (on top of the FastAPI app defined in main.py):
  - Rotating file logging  (logs/bridge.log, 5 MB x 3 files)
  - Uvicorn runs in a daemon thread so the main thread can host the tray icon
  - System tray icon via pystray (gracefully skipped if not installed)
  - Balloon toast notification on successful start
  - Auto-restart of the HTTP server on unexpected crash
"""

from __future__ import annotations

import logging
import os
import sys
import threading
import time
from logging.handlers import RotatingFileHandler
from pathlib import Path

# ---------------------------------------------------------------------------
# Logging setup
# ---------------------------------------------------------------------------


def _setup_logging() -> None:
    """Configure root logger: rotating file + optional stderr."""
    log_dir = Path(__file__).parent / "logs"
    log_dir.mkdir(exist_ok=True)
    log_file = log_dir / "bridge.log"

    fmt = logging.Formatter(
        "%(asctime)s  %(levelname)-8s  %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    file_handler = RotatingFileHandler(
        log_file,
        maxBytes=5 * 1024 * 1024,  # 5 MB per file
        backupCount=3,
        encoding="utf-8",
    )
    file_handler.setFormatter(fmt)
    file_handler.setLevel(logging.INFO)

    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.addHandler(file_handler)

    # Only attach stderr when it is available (suppressed in windowless mode)
    if sys.stderr is not None:
        stderr_handler = logging.StreamHandler(sys.stderr)
        stderr_handler.setFormatter(fmt)
        stderr_handler.setLevel(logging.WARNING)
        root.addHandler(stderr_handler)


_logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# HTTP server thread (with auto-restart on crash)
# ---------------------------------------------------------------------------


def _run_server_with_restart(app, host: str, port: int) -> None:
    """Run uvicorn in a loop so it restarts automatically on unexpected exit."""
    import uvicorn

    while True:
        try:
            _logger.info("Starting uvicorn on %s:%d", host, port)
            uvicorn.run(
                app,
                host=host,
                port=port,
                log_level="warning",
                access_log=False,
                use_colors=False,
            )
            # Clean exit from uvicorn — do not restart
            _logger.info("Uvicorn exited cleanly")
            break
        except Exception as exc:
            _logger.error("HTTP server crashed: %s — restarting in 5 seconds…", exc)
            time.sleep(5)


# ---------------------------------------------------------------------------
# Main entry point
# ---------------------------------------------------------------------------


def main() -> None:
    """Start the bridge service.

    Execution order:
      1. Fix missing stdout/stderr (windowless EXE)
      2. Set up rotating file logging
      3. Import the FastAPI app (and resolved port)
      4. Start uvicorn in a daemon thread
      5. Attempt to launch the system tray icon (blocks main thread)
      6. If tray unavailable, keep main thread alive via thread.join()
    """

    # ------------------------------------------------------------------
    # 1. Fix streams for windowless EXE
    # ------------------------------------------------------------------
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w")

    # ------------------------------------------------------------------
    # 2. Logging
    # ------------------------------------------------------------------
    _setup_logging()
    _logger.info("=" * 60)
    _logger.info("PPT Remote Bridge starting up")

    # ------------------------------------------------------------------
    # 3. Import app + resolved port (env vars already read inside main.py)
    # ------------------------------------------------------------------
    from main import BRIDGE_PORT, app  # noqa: PLC0415

    bridge_url = f"http://localhost:{BRIDGE_PORT}"

    # ------------------------------------------------------------------
    # 4. HTTP server thread
    # ------------------------------------------------------------------
    server_thread = threading.Thread(
        target=_run_server_with_restart,
        args=(app, "0.0.0.0", BRIDGE_PORT),
        daemon=True,
        name="UvicornServer",
    )
    server_thread.start()
    _logger.info("HTTP server thread started — %s", bridge_url)

    # ------------------------------------------------------------------
    # 5. System tray icon (optional — graceful fallback if not installed)
    # ------------------------------------------------------------------
    try:
        from tray_icon import PYSTRAY_AVAILABLE, TrayIconManager  # noqa: PLC0415

        if PYSTRAY_AVAILABLE:

            def _on_quit() -> None:
                _logger.info("Quit requested via tray — shutting down")
                sys.exit(0)

            # Wire up quick controls — resolve active presentation on demand
            def _tray_action(command: str) -> None:
                """Run a bridge command from the tray menu (fire-and-forget thread)."""
                import threading as _threading
                def _run() -> None:
                    try:
                        from main import controller  # noqa: PLC0415
                        presentations = controller.list_presentations()
                        target = next(
                            (p for p in presentations if p.in_slideshow),
                            presentations[0] if presentations else None,
                        )
                        if target is None:
                            _logger.warning("Tray action '%s': no open presentations", command)
                            return
                        if command == "next":
                            controller.next_slide(target.id)
                        elif command == "previous":
                            controller.previous_slide(target.id)
                        elif command == "start":
                            controller.start_slideshow(target.id)
                        elif command == "stop":
                            controller.stop_slideshow(target.id)
                        _logger.info("Tray action '%s' executed on '%s'", command, target.name)
                    except Exception as exc:
                        _logger.warning("Tray action '%s' failed: %s", command, exc)
                _threading.Thread(target=_run, daemon=True).start()

            tray = TrayIconManager(
                bridge_url=bridge_url,
                on_quit=_on_quit,
                on_next=lambda: _tray_action("next"),
                on_previous=lambda: _tray_action("previous"),
                on_start_slideshow=lambda: _tray_action("start"),
                on_stop_slideshow=lambda: _tray_action("stop"),
            )

            # Give uvicorn a moment to bind the port before showing the toast
            time.sleep(1.5)
            tray.notify(
                "PPT Remote Bridge",
                f"Bridge is running at {bridge_url}",
            )
            _logger.info("Tray icon starting (blocks main thread until Quit)")
            tray.run()  # <-- blocks here until user clicks Quit

            # Show stop toast after tray exits
            tray.notify("PPT Remote Bridge", "Bridge has stopped")
            return

        _logger.info("pystray not available — skipping tray icon")

    except Exception as exc:
        _logger.warning("Could not start tray icon: %s", exc)

    # ------------------------------------------------------------------
    # 6. Fallback: keep main thread alive until the server thread exits
    # ------------------------------------------------------------------
    _logger.info("Running without tray icon — press Ctrl+C to stop")
    try:
        server_thread.join()
    except KeyboardInterrupt:
        _logger.info("KeyboardInterrupt — shutting down")


if __name__ == "__main__":
    main()
