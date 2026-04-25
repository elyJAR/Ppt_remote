from __future__ import annotations

import json
import os
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
# Configuration (overridable via environment variables)
# ---------------------------------------------------------------------------
BRIDGE_PORT: int = int(os.getenv("PPT_BRIDGE_PORT", "8787"))
DISCOVERY_PORT: int = int(os.getenv("PPT_DISCOVERY_PORT", "8788"))
DISCOVERY_TOKEN: str = "PPT_REMOTE_DISCOVER"

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
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

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
