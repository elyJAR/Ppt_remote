from __future__ import annotations

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from powerpoint_controller import PowerPointController, PowerPointControllerError


class PresentationDto(BaseModel):
    id: str
    name: str
    path: str
    in_slideshow: bool
    current_slide: int | None
    total_slides: int


class HealthDto(BaseModel):
    status: str


app = FastAPI(title="PowerPoint Bridge API", version="0.1.0")
controller = PowerPointController()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health", response_model=HealthDto)
def health() -> HealthDto:
    return HealthDto(status="ok")


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
