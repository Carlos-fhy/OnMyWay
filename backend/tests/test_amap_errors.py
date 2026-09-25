import httpx
import pytest

from backend.amap import Amap, MapError


@pytest.mark.parametrize('path,payload', [
    ('/v3/direction/walking', {'status': '1', 'route': {}}),
    ('/v4/direction/bicycling', {'errcode': 0, 'data': {}}),
])
def test_success_formats_and_cache(path, payload):
    calls = []
    def respond(request):
        calls.append(request)
        return httpx.Response(200, json=payload)
    provider = Amap('secret', 0, httpx.MockTransport(respond))
    assert provider._get(path, {}) == payload
    assert provider._get(path, {}) == payload
    assert len(calls) == 1


@pytest.mark.parametrize('code', ['10001', '10003', '10009', '10012'])
def test_v4_gateway_error_is_specific_safe_and_not_retried(code):
    calls = []
    def respond(request):
        calls.append(request)
        return httpx.Response(200, json={'status': '0', 'infocode': code, 'info': str(request.url)})
    provider = Amap('secret', 0, httpx.MockTransport(respond))
    with pytest.raises(MapError) as error:
        provider._get('/v4/direction/bicycling', {})
    assert code in str(error.value)
    assert '骑行路线' in str(error.value)
    assert 'secret' not in str(error.value)
    assert 'https' not in str(error.value)
    assert len(calls) == 1


@pytest.mark.parametrize('recover', [True, False])
@pytest.mark.parametrize('http_status', [200, 429])
def test_rate_limit_retry_is_bounded(monkeypatch, recover, http_status):
    monkeypatch.setattr('backend.amap.time.sleep', lambda _: None)
    calls = []
    def respond(request):
        calls.append(request)
        if recover and len(calls) == 3:
            return httpx.Response(200, json={'errcode': 0})
        return httpx.Response(http_status, json={'status': '0', 'infocode': '10021'})
    provider = Amap('secret', 0, httpx.MockTransport(respond))
    if recover:
        assert provider._get('/v4/direction/bicycling', {}) == {'errcode': 0}
    else:
        with pytest.raises(MapError):
            provider._get('/v4/direction/bicycling', {})
        assert not provider._cache
    assert len(calls) == 3


def test_unknown_error_does_not_echo_arbitrary_fields():
    provider = Amap('secret', 0, httpx.MockTransport(lambda request: httpx.Response(
        200, json={'errcode': str(request.url), 'errmsg': 'secret'})))
    with pytest.raises(MapError) as error:
        provider._get('/v4/direction/bicycling', {})
    assert '未知' in str(error.value)
    assert 'secret' not in str(error.value)



def test_nearby_fetches_full_first_page_without_exposing_key():
    urls = []
    def respond(request):
        urls.append(request.url)
        return httpx.Response(200, json={'status': '1', 'pois': []})
    provider = Amap('secret', 0, httpx.MockTransport(respond))
    from backend.models import Place
    center = Place(id='school', name='school', longitude=120.576480, latitude=31.249126)
    assert provider.nearby('supermarket|convenience store', center) == []
    assert len(urls) == 1
    assert urls[0].params['offset'] == '25'
