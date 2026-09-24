"""Bounded intent proposals. The phone confirms and executes mutations locally."""
import base64
import json
import os
import io
import wave
from datetime import datetime
from typing import Literal

import httpx
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field, ValidationError

router = APIRouter()


class NavigationTask(BaseModel):
    id: str = Field(max_length=100)
    title: str = Field(max_length=100)
    place_type: str = Field(max_length=50)


class NavigationRequest(BaseModel):
    text: str = Field(min_length=1, max_length=4000)
    destination: str = Field(max_length=200)
    tasks: list[NavigationTask] = Field(max_length=6)
    history: list['NavigationTurn'] = Field(default_factory=list, max_length=12)
    proposed_destination: str = Field(default='', max_length=200)
    proposed_task_ids: list[str] | None = Field(default=None, max_length=6)


class NavigationTurn(BaseModel):
    role: Literal['user', 'assistant']
    content: str = Field(max_length=4000)


NavigationRequest.model_rebuild()


class NavigationIntent(BaseModel):
    model_config = ConfigDict(extra='forbid')
    action: Literal['adjust', 'clarify']
    destination: str = Field(default='', max_length=100)
    keep_task_ids: list[str] = Field(default_factory=list, max_length=6)
    reply: str = Field(default='', max_length=500)


@router.post('/assistant/navigation', response_model=NavigationIntent)
def navigation(body: NavigationRequest):
    schema = json.dumps(NavigationIntent.model_json_schema(), ensure_ascii=False)
    prompt = f'''你理解正在导航中的路线调整。只输出符合 schema 的 JSON：{schema}。
输入提供当前终点和当前未完成任务。任务 id 只能从输入选择。不要创建任务、标记完成、编造地点或坐标。
history 是本次行程的近期对话，不代表已执行。proposed_destination/proposed_task_ids 是尚未确认的最新方案。
有未确认方案时，“那就不打印了”“还是去那里”等后续指令应在该方案上修改；保留没有被用户修改的终点和事项。destination 留空时手机继承未确认方案终点，无方案才继承当前终点。
可根据历史中的追问理解简短回答；指代不唯一时 clarify，不猜测。当前 tasks 为真实可用任务，历史不能恢复已不存在的任务。
keep_task_ids 是调整后还要经过的任务，不是已完成任务。destination 留空表示保留当前终点。
“取完快递直接回宿舍”表示未来先取快递再回宿舍：保留所有 PARCEL 任务，移除其他途经任务，destination=宿舍，action=adjust。
“打印完再回家”同理保留 PRINT_SHOP，destination=家。“不取快递了”移除 PARCEL，其他任务保留。
仅“改去图书馆”时更换终点，保留全部当前任务。“直接回宿舍”“直接去目的地”移除全部途经任务。
家、宿舍、学校、公司是可使用的预设地点名称，无需追问地址，手机会解析。
若要求的任务不在当前任务中、要求新增事项、否定或假设表达无法明确执行，则 action=clarify，reply 简短说明，不擅自改成直达。
回复应概述保留事项和终点；真实路线由程序试算，用户确认后才更新。'''
    content = completion([{'role': 'system', 'content': prompt},
                          {'role': 'user', 'content': body.model_dump_json()}],
                         os.getenv('AGENT_MODEL', 'qwen3.8-max'), True)
    try:
        intent = NavigationIntent.model_validate_json(content)
        allowed = {task.id for task in body.tasks}
        if len(set(intent.keep_task_ids)) != len(intent.keep_task_ids) or not set(intent.keep_task_ids) <= allowed:
            raise ValueError('invalid task ids')
        return intent
    except (ValueError, ValidationError):
        raise HTTPException(502, '导航调整结果未通过校验，请重新说一次。') from None


class Message(BaseModel):
    role: Literal['user', 'assistant']
    content: str = Field(max_length=4000)


class ChatRequest(BaseModel):
    messages: list[Message] = Field(min_length=1, max_length=20)
    now: datetime


class Proposal(BaseModel):
    model_config = ConfigDict(extra='forbid')
    action: Literal['reply', 'create_task', 'list_tasks', 'plan_route']
    reply: str = Field(max_length=1000)
    title: str = Field(default='', max_length=100)
    place_type: Literal['PRINT_SHOP', 'SUPERMARKET', 'PHARMACY', 'PARCEL', 'TAKEOUT'] = 'SUPERMARKET'
    deadline: str = Field(default='', max_length=50)
    destination: str = Field(default='', max_length=100)
    mode: Literal['', 'walking', 'cycling'] = ''


def completion(messages, model, structured=False):
    key = os.getenv('DASHSCOPE_API_KEY', '').strip()
    base = os.getenv('DASHSCOPE_BASE_URL', '').strip().rstrip('/')
    if not key or not base:
        raise HTTPException(503, '请在 backend/.env 配置百炼 Key 和服务地址。')
    if base.endswith('/api/v1'):
        base = base[:-len('/api/v1')] + '/compatible-mode/v1'
    body = dict(model=model, messages=messages, stream=False)
    if structured:
        body.update(response_format={'type': 'json_object'}, enable_thinking=False)
    try:
        response = httpx.post(base + '/chat/completions', headers={'Authorization': f'Bearer {key}'},
                              json=body, timeout=45)
        if response.status_code != 200:
            raise HTTPException(502, f'模型请求失败（HTTP {response.status_code}），请核对模型权限和服务地址。')
        content = response.json()['choices'][0]['message']['content']
        if isinstance(content, list):
            content = ''.join(part.get('text', '') for part in content if isinstance(part, dict))
        if not isinstance(content, str) or not content.strip():
            raise ValueError('empty result')
        return content
    except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError):
        raise HTTPException(502, '模型服务暂不可用，请稍后重试。') from None


