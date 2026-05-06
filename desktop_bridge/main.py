from __future__ import annotations

import json
import logging
import logging.handlers
import os
import pathlib
import socket
import threading
import urllib.parse
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import APIKeyHeader
from network_detector import (  # pyright: ignore[reportImplicitRelativeImport]
    NetworkType,
    get_network_type,
)
from powerpoint_controller import (  # pyright: ignore[reportImplicitRelativeImport]
    PowerPointController,
    PowerPointControllerError,
)
from pydantic import BaseModel
from slowapi import (  # pyright: ignore[reportMissingImports]
    Limiter,
    _rate_limit_exceeded_handler,
)
from slowapi.errors import RateLimitExceeded  # pyright: ignore[reportMissingImports]
from slowapi.util import get_remote_address  # pyright: ignore[reportMissingImports]

# ---------------------------------------------------------------------------
# File logging — written to %APPDATA%\PptRemoteBridge\bridge.log
# ---------------------------------------------------------------------------
def _setup_logging() -> None:
    log_dir = pathlib.Path(os.getenv("APPDATA", "")) / "PptRemoteBridge"
    log_dir.mkdir(parents=True, exist_ok=True)
    log_file = log_dir / "bridge.log"
    handler = logging.handlers.RotatingFileHandler(
        log_file, maxBytes=1_000_000, backupCount=2, encoding="utf-8"
    )
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)-8s %(name)s: %(message)s"))
    root = logging.getLogger()
    root.setLevel(logging.DEBUG)
    root.addHandler(handler)

_setup_logging()
_logger = logging.getLogger(__name__)

# Shared state for the bridge (tracked across requests)
STATE = {
    "last_client_ip": None
}

def open_ftp_explorer() -> bool:
    """Launch Windows Explorer pointed at the mobile device's FTP server."""
    client_ip = STATE.get("last_client_ip")
    if not client_ip:
        _logger.warning("open_ftp_explorer: No mobile client IP known.")
        return False

    import subprocess
    # Force the trailing slash and ensure port 2121
    ftp_url = f"ftp://{client_ip}:2121/"
    _logger.info("Opening Android files in Explorer: %s", ftp_url)
    try:
        # Force Windows File Explorer by using explorer.exe explicitly.
        subprocess.Popen(["explorer.exe", ftp_url])
        return True
    except Exception as exc:
        _logger.error("Failed to open FTP explorer: %s", exc)
        return False

# ---------------------------------------------------------------------------
# Configuration (overridable via environment variables)
# ---------------------------------------------------------------------------
BRIDGE_PORT: int = int(os.getenv("PPT_BRIDGE_PORT", "8787"))
DISCOVERY_PORT: int = int(os.getenv("PPT_DISCOVERY_PORT", "8788"))
DISCOVERY_TOKEN: str = "PPT_REMOTE_DISCOVER"

# Request timeout in seconds — rejects stale / hung requests (0 = disabled)
REQUEST_TIMEOUT: int = int(os.getenv("PPT_REQUEST_TIMEOUT", "30"))

# ---------------------------------------------------------------------------
# CORS — restrict to LAN origins by default.
# Set PPT_CORS_ORIGINS=* to allow all origins (useful for development).
# Firewall note: open TCP 8787 (HTTP API) and UDP 8788 (discovery) on your
# Windows firewall for inbound connections from your local network only.
# ---------------------------------------------------------------------------
_cors_env = os.getenv("PPT_CORS_ORIGINS", "")
_CORS_ORIGINS: list[str] = (
    ["*"] if _cors_env.strip() == "*"
    else [o.strip() for o in _cors_env.split(",") if o.strip()]
) or [
    # Default: allow common LAN address ranges via regex-style allow_origin_regex
    # (FastAPI/Starlette supports regex in allow_origin_regex, not allow_origins)
    # We keep allow_origins=["*"] for LAN use but document the env var override.
    "*"
]

# ---------------------------------------------------------------------------
# Optional API key authentication
# ---------------------------------------------------------------------------
_API_KEY: str | None = os.getenv("PPT_API_KEY") or None  # None = open mode

_api_key_header = APIKeyHeader(name="X-Api-Key", auto_error=False)


async def verify_api_key(
    x_api_key: Annotated[str | None, Depends(_api_key_header)] = None,
) -> None:
    """Dependency: enforce API key when PPT_API_KEY env var is set."""
    if _API_KEY is None:
        return  # open mode — no key required
    if x_api_key != _API_KEY:
        raise HTTPException(
            status_code=401,
            detail="Invalid or missing API key. Set X-Api-Key header.",
        )


# ---------------------------------------------------------------------------
# DTOs
# ---------------------------------------------------------------------------
class PresentationDto(BaseModel):
    id: str
    name: str
    path: str
    in_slideshow: bool
    current_slide: int | None
    total_slides: int


