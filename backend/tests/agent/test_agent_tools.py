"""Tool contracts are independent of a particular model or business workflow."""
import importlib.util

import pytest


def api():
    assert importlib.util.find_spec("backend.agent.tools") is not None, "Agent tool catalog is not implemented"
    from backend.agent.tools import ToolCatalog, ToolDefinition, ToolError
    return ToolCatalog, ToolDefinition, ToolError


def definition(namespace="travel", name="search", **changes):
    _, ToolDefinition, _ = api()
    return ToolDefinition(namespace=namespace, name=name, description=changes.pop("description", "查询成都旅行景点开放时间和位置"),
                          input_schema={"type": "object", "properties": {"city": {"type": "string"}},
                                        "required": ["city"], "additionalProperties": False}, **changes)


def test_discovery_shortlists_relevant_tools_without_loading_schemas():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    catalog.register(definition(), lambda args: {"city": args["city"]})
    catalog.register(definition("notes", "save", effect="write", description="保存笔记"), lambda args: {})
    results = catalog.discover("成都景点开放时间", limit=1)
    assert len(results) == 1
    assert results[0]["tool_id"] == "travel.search"
    assert "input_schema" not in results[0]
    assert catalog.describe("travel.search")["input_schema"]["required"] == ["city"]


def test_explicit_namespace_beats_description_overlap_in_chinese_query():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    catalog.register(definition("exa", "web_search_exa", description="Search the web for current information and facts."), lambda args: {})
    catalog.register(definition("exa", "web_fetch_exa", description="Read full content from known URLs."), lambda args: {})
    for index in range(6):
        catalog.register(definition(f"maps{index}", "places", description="查询成都景点官方开放时间、预约规则和门票信息"), lambda args: {})
    results = catalog.discover("查找 Exa 网页搜索工具，用于查询成都景点官方开放时间、预约规则和门票信息", limit=5)
    assert {tool["tool_id"] for tool in results[:2]} == {"exa.web_search_exa", "exa.web_fetch_exa"}
    assert any(tool["tool_id"].startswith("maps") for tool in results[2:])
    assert all("input_schema" not in tool for tool in results)


def test_exact_tool_identity_beats_namespace_and_topic_matches():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    catalog.register(definition("exa", "web_search_exa", description="Search the web for current facts."), lambda args: {})
    catalog.register(definition("exa", "web_fetch_exa", description="读取景点预约规则、官方开放时间和门票详情"), lambda args: {})
    catalog.register(definition("map", "search", description="读取景点预约规则、官方开放时间和门票详情"), lambda args: {})
    results = catalog.discover("请用 `Exa.web_search_exa` 读取景点预约规则、官方开放时间和门票详情。", limit=3)
    assert [tool["tool_id"] for tool in results] == ["exa.web_search_exa", "exa.web_fetch_exa", "map.search"]


def test_namespace_match_is_whole_identifier_and_bilingual_descriptions_still_rank():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    catalog.register(definition("exa", "search", description="Query content."), lambda args: {})
    catalog.register(definition("examples", "search", description="查询开放时间 Opening hours and reservations."), lambda args: {})
    catalog.register(definition("weather-data", "forecast", description="天气预报 Weather forecast."), lambda args: {})
    assert [tool["tool_id"] for tool in catalog.discover("examples 官方开放时间", limit=1)] == ["examples.search"]
    assert [tool["tool_id"] for tool in catalog.discover("天气预报", limit=1)] == ["weather-data.forecast"]
    assert [tool["tool_id"] for tool in catalog.discover("Weather-data 工具：查询开放时间", limit=1)] == ["weather-data.forecast"]
    assert catalog.discover("hexagon unrelated", limit=1) == []


def test_namespace_index_tracks_register_replace_and_revoke_without_tool_details():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    assert catalog.namespace_index() == {"items": [], "omitted": 0}
    catalog.register(definition("beta", "search"), lambda args: {})
    catalog.register(definition("beta", "save", effect="write"), lambda args: {})
    catalog.register(definition("alpha", "search", description="Private description not needed for the index",
                                source="https://private.example/api?key=synthetic-private-value"), lambda args: {})
    catalog.register(definition("beta", "search", description="Updated contract"), lambda args: {})
    assert catalog.namespace_index(limit=1) == {"items": [{"namespace": "alpha", "tool_count": 1}], "omitted": 1}
    assert catalog.namespace_index() == {"items": [
        {"namespace": "alpha", "tool_count": 1}, {"namespace": "beta", "tool_count": 2}], "omitted": 0}
    catalog.revoke("beta.save")
    catalog.replace_namespace("alpha", [(definition("alpha", "lookup"), lambda args: {}),
                                         (definition("alpha", "details"), lambda args: {})])
    assert catalog.namespace_index() == {"items": [
        {"namespace": "alpha", "tool_count": 2}, {"namespace": "beta", "tool_count": 1}], "omitted": 0}
    catalog.replace_namespace("beta", [])
    assert catalog.namespace_index() == {"items": [{"namespace": "alpha", "tool_count": 2}], "omitted": 0}


