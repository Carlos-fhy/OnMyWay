"""Regression for a real review that omitted correct route evidence at 12000."""
import copy
import json
from pathlib import Path

import pytest

from backend.agent.decision import DecisionError
from backend.agent.review import build_review_context, parse_review_result


@pytest.fixture
def route_review_case():
    path = Path(__file__).parents[1] / 'fixtures' / 'a1_review_evidence.json'
    return json.loads(path.read_text(encoding='utf-8'))


def visible_values(source):
    return {parent + name: text for parent, group in source['values'].items() for name, text in group.items()}


@pytest.mark.parametrize('contradict', [False, True])
def test_review_keeps_all_published_leg_and_stop_facts(route_review_case, contradict):
    fixture = copy.deepcopy(route_review_case)
    route = fixture['sources'][1]['result']['data']['plan']
    if contradict:
        route['legs'][2]['meters'] = 9001
        route['stops'][1]['task_ids'] = ['other-task']
    context = build_review_context(fixture['snapshot'], fixture['sources'])
    data = json.loads(context.messages[-1]['content'])
    source = data['sources'][1]
    values = visible_values(source)
    for index, leg in enumerate(route['legs']):
        for field in ('meters', 'seconds'):
            assert values.get(f'/data/plan/legs/{index}/{field}') == str(leg[field])
    for index, stop in enumerate(route['stops']):
        assert values.get(f'/data/plan/stops/{index}/service_seconds') == str(stop['service_seconds'])
        for field in ('task_ids', 'task_titles'):
            assert values.get(f'/data/plan/stops/{index}/{field}/0') == stop[field][0]
    for index, warning in enumerate(route['warnings']):
        assert values.get(f'/data/plan/warnings/{index}') == warning
    assert values.get('/data/plan/origin/address') == route['origin']['address']
    for field in ('total_meters', 'travel_seconds', 'service_seconds', 'total_seconds'):
        assert values.get(f'/data/plan/{field}') == str(route[field])
    assert context.estimated_input_tokens <= 12000
    assert data['artifacts'][0]['content'] == fixture['snapshot']['artifacts'][0]['content']
    assert data['requirements']['constraints'] == fixture['snapshot']['constraints']
    assert source['coverage']['complete'] is False  # Road geometry remains explicitly omitted.
    if contradict:
        verdict = {'status': 'contradicted', 'findings': [{
            'location': 'legs/2/meters', 'issue': 'The original leg distance differs.',
            'source_call_id': source['tool_call_id'], 'quote': '9001'}]}
        assert parse_review_result(json.dumps(verdict), context).status == 'contradicted'


def test_review_exposes_durable_effect_without_accepting_source_effect(route_review_case):
    fixture = copy.deepcopy(route_review_case)
    fixture['sources'][1]['result']['effect'] = 'write'
    context = build_review_context(fixture['snapshot'], fixture['sources'])
    data = json.loads(context.messages[-1]['content'])
    assert all(fact.get('effect') == 'read' for fact in data['tool_call_facts'])
    changed = copy.deepcopy(fixture['snapshot'])
    changed['tool_calls'][0]['effect'] = 'write'
    assert build_review_context(changed, fixture['sources']).fingerprint != context.fingerprint
    # Trustworthy effect metadata is not a quotation from the original tool body.
    verdict = {'status': 'contradicted', 'findings': [{
        'location': 'summary', 'issue': 'Invalid metadata quotation.',
        'source_call_id': fixture['sources'][0]['tool_call_id'], 'quote': 'effect=read'}]}
    with pytest.raises(DecisionError):
        parse_review_result(json.dumps(verdict), context)


def test_all_array_positions_survive_an_unrelated_detail_with_matching_words(route_review_case):
    fixture = copy.deepcopy(route_review_case)
    route = fixture['sources'][1]['result']['data']['plan']
    # Lexical relevance alone cannot displace another array element's evidence.
    route['legs'][0]['details'] = [{
        'meters': 566, 'seconds': 453, 'description': 'origin destination route arrival departure'
    } for _ in range(10)]
    context = build_review_context(fixture['snapshot'], fixture['sources'])
    data = json.loads(context.messages[-1]['content'])
    values = visible_values(data['sources'][1])
    for index, leg in enumerate(route['legs']):
        assert values.get(f'/data/plan/legs/{index}/meters') == str(leg['meters'])
        assert values.get(f'/data/plan/legs/{index}/seconds') == str(leg['seconds'])
    assert context.estimated_input_tokens <= 12000