class HealthDto(BaseModel):
    status: str
    network_type: str = "unknown"
    is_hotspot: bool = False


class NetworkStatusDto(BaseModel):
    network_type: str
    is_hotspot: bool
    warning: str | None = None


class SlideNotesDto(BaseModel):
    slide_index: int
    notes: str


# ---------------------------------------------------------------------------
# Discovery helpers
# ---------------------------------------------------------------------------
def _local_ip_for_peer(peer_ip: str) -> str:
    """Pick the local interface IP that routes back to the requesting device."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect((peer_ip, 1))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


class DiscoveryResponder:
    def __init__(self, discovery_port: int, bridge_port: int) -> None:
        self._discovery_port: int = discovery_port
        self._bridge_port: int = bridge_port
        self._stop_event: threading.Event = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=1.5)

    def _run(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("", self._discovery_port))
            sock.settimeout(1.0)

            while not self._stop_event.is_set():
                try:
                    data, addr = sock.recvfrom(1024)
                except socket.timeout:
                    continue
                except OSError:
                    break

                message = data.decode("utf-8", errors="ignore").strip()
                if message != DISCOVERY_TOKEN:
                    continue

                local_ip = _local_ip_for_peer(addr[0])
                payload = json.dumps(
                    {"bridge_url": f"http://{local_ip}:{self._bridge_port}"}
                )
                sock.sendto(payload.encode("utf-8"), addr)
        finally:
            sock.close()


discovery_responder = DiscoveryResponder(DISCOVERY_PORT, BRIDGE_PORT)


# ---------------------------------------------------------------------------
# App lifespan (replaces deprecated @app.on_event)
# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(_app: FastAPI):
    discovery_responder.start()
    yield
    discovery_responder.stop()


# ---------------------------------------------------------------------------
# FastAPI application
# ---------------------------------------------------------------------------
app = FastAPI(title="PowerPoint Bridge API", version="0.1.0", lifespan=lifespan)
controller = PowerPointController()

app.add_middleware(
    CORSMiddleware,
    allow_origins=_CORS_ORIGINS,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# Request timeout middleware — rejects stale / hung requests
# ---------------------------------------------------------------------------
if REQUEST_TIMEOUT > 0:
    import asyncio
    from starlette.middleware.base import BaseHTTPMiddleware
    from starlette.responses import JSONResponse

    class TimeoutMiddleware(BaseHTTPMiddleware):
        async def dispatch(self, request, call_next):
            try:
                return await asyncio.wait_for(
                    call_next(request), timeout=REQUEST_TIMEOUT
                )
            except asyncio.TimeoutError:
                return JSONResponse(
                    {"detail": f"Request timed out after {REQUEST_TIMEOUT}s"},
                    status_code=504,
                )

    app.add_middleware(TimeoutMiddleware)

# ---------------------------------------------------------------------------
# Client IP tracking middleware
# ---------------------------------------------------------------------------
class ClientIpTrackerMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        client_host = request.client.host if request.client else None
        if client_host and client_host != "127.0.0.1":
            if STATE["last_client_ip"] != client_host:
                _logger.info("New mobile client detected: %s", client_host)
                STATE["last_client_ip"] = client_host
        return await call_next(request)

app.add_middleware(ClientIpTrackerMiddleware)

# ---------------------------------------------------------------------------
# Rate limiting (slowapi)
# ---------------------------------------------------------------------------
_limiter = Limiter(key_func=get_remote_address, default_limits=["60/minute"])
app.state.limiter = _limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)


# ---------------------------------------------------------------------------
# Input validation helper
# ---------------------------------------------------------------------------
def _resolve_id(presentation_id: str) -> str:
    """URL-decode and validate the presentation path parameter."""
    decoded = urllib.parse.unquote(presentation_id)
    if not decoded or len(decoded) > 512:
        raise HTTPException(status_code=400, detail="Invalid presentation_id.")
    return decoded


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------
@app.get("/api/health", response_model=HealthDto)
def health() -> HealthDto:
    network_type = get_network_type()
    is_hotspot = network_type in (
        NetworkType.HOTSPOT_PROVIDING,
        NetworkType.HOTSPOT_USING,
    )
    return HealthDto(
        status="ok",
        network_type=network_type.value,
        is_hotspot=is_hotspot,
    )


@app.get("/api/debug/ppt", include_in_schema=False)
def debug_ppt():
    """Diagnostic endpoint — open in browser to see the raw PowerPoint COM status."""
    import traceback
    try:
        items = controller.list_presentations()
        return {
            "status": "ok",
            "presentation_count": len(items),
            "presentations": [p.name for p in items],
        }
    except Exception as exc:
        _logger.error("debug_ppt: %s", traceback.format_exc())
        return {
            "status": "error",
            "error": str(exc),
            "type": type(exc).__name__,
        }

@app.get(
    "/api/network/status",
    response_model=NetworkStatusDto,
    dependencies=[Depends(verify_api_key)],
)
def network_status() -> NetworkStatusDto:
    network_type = get_network_type()

    warning = None
    if network_type == NetworkType.HOTSPOT_PROVIDING:
        warning = (
            "Desktop is providing hotspot to phone. Connection may be less stable."
        )
    elif network_type == NetworkType.HOTSPOT_USING:
        warning = (
            "Desktop is using a hotspot connection. Connection may be less stable."
        )

    return NetworkStatusDto(
        network_type=network_type.value,
        is_hotspot=(
            network_type in (NetworkType.HOTSPOT_PROVIDING, NetworkType.HOTSPOT_USING)
        ),
        warning=warning,
    )


@app.get(
    "/api/presentations",
    response_model=list[PresentationDto],
    dependencies=[Depends(verify_api_key)],
)
def list_presentations() -> list[PresentationDto]:
    try:
        items = controller.list_presentations()
        return [PresentationDto(**item.__dict__) for item in items]
    except PowerPointControllerError as exc:
        message = str(exc)
        if "PowerPoint is not running" in message or "Open at least one presentation first" in message:
            return []
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post(
    "/api/presentations/{presentation_id}/start",
    dependencies=[Depends(verify_api_key)],
)
@_limiter.limit("30/minute")
def start_slideshow(request: Request, presentation_id: str):
    try:
        controller.start_slideshow(_resolve_id(presentation_id))
        return {"ok": True}
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post(
    "/api/presentations/{presentation_id}/stop",
    dependencies=[Depends(verify_api_key)],
)
@_limiter.limit("30/minute")
def stop_slideshow(request: Request, presentation_id: str):
    try:
        controller.stop_slideshow(_resolve_id(presentation_id))
        return {"ok": True}
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post(
    "/api/presentations/{presentation_id}/next",
    dependencies=[Depends(verify_api_key)],
)
@_limiter.limit("30/minute")
def next_slide(request: Request, presentation_id: str):
    try:
        controller.next_slide(_resolve_id(presentation_id))
        return {"ok": True}
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post(
    "/api/presentations/{presentation_id}/previous",
    dependencies=[Depends(verify_api_key)],
)
@_limiter.limit("30/minute")
def previous_slide(request: Request, presentation_id: str):
    try:
        controller.previous_slide(_resolve_id(presentation_id))
        return {"ok": True}
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get(
    "/api/presentations/{presentation_id}/notes",
    response_model=list[SlideNotesDto],
    summary="Get speaker notes for every slide",
    dependencies=[Depends(verify_api_key)],
)
def get_all_speaker_notes(presentation_id: str) -> list[SlideNotesDto]:
    try:
        notes = controller.get_all_speaker_notes(_resolve_id(presentation_id))
        return [SlideNotesDto(slide_index=i + 1, notes=n) for i, n in enumerate(notes)]
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get(
    "/api/presentations/{presentation_id}/current-notes",
    response_model=SlideNotesDto,
    summary="Get speaker notes for the current slide (slideshow must be active)",
    dependencies=[Depends(verify_api_key)],
)
def get_current_slide_notes(presentation_id: str) -> SlideNotesDto:
    try:
        slide_index, notes = controller.get_current_slide_notes(
            _resolve_id(presentation_id)
        )
        return SlideNotesDto(slide_index=slide_index, notes=notes)
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get(
    "/api/presentations/{presentation_id}/slides/{slide_index}/thumbnail",
    summary="Get a PNG thumbnail of a specific slide",
    dependencies=[Depends(verify_api_key)],
    responses={200: {"content": {"image/png": {}}}},
)
def get_slide_thumbnail(
    request: Request,
    presentation_id: str,
    slide_index: int,
    width: int = 720,
):
    from fastapi.responses import Response  # noqa: PLC0415
    try:
        png = controller.get_slide_thumbnail(
            _resolve_id(presentation_id), slide_index, width
        )
        return Response(content=png, media_type="image/png")
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get(
    "/api/presentations/{presentation_id}/current-thumbnail",
    summary="Get a PNG thumbnail of the current slideshow slide",
    dependencies=[Depends(verify_api_key)],
    responses={200: {"content": {"image/png": {}}}},
)
def get_current_slide_thumbnail(
    request: Request,
    presentation_id: str,
    width: int = 720,
):
    from fastapi.responses import Response  # noqa: PLC0415
    try:
        _slide_index, png = controller.get_current_slide_thumbnail(
            _resolve_id(presentation_id), width
        )
        return Response(content=png, media_type="image/png")
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post(
    "/api/ftp/open",
    summary="Open Android FTP server in Windows File Explorer",
    dependencies=[Depends(verify_api_key)],
)
def open_ftp_on_pc():
    if not open_ftp_explorer():
        raise HTTPException(
            status_code=400,
            detail="No mobile client detected yet or failed to launch Explorer.",
        )
    return {"ok": True}