def test_namespace_index_bounds_the_service_list_and_reports_omissions():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    for index in range(25):
        catalog.register(definition(f"service{index:02d}", "query"), lambda args: {})
    result = catalog.namespace_index()
    assert result == {"items": [{"namespace": f"service{index:02d}", "tool_count": 1} for index in range(20)], "omitted": 5}
    assert catalog.namespace_index(limit=1) == {"items": [{"namespace": "service00", "tool_count": 1}], "omitted": 24}


@pytest.mark.parametrize("limit", [0, 21, True, "1", None])
def test_namespace_index_rejects_invalid_limits(limit):
    ToolCatalog, _, ToolError = api()
    with pytest.raises(ToolError) as caught:
        ToolCatalog().namespace_index(limit=limit)
    assert caught.value.code == "invalid_query"


def test_chinese_goal_can_find_new_english_capabilities_via_namespace_index():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    catalog.register(definition("city_events", "upcoming", description="Find upcoming local cultural events."), lambda args: {})
    assert catalog.discover("查询周末的文化活动") == []
    index = catalog.namespace_index()
    assert index == {"items": [{"namespace": "city_events", "tool_count": 1}], "omitted": 0}
    discovered = catalog.discover(index["items"][0]["namespace"])
    assert [tool["tool_id"] for tool in discovered] == ["city_events.upcoming"]
    assert "input_schema" not in discovered[0]


def test_discovery_pages_browse_every_registered_namespace_tool_once():
    ToolCatalog, _, ToolError = api()
    catalog = ToolCatalog()
    dispatched = []
    for index in reversed(range(12)):
        catalog.register(definition("atlas", f"tool{index:02d}", description="Retrieve an external record",
                                    effect="write" if index == 11 else "read"), lambda args: dispatched.append(args))
    catalog.register(definition("other", "tool00"), lambda args: dispatched.append(args))
    seen, offset, version = [], 0, None
    while True:
        page = catalog.discover_page(namespace="atlas", offset=offset, directory_version=version)
        assert page["query"] == "" and page["namespace"] == "atlas"
        assert page["offset"] == offset and page["total"] == 12
        assert version is None or page["directory_version"] == version
        assert len(page["tools"]) <= 5
        seen.extend(item["tool_id"] for item in page["tools"])
        if page["next_offset"] is None:
            break
        offset, version = page["next_offset"], page["directory_version"]
    assert seen == [f"atlas.tool{index:02d}" for index in range(12)]
    assert len(seen) == len(set(seen)) and dispatched == []
    with pytest.raises(ToolError) as denied:
        catalog.call("atlas.tool11", {"city": "成都"})
    assert denied.value.code == "authorization_required" and dispatched == []


def test_discovery_page_search_preserves_ranking_and_exact_identity_selection():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    for namespace in ("alpha", "beta"):
        for index in range(8):
            catalog.register(definition(namespace, f"query{index}", description="Find current records"), lambda args: {})
    query = "beta.query7 current records"
    page = catalog.discover_page(query, namespace="beta")
    expected = [item["tool_id"] for item in catalog.discover(query, limit=20) if item["tool_id"].startswith("beta.")]
    assert [item["tool_id"] for item in page["tools"]] == expected[:5]
    assert page["tools"][0]["tool_id"] == "beta.query7" and page["total"] == 8
    assert catalog.discover_page("no_matches", namespace="beta")["total"] == 0
    assert catalog.discover_page("no_matches", namespace="beta")["next_offset"] is None


def test_discovery_page_exact_identity_keeps_only_the_selected_tool():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    for namespace in ("alpha", "beta"):
        for index in range(8):
            catalog.register(definition(namespace, f"query{index}", description="Find current records"), lambda args: {})
    page = catalog.discover_page("beta.query7")
    assert [item["tool_id"] for item in page["tools"]] == ["beta.query7"]
    assert page["total"] == 1 and page["next_offset"] is None
    mismatch = catalog.discover_page("beta.query7", namespace="alpha")
    assert mismatch["tools"] == [] and mismatch["total"] == 0
    assert len(catalog.discover("beta.query7")) == 5


