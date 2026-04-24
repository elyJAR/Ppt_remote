from __future__ import annotations

import json
import socket
import threading

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from powerpoint_controller import PowerPointController, PowerPointControllerError
from network_detector import get_network_type, NetworkType


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


app = FastAPI(title="PowerPoint Bridge API", version="0.1.0")
controller = PowerPointController()
DISCOVERY_PORT = 8788
BRIDGE_PORT = 8787
DISCOVERY_TOKEN = "PPT_REMOTE_DISCOVER"

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


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
    def __init__(self, discovery_port: int, bridge_port: int):
        self._discovery_port = discovery_port
        self._bridge_port = bridge_port
        self._stop_event = threading.Event()
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
                payload = json.dumps({"bridge_url": f"http://{local_ip}:{self._bridge_port}"})
                sock.sendto(payload.encode("utf-8"), addr)
        finally:
            sock.close()


discovery_responder = DiscoveryResponder(DISCOVERY_PORT, BRIDGE_PORT)


@app.on_event("startup")
def startup_event() -> None:
    discovery_responder.start()


@app.on_event("shutdown")
def shutdown_event() -> None:
    discovery_responder.stop()


@app.get("/api/health", response_model=HealthDto)
def health() -> HealthDto:
    network_type = get_network_type()
    is_hotspot = network_type in (NetworkType.HOTSPOT_PROVIDING, NetworkType.HOTSPOT_USING)
    return HealthDto(
        status="ok",
        network_type=network_type.value,
        is_hotspot=is_hotspot
    )


@app.get("/api/network/status", response_model=NetworkStatusDto)
def network_status() -> NetworkStatusDto:
    network_type = get_network_type()
    
    warning = None
    if network_type == NetworkType.HOTSPOT_PROVIDING:
        warning = "Desktop is providing hotspot to phone. Connection may be less stable."
    elif network_type == NetworkType.HOTSPOT_USING:
        warning = "Desktop is using a hotspot connection. Connection may be less stable."
    
    return NetworkStatusDto(
        network_type=network_type.value,
        is_hotspot=(network_type in (NetworkType.HOTSPOT_PROVIDING, NetworkType.HOTSPOT_USING)),
        warning=warning
    )


@app.get("/api/presentations", response_model=list[PresentationDto])
def list_presentations() -> list[PresentationDto]:
    try:
        items = controller.list_presentations()
        return [PresentationDto(**item.__dict__) for item in items]
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/presentations/{presentation_id}/start")
def start_slideshow(presentation_id: str):
    try:
        controller.start_slideshow(presentation_id)
        return {"ok": True}
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/presentations/{presentation_id}/stop")
def stop_slideshow(presentation_id: str):
    try:
        controller.stop_slideshow(presentation_id)
        return {"ok": True}
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/presentations/{presentation_id}/next")
def next_slide(presentation_id: str):
    try:
        controller.next_slide(presentation_id)
        return {"ok": True}
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/presentations/{presentation_id}/previous")
def previous_slide(presentation_id: str):
    try:
        controller.previous_slide(presentation_id)
        return {"ok": True}
    except PowerPointControllerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
