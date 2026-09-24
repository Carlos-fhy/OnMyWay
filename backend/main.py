from pathlib import Path
import threading

from dotenv import load_dotenv
from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse

from .amap import MapError, from_environment
from .models import LocationRequest, Place, PlanRequest, PlanResult
from .planner import PlanningError, plan, recommend
from .assistant import router as assistant_router

load_dotenv(Path(__file__).with_name(".env"))
app = FastAPI(title="OnMyWay local planner", version="0.1.0")
app.include_router(assistant_router)
app.state.provider = from_environment()
planning_slot = threading.BoundedSemaphore(1)


@app.exception_handler(MapError)
async def map_error(_request: Request, error: MapError):
    return JSONResponse(status_code=503, content={"detail": str(error)})


@app.exception_handler(PlanningError)
async def planning_error(_request: Request, error: PlanningError):
    return JSONResponse(status_code=422, content={"detail": str(error)})


@app.get("/health")
def health():
    return {"status": "ok", "amap_configured": bool(app.state.provider.key), "version": "0.1.0"}


@app.get("/places", response_model=list[Place])
def places(q: str = Query(min_length=1, max_length=100), city: str = Query(min_length=1, max_length=50)):
    return app.state.provider.search(q, city)


@app.post("/plans", response_model=PlanResult)
def plans(body: PlanRequest):
    if not planning_slot.acquire(blocking=False):
        return JSONResponse(status_code=429, content={"detail": "已有规划正在运行，请稍后重试。"})
    try:
        return plan(body, app.state.provider)
    finally:
        planning_slot.release()


@app.post("/recommendations")
def recommendations(body: PlanRequest):
    if not planning_slot.acquire(blocking=False):
        return JSONResponse(status_code=429, content={"detail": "已有规划正在运行，请稍后重试。"})
    try:
        return recommend(body, app.state.provider)
    finally:
        planning_slot.release()


@app.post("/location", response_model=Place)
def current_location(body: LocationRequest):
    return app.state.provider.current_place(body.longitude, body.latitude)