@pytest.mark.parametrize("change", ["missing_version", "query", "namespace", "added", "removed", "contract"])
def test_discovery_page_continuation_is_bound_to_query_namespace_and_catalog(change):
    ToolCatalog, _, ToolError = api()
    catalog = ToolCatalog()
    for namespace in ("alpha", "beta"):
        for index in range(3):
            catalog.register(definition(namespace, f"query{index}", description="Find current records"), lambda args: {})
    first = catalog.discover_page(namespace="alpha", limit=1)
    args = {"namespace": "alpha", "offset": 1, "limit": 1, "directory_version": first["directory_version"]}
    if change == "missing_version":
        args.pop("directory_version")
    elif change == "query":
        args["query"] = "records"
    elif change == "namespace":
        args["namespace"] = "beta"
    elif change == "added":
        catalog.register(definition("alpha", "query9"), lambda args: {})
    elif change == "removed":
        catalog.revoke("alpha.query2")
    else:
        catalog.register(definition("alpha", "query2", description="Changed tool contract"), lambda args: {})
    with pytest.raises(ToolError) as changed:
        catalog.discover_page(**args)
    assert changed.value.code == "directory_changed"
    if change == "removed":
        with pytest.raises(ToolError) as revoked:
            catalog.describe("alpha.query2")
        assert revoked.value.code == "tool_unavailable"


def test_discovery_summary_is_byte_bounded_and_does_not_copy_schema_or_source_url():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    description = "位置🙂" * 100
    catalog.register(definition("atlas", "lookup", description=description,
                                source="https://private.example/api?key=synthetic-private-value"), lambda args: {})
    summary = catalog.discover_page(namespace="atlas")["tools"][0]
    assert 0 < len(summary["description"].encode("utf-8")) <= 160
    assert description.startswith(summary["description"]) and summary["description_truncated"] is True
    assert "input_schema" not in summary and "output_schema" not in summary and "source" not in summary
    assert summary["effect"] == "read" and summary["executor"] == "server"
    assert summary["version"] == catalog.describe("atlas.lookup")["version"]
    assert catalog.describe("atlas.lookup")["description"] == description
    catalog.register(definition("atlas", "short", description="Short public description"), lambda args: {})
    short = next(item for item in catalog.discover_page(namespace="atlas")["tools"] if item["tool_id"] == "atlas.short")
    assert short["description_truncated"] is False and short["source"] == "builtin:atlas"


@pytest.mark.parametrize("args", [{"offset": -1}, {"offset": True}, {"offset": "1"},
                                  {"limit": 0}, {"limit": 21}, {"limit": True},
                                  {"query": None}, {"namespace": "missing"}])
def test_discovery_page_rejects_invalid_requests(args):
    ToolCatalog, _, ToolError = api()
    with pytest.raises(ToolError) as caught:
        ToolCatalog().discover_page(**args)
    assert caught.value.code == "invalid_query"


def test_namespace_labels_are_explicit_registration_metadata_not_tool_claims():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    tool = definition("maps_primary", "search", description="Claiming to be official",
                      source="mcp:official:amap")
    catalog.register(tool, lambda args: {})
    assert catalog.namespace_index()["items"] == [{"namespace": "maps_primary", "tool_count": 1}]
    labels = {"display_name": "高德地图", "aliases": ["高德", "Amap"]}
    catalog.replace_namespace("maps_primary", [(tool, lambda args: {})], metadata=labels)
    labels["aliases"].append("injected-after-registration")
    item = catalog.namespace_index()["items"][0]
    assert item == {"namespace": "maps_primary", "tool_count": 1,
                    "display_name": "高德地图", "aliases": ["高德", "Amap"]}
    catalog.replace_namespace("maps_primary", [])
    assert catalog.namespace_index() == {"items": [], "omitted": 0}


def test_namespaced_identity_preserves_same_name_from_two_servers():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    catalog.register(definition("weather"), lambda args: {"kind": "weather"})
    catalog.register(definition("travel"), lambda args: {"kind": "attractions"})
    weather = catalog.call("weather.search", {"city": "成都"})
    travel = catalog.call("travel.search", {"city": "成都"})
    assert weather["data"] != travel["data"]
    assert weather["tool_id"] == "weather.search"
    assert weather["source"] == "builtin:weather"
    assert weather["observed_at"] and weather["version"]


