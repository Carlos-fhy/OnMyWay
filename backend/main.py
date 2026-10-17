from pathlib import Path
from ipaddress import ip_address
import threading

from dotenv import load_dotenv
from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse

from .amap import MapError, from_environment
from .models import LocationRequest, Place, PlanRequest, PlanResult
from .planner import PlanningError, plan, recommend
from .assistant import router as assistant_router
from .backup import router as backup_router
from .sync import router as sync_router
from .tasks import router as tasks_router

load_dotenv(Path(__file__).with_name(".env"))
app = FastAPI(title="OnMyWay local planner", version="0.1.0")
app.include_router(assistant_router)
app.include_router(backup_router)
app.include_router(sync_router)
app.include_router(tasks_router)
app.state.provider = from_environment()
planning_slot = threading.BoundedSemaphore(1)


def _local_client(host: str) -> bool:
    # Starlette uses an in-process pseudo-client in tests; TCP peers have IP addresses.
    if host == "testclient":
        return True
    try:
        return ip_address(host).is_loopback
    except ValueError:
        return False


@app.middleware("http")
async def restrict_to_local_development(request: Request, call_next):
    # This backend has no deployment-grade user identity yet. A different uvicorn
    # bind address must not turn paid model/map endpoints into public APIs.
    if request.client is None or not _local_client(request.client.host):
        return JSONResponse(status_code=403, content={
            "detail": "当前服务仅允许本机或 USB 转发访问；公网部署前需配置身份认证。"})
    return await call_next(request)


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
