import httpx
import pytest
from fastapi.testclient import TestClient

from backend.amap import Amap, MapError
from backend.main import app
from backend.models import Place


ORIGIN = Place(id="a", name="a", longitude=110, latitude=30)
END = Place(id="b", name="b", longitude=110.01, latitude=30)


@pytest.mark.parametrize("mode,payload,path", [
    ("walking", {"status": "1", "route": {"paths": [{"duration": "12", "distance": "20", "steps": []}]}}, "/v3/direction/walking"),
    ("cycling", {"errcode": 0, "data": {"paths": [{"duration": 12, "distance": 20, "steps": []}]}}, "/v4/direction/bicycling"),
])
def test_map_modes_and_cache(mode, payload, path):
    requests = []
    def handler(req):
        requests.append(req)
        return httpx.Response(200, json=payload)
    provider = Amap("not-a-real-key", interval=0, transport=httpx.MockTransport(handler))
    assert provider.route(ORIGIN, END, mode).seconds == 12
    provider.route(ORIGIN, END, mode)
    assert len(requests) == 1
    assert requests[0].url.path == path


def test_errors_do_not_leak_key_or_upstream_url():
    def handler(req):
        raise httpx.ConnectError(str(req.url), request=req)
    provider = Amap("secret-sentinel", interval=0, transport=httpx.MockTransport(handler))
    with pytest.raises(MapError) as error:
        provider.route(ORIGIN, END, "walking")
    assert "secret-sentinel" not in str(error.value)
    assert "restapi" not in str(error.value)


def test_health_and_missing_key(monkeypatch):
    monkeypatch.setattr(app.state, "provider", Amap(""))
    with TestClient(app) as client:
        assert client.get("/health").json()["amap_configured"] is False
        response = client.get("/places", params={"q": "打印", "city": "上海"})
        assert response.status_code == 503
        assert "Key" in response.json()["detail"]
        assert client.post("/plans", json={}).status_code == 422


def test_search_skips_malformed_and_empty_locations():
    data = {"pois": [{"id": "bad", "name": "bad", "location": ""},
                     {"id": "ok", "name": "shop", "location": "110,30", "address": []}]}
    places = Amap._places(data)
    assert len(places) == 1
    assert places[0].address == ""


def test_invalid_map_credentials_are_not_no_route():
    provider = Amap("secret", interval=0, transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"status": "0", "info": "INVALID_USER_KEY"})))
    with pytest.raises(MapError, match="权限"):
        provider.route(ORIGIN, END, "walking")


def test_end_to_end_plan_response_with_injected_map(monkeypatch):
    from datetime import datetime, timezone
    from backend.models import Leg
    class Maps:
        key = "test"
        def route(self, origin, destination, mode):
            return Leg(seconds=100, meters=80)
    monkeypatch.setattr(app.state, "provider", Maps())
    body = {"origin": ORIGIN.model_dump(), "destination": END.model_dump(),
            "departure": datetime.now(timezone.utc).isoformat(), "mode": "cycling",
            "tasks": [{"id": "buy", "title": "buy", "place": END.model_dump(), "service_minutes": 2}]}
    with TestClient(app) as client:
        response = client.post("/plans", json=body)
    assert response.status_code == 200
    result = response.json()
    assert result["stops"][0]["task_ids"] == ["buy"]
    assert result["service_seconds"] == 120
    assert result["mode"] == "cycling"
    assert "key" not in result


def test_current_location_converts_before_reverse_geocoding():
    requests = []
    def handler(req):
        requests.append(req)
        if req.url.path.endswith("convert"):
            assert req.url.params["coordsys"] == "gps"
            return httpx.Response(200, json={"status": "1", "locations": "110.005,30.002"})
        assert req.url.params["location"] == "110.005000,30.002000"
        return httpx.Response(200, json={"status": "1", "regeocode": {"formatted_address": "Test address"}})
    provider = Amap("test", interval=0, transport=httpx.MockTransport(handler))
    place = provider.current_place(110, 30)
    assert (place.longitude, place.latitude) == (110.005, 30.002)
    assert place.address == "Test address"
    assert len(requests) == 2


def test_current_location_never_falls_back_to_raw_gps():
    provider = Amap("test", interval=0, transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"status": "1", "locations": "bad"})))
    with pytest.raises(MapError, match="坐标转换失败"):
        provider.current_place(110, 30)


def test_location_endpoint_validates_coordinates_and_address_is_optional(monkeypatch):
    def handler(req):
        if req.url.path.endswith("convert"):
            return httpx.Response(200, json={"status": "1", "locations": "110.005,30.002"})
        return httpx.Response(200, json={"status": "0"})
    monkeypatch.setattr(app.state, "provider", Amap("test", interval=0, transport=httpx.MockTransport(handler)))
    with TestClient(app) as client:
        assert client.post("/location", json={"longitude": 200, "latitude": 30}).status_code == 422
        response = client.post("/location", json={"longitude": 110, "latitude": 30})
        assert response.status_code == 200
        assert response.json()["longitude"] == 110.005
        assert response.json()["address"] == ""