@pytest.mark.parametrize("arguments", [{}, {"city": 7}, {"city": "成都", "extra": True}, []])
def test_invalid_arguments_never_reach_handler(arguments):
    ToolCatalog, _, ToolError = api()
    calls = []
    catalog = ToolCatalog()
    catalog.register(definition(), lambda args: calls.append(args))
    with pytest.raises(ToolError) as caught:
        catalog.call("travel.search", arguments)
    assert caught.value.code == "invalid_arguments"
    assert calls == []


def test_effect_is_local_policy_and_write_requires_executor_authorization():
    ToolCatalog, _, ToolError = api()
    calls = []
    catalog = ToolCatalog()
    catalog.register(definition("notes", "save", effect="write"), lambda args: calls.append(args) or {"saved": True})
    with pytest.raises(ToolError) as caught:
        catalog.call("notes.save", {"city": "成都"})
    assert caught.value.code == "authorization_required"
    assert calls == []
    assert catalog.call("notes.save", {"city": "成都"}, authorized=True)["data"] == {"saved": True}


def test_schema_version_change_rejects_prepared_parameters_before_execution():
    ToolCatalog, ToolDefinition, ToolError = api()
    catalog = ToolCatalog()
    first = definition()
    catalog.register(first, lambda args: {})
    previous = catalog.describe(first.tool_id)["version"]
    changed = ToolDefinition(namespace="travel", name="search", description=first.description,
                             input_schema={"type": "object", "required": ["date"]})
    calls = []
    catalog.register(changed, lambda args: calls.append(args))
    assert catalog.describe(first.tool_id)["version"] != previous
    with pytest.raises(ToolError) as caught:
        catalog.call(first.tool_id, {"city": "成都"}, expected_version=previous)
    assert caught.value.code == "tool_changed"
    assert calls == []


def test_revoke_removes_discovery_and_prevents_execution():
    ToolCatalog, _, ToolError = api()
    catalog = ToolCatalog()
    catalog.register(definition(), lambda args: {})
    catalog.revoke("travel.search")
    assert catalog.discover("成都") == []
    with pytest.raises(ToolError) as caught:
        catalog.call("travel.search", {"city": "成都"})
    assert caught.value.code == "tool_unavailable"


def test_descriptions_and_arguments_are_defensive_copies():
    ToolCatalog, _, _ = api()
    catalog = ToolCatalog()
    tool = definition()
    catalog.register(tool, lambda args: args.update(city="changed") or args)
    catalog.describe(tool.tool_id)["input_schema"]["required"].append("unsafe")
    arguments = {"city": "成都"}
    assert catalog.call(tool.tool_id, arguments)["data"] == {"city": "changed"}
    assert arguments == {"city": "成都"}
    assert catalog.describe(tool.tool_id)["input_schema"]["required"] == ["city"]


def test_remote_schema_references_rejected_without_network():
    _, ToolDefinition, ToolError = api()
    with pytest.raises(ToolError) as caught:
        ToolDefinition(namespace="travel", name="search", description="search",
                       input_schema={"type": "object", "properties": {"value": {"$ref": "https://example.invalid/schema"}}})
    assert caught.value.code == "invalid_schema"


def test_output_limits_and_handler_error_do_not_expose_details():
    ToolCatalog, _, ToolError = api()
    catalog = ToolCatalog(max_result_bytes=128)
    catalog.register(definition(), lambda args: {"value": "x" * 200})
    with pytest.raises(ToolError) as caught:
        catalog.call("travel.search", {"city": "成都"})
    assert caught.value.code == "result_too_large"
    def broken(args):
        raise RuntimeError("private-provider-url?key=secret")
    catalog.register(definition("notes", "save", effect="write"), broken)
    with pytest.raises(ToolError) as caught:
        catalog.call("notes.save", {"city": "成都"}, authorized=True)
    assert caught.value.outcome == "unknown"
    assert "secret" not in str(caught.value)


def test_discovery_limit_and_unknown_tool_are_explicit():
    ToolCatalog, _, ToolError = api()
    catalog = ToolCatalog()
    with pytest.raises(ToolError):
        catalog.discover("", limit=1000)
    with pytest.raises(ToolError) as caught:
        catalog.describe("missing.tool")
    assert caught.value.code == "tool_unavailable"
