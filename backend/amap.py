"""Server-side Gaode adapter. Never include keys or upstream URLs in errors."""
import os
import threading
import time
from collections import OrderedDict

import httpx

from .models import Leg, Place


class MapError(Exception):
    pass


SERVICES = {
    "/v3/direction/walking": "步行路线",
    "/v4/direction/bicycling": "骑行路线",
    "/v3/place/text": "地点搜索",
    "/v3/place/around": "周边搜索",
    "/v3/assistant/coordinate/convert": "坐标转换",
    "/v3/geocode/regeo": "地址查询",
}
ERROR_HINTS = {
    "10001": "Web 服务 Key 无效或已过期，请检查 backend/.env 的 AMAP_WEB_KEY。",
    "10002": "接口未获授权或不可用，请检查高德控制台的服务权限。",
    "10003": "今日调用额度已用完，请检查高德控制台配额。",
    "10004": "单位时间内访问过于频繁，请稍后重试。",
    "10005": "服务器出口 IP 不在 Key 的 IP 白名单中。",
    "10006": "Key 绑定域名无效，请检查平台类型与域名配置。",
    "10007": "数字签名验证失败，请核对 Key 的签名配置。",
    "10008": "安全码验证失败，请核对 Key 的平台类型与安全配置。",
    "10009": "Key 平台类型不匹配，AMAP_WEB_KEY 必须使用 Web 服务 Key。",
    "10010": "服务器 IP 调用超限，请检查高德控制台。",
    "10012": "接口权限不足，请检查高德控制台的服务权限。",
    "10013": "Key 已被删除，请更新 AMAP_WEB_KEY 并重启后端。",
    "10014": "接口每秒调用次数超限，请稍后重试或调大 AMAP_REQUEST_INTERVAL。",
    "10020": "接口每秒调用次数超限，请稍后重试或调大 AMAP_REQUEST_INTERVAL。",
    "10021": "接口每秒调用次数超限，请稍后重试或调大 AMAP_REQUEST_INTERVAL。",
    "10029": "接口每秒调用次数超限，请稍后重试或调大 AMAP_REQUEST_INTERVAL。",
    "20000": "请求参数不正确，请检查起终点和接口参数。",
    "20001": "缺少必填接口参数。",
    "20002": "请求参数超出接口允许范围。",
}
QPS_CODES = {"10014", "10020", "10021", "10029"}


def failure_code(data: dict) -> str:
    # V4 can also return the common gateway's V3-style authentication errors.
    for field in ("infocode", "errcode"):
        value = str(data.get(field, ""))
        if value.isascii() and value.isdigit() and len(value) <= 6 and value not in {"0", "10000"}:
            return value
    return "未知"