@router.post('/assistant/chat', response_model=Proposal)
def chat(body: ChatRequest):
    schema = json.dumps(Proposal.model_json_schema(), ensure_ascii=False)
    prompt = f'''你是顺路生活助手。现在是{body.now.isoformat()}。用简短中文回复，只输出符合以下 schema 的 JSON：{schema}。
你只能提出建议，不能声称已保存、完成或启动。create_task 每次只提出一个任务草稿，用户确认后手机才保存。
多个任务应逐个确认；不清楚的信息先 reply 追问。不要将否定、取消、假设表达当成创建或导航指令。
仅支持打印、购物、买药、取快递、取外卖；其他类型先解释限制。取快递/外卖的地点必须由用户随后指定，不能猜测。
deadline 仅在用户明确表达截止时间且可确定时填含时区 ISO8601，否则留空并在需要时追问；不要把期望办理时间当截止时间。
list_tasks 用于查看待办，实际列表由手机读取。plan_route 必须有用户表达的目的地名称，不能生成坐标、路线或耗时。
家、宿舍、学校、公司也都是合法目的地名称，原样放入 destination，手机会匹配常用地点或请用户搜索。
用户只说一个明确地点名称（例如“苏州科技大学石湖校区图书馆”）也按 plan_route 处理，保留完整名称。不要要求用户先手动定位或勾选待办，手机会自动定位并计算顺路建议。
mode 仅在本次用户明确说步行/走路或骑行/骑自行车时填写 walking/cycling，否则必须留空，由手机询问出行方式，不沿用旧对话的方式。
例如“我要回宿舍”返回 action=plan_route,destination=宿舍,mode=""；“骑车去图书馆”返回 mode=cycling。如果需要追问而无法生成有效操作，action 必须是 reply，不能返回空字段的 create_task/plan_route。
对话中的确认不代表此前工具执行成功；以手机给出的真实执行结果为准。当前不支持通过对话删除或完成任务，提示去待办操作。'''
    content = completion([{'role': 'system', 'content': prompt}] + [m.model_dump() for m in body.messages],
                         os.getenv('AGENT_MODEL', 'qwen3.8-max'), True)
    try:
        proposal = Proposal.model_validate_json(content)
        if proposal.action == 'create_task' and not proposal.title.strip():
            raise ValueError('missing title')
        if proposal.action == 'plan_route' and not proposal.destination.strip():
            raise ValueError('missing destination')
        if proposal.deadline:
            stamp = datetime.fromisoformat(proposal.deadline)
            if stamp.utcoffset() is None:
                raise ValueError('timezone required')
        return proposal
    except (ValidationError, ValueError):
        raise HTTPException(502, '助手返回的操作未通过校验，请换一种说法。') from None


class AudioRequest(BaseModel):
    audio: str = Field(min_length=1, max_length=3_000_000)


@router.post('/assistant/transcribe')
def transcribe(body: AudioRequest):
    try:
        raw = base64.b64decode(body.audio, validate=True)
        if len(raw) > 2_000_000:
            raise ValueError('audio too large')
        with wave.open(io.BytesIO(raw), 'rb') as audio:
            if audio.getnchannels() != 1 or audio.getsampwidth() != 2 or audio.getframerate() != 16000 or not 0 < audio.getnframes() <= 480000:
                raise ValueError('unsupported audio')
    except (ValueError, wave.Error, EOFError):
        raise HTTPException(422, '录音无效或过大，请重新录制。') from None
    key = os.getenv('DASHSCOPE_API_KEY', '').strip()
    base = os.getenv('DASHSCOPE_BASE_URL', '').strip().rstrip('/')
    if not key or not base:
        raise HTTPException(503, '请配置语音 Key 和服务地址。')
    root = base.removesuffix('/compatible-mode/v1').removesuffix('/api/v1')
    try:
        response = httpx.post(root + '/api/v1/services/aigc/multimodal-generation/generation',
            headers={'Authorization': f'Bearer {key}', 'X-DashScope-SSE': 'disable'},
            json={'model': os.getenv('VOICE_ASR_MODEL', 'qwen-audio-3.0-asr-flash'),
                  'input': {'messages': [{'role': 'user', 'content': [{'type': 'input_audio', 'input_audio': {
                      'data': 'data:audio/wav;base64,' + body.audio}}]}]},
                  'parameters': {'format': 'wav', 'sample_rate': 16000}}, timeout=45)
        if response.status_code != 200:
            raise HTTPException(502, f'语音接口请求失败（HTTP {response.status_code}），请检查语音模型权限。')
        data = response.json()
        output = data.get('output', {})
        text = data.get('sentence', {}).get('text') or output.get('text') or output.get('output', {}).get('sentence', {}).get('text')
        if not isinstance(text, str) or not text.strip():
            raise HTTPException(422, '没有识别到有效语音，请重试。')
    except (httpx.HTTPError, ValueError, TypeError, AttributeError):
        raise HTTPException(502, '语音服务暂不可用，请稍后重试。') from None
    return {'text': text[:4000]}