class Amap:
    def __init__(self, key: str, interval: float = 0.35, transport=None):
        self.key = key
        self.interval = max(0, interval)
        self.transport = transport
        self._lock = threading.Lock()
        self._last_call = 0.0
        self._cache = OrderedDict()

    def _get(self, path: str, params: dict) -> dict:
        if not self.key:
            raise MapError("尚未配置高德 Web 服务 Key，请在电脑 backend/.env 中填写并重启服务。")
        cache_key = (path, tuple(sorted(params.items())))
        # Serialized outbound calls also bound QPS across concurrent searches/plans.
        with self._lock:
            cached = self._cache.get(cache_key)
            if cached and time.monotonic() - cached[0] < 300:
                return cached[1]
            service = SERVICES.get(path, "地图服务")
            for attempt in range(3):
                time.sleep(max(0, self.interval - (time.monotonic() - self._last_call)))
                self._last_call = time.monotonic()
                try:
                    with httpx.Client(timeout=12, transport=self.transport) as client:
                        response = client.get("https://restapi.amap.com" + path, params={**params, "key": self.key})
                        response.raise_for_status()
                        data = response.json()
                except httpx.HTTPStatusError as error:
                    status = error.response.status_code
                    if status == 429 and attempt < 2:
                        time.sleep(2 ** attempt)
                        continue
                    raise MapError(f"高德{service}请求失败（HTTP {status}），请稍后重试。") from None
                except (httpx.HTTPError, ValueError):
                    raise MapError(f"高德{service}连接失败或响应异常，请稍后重试。") from None
                if not isinstance(data, dict):
                    raise MapError(f"高德{service}返回的数据格式异常。")
                success = (str(data.get("errcode")) == "0" if path.startswith("/v4/")
                           else str(data.get("status")) == "1")
                if success:
                    break
                code = failure_code(data)
                if code in QPS_CODES and attempt < 2:
                    time.sleep(2 ** attempt)
                    continue
                hint = ERROR_HINTS.get(code, "请求未成功，请根据错误码检查高德控制台的接口权限与配额。")
                # Never forward arbitrary upstream info/errmsg, request URLs or credentials.
                raise MapError(f"高德{service}失败（错误码 {code}）：{hint}")
            self._cache[cache_key] = (time.monotonic(), data)
            self._cache.move_to_end(cache_key)
            while len(self._cache) > 2048:
                self._cache.popitem(last=False)
            return data

    @staticmethod
    def _places(data: dict) -> list[Place]:
        places = []
        for item in data.get("pois", []):
            try:
                lon, lat = map(float, item["location"].split(","))
                places.append(Place(id=item["id"], name=item["name"], longitude=lon,
                                    latitude=lat, address=item.get("address") or ""))
            except (KeyError, ValueError, TypeError, AttributeError):
                continue
        return places

    def search(self, keyword: str, city: str) -> list[Place]:
        return self._places(self._get("/v3/place/text", {
            "keywords": keyword, "city": city, "citylimit": "true", "offset": 10, "page": 1,
        }))

    def current_place(self, longitude: float, latitude: float) -> Place:
        data = self._get("/v3/assistant/coordinate/convert", {
            "locations": f"{longitude:.6f},{latitude:.6f}", "coordsys": "gps",
        })
        try:
            lon, lat = map(float, data["locations"].split(","))
            place = Place(id=f"current:{lon:.6f},{lat:.6f}", name="当前位置", longitude=lon, latitude=lat)
        except (KeyError, ValueError, TypeError, AttributeError):
            raise MapError("当前位置坐标转换失败，请重试或手动选择起点。") from None
        # Address lookup is optional. Never substitute unconverted GPS coordinates.
        try:
            reverse = self._get("/v3/geocode/regeo", {"location": place.location, "extensions": "base"})
            address = reverse.get("regeocode", {}).get("formatted_address", "")
            if isinstance(address, str):
                place.address = address[:500]
        except (MapError, AttributeError):
            pass
        return place

    def nearby(self, keyword: str, center: Place) -> list[Place]:
        return self._places(self._get("/v3/place/around", {
            "keywords": keyword, "location": center.location, "radius": 3000,
            "sortrule": "distance", "offset": 25, "page": 1,
        }))

    def route(self, origin: Place, destination: Place, mode: str) -> Leg | None:
        if origin.location == destination.location:
            return Leg(seconds=0, meters=0)
        cycling = mode == "cycling"
        path = "/v4/direction/bicycling" if cycling else "/v3/direction/walking"
        data = self._get(path, {"origin": origin.location, "destination": destination.location})
        try:
            paths = data.get("data" if cycling else "route", {}).get("paths", [])
            if not paths:
                return None
            best = min(paths, key=lambda p: (int(p["distance"]), int(p["duration"])))
            return Leg(seconds=int(best["duration"]), meters=int(best["distance"]),
                       polyline=";".join(step.get("polyline", "") for step in best.get("steps", [])))
        except (ValueError, KeyError, TypeError, AttributeError):
            raise MapError("高德路线数据缺少有效的时间或距离。") from None


def from_environment() -> Amap:
    return Amap(os.getenv("AMAP_WEB_KEY", "").strip(), float(os.getenv("AMAP_REQUEST_INTERVAL", "0.35")))
