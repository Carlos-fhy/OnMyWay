"""Bounded intent proposals. The phone confirms and executes mutations locally."""
import base64
import json
import os
import io
import wave
from uuid import UUID
from .harness import propose, status
from datetime import date, datetime
from typing import Literal

import httpx
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_serializer, model_validator

router = APIRouter()


class IntentRunStatus(BaseModel):
    request_id: UUID
    operation: Literal['chat', 'navigation']
    state: Literal['not_found', 'running', 'completed', 'failed', 'unresolved']
    result: dict | None = None
    error_status: int | None = None


def _request_status(body, operation, validate):
    result = status(str(body.request_id), operation,
                    _chat_payload(body) if operation == 'chat'
                    else body.model_dump(mode='json', exclude={'request_id'}))
    if result['state'] == 'completed':
        try:
            result['result'] = validate(result['result']).model_dump(mode='json')
        except HTTPException:
            # Corrupt or obsolete cache data must never become an executable proposal.
            result.update(state='unresolved', result=None, error_status=None)
    return result


class NavigationTask(BaseModel):
    id: str = Field(max_length=100)
    title: str = Field(max_length=100)
    place_type: str = Field(max_length=50)


class NavigationRequest(BaseModel):
    request_id: UUID | None = None
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


class NavigationStatusRequest(NavigationRequest):
    request_id: UUID


class NavigationIntent(BaseModel):
    model_config = ConfigDict(extra='forbid')
    action: Literal['adjust', 'clarify', 'add_stop']
    destination: str = Field(default='', max_length=100)
    keep_task_ids: list[str] = Field(default_factory=list, max_length=6)
    new_task_title: str = Field(default='', max_length=100)
    new_place_type: Literal['', 'PRINT_SHOP', 'SUPERMARKET', 'PHARMACY'] = ''
    reply: str = Field(default='', max_length=500)


@router.post('/assistant/navigation', response_model=NavigationIntent)
def navigation(body: NavigationRequest):
    if body.request_id is None:
        return _navigation(body)
    result = propose(str(body.request_id), "navigation",
                     body.model_dump(mode="json", exclude={"request_id"}),
                     lambda: _navigation(body).model_dump(mode="json"))
    return _validate_navigation(result, body)


@router.post('/assistant/navigation/status', response_model=IntentRunStatus)
def navigation_status(body: NavigationStatusRequest):
    return _request_status(body, 'navigation', lambda result: _validate_navigation(result, body))


def _navigation(body: NavigationRequest):
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
用户明确提出新增顺路事项，例如“附近买瓶水”“顺路找打印店”，action=add_stop；new_task_title 是要办的事，new_place_type 分别用 SUPERMARKET、PRINT_SHOP、PHARMACY。它是待确认的新事项，不能声称已加入路线或已保存。取快递、取外卖需要确切领取地点，不得用 add_stop 猜地点，应 clarify。
add_stop 可同时更换终点、保留或移除原路线事项：根据用户整句要求填 destination 和 keep_task_ids；没有明确更改原路线事项时继承 proposed_task_ids 或当前全部任务。例如“取完快递去上方山，中途买水”应保留快递、把上方山设为终点并新增买水，给出一份待确认方案。只有一个新事项可被提议；不明确时才 clarify。普通 adjust、clarify 的 new_task_title/new_place_type 必须留空。
回复应概述保留事项和终点；真实路线由程序试算，用户确认后才更新。'''
    content = completion([{'role': 'system', 'content': prompt},
                          {'role': 'user', 'content': body.model_dump_json()}],
                         os.getenv('AGENT_MODEL', 'qwen3.8-max'), True)
    return _validate_navigation(content, body)


def _validate_navigation(value, body: NavigationRequest):
    try:
        intent = (NavigationIntent.model_validate_json(value) if isinstance(value, str)
                  else NavigationIntent.model_validate(value))
        allowed = {task.id for task in body.tasks}
        if len(set(intent.keep_task_ids)) != len(intent.keep_task_ids) or not set(intent.keep_task_ids) <= allowed:
            raise ValueError('invalid task ids')
        if intent.action == 'add_stop':
            if not intent.new_task_title.strip() or not intent.new_place_type:
                raise ValueError('invalid new stop')
        elif intent.new_task_title or intent.new_place_type:
            raise ValueError('unexpected new stop')
        return intent
    except (ValueError, ValidationError):
        raise HTTPException(502, '导航调整结果未通过校验，请重新说一次。') from None


class Message(BaseModel):
    role: Literal['user', 'assistant']
    content: str = Field(max_length=4000)


PlaceType = Literal['PRINT_SHOP', 'SUPERMARKET', 'PHARMACY', 'PARCEL', 'TAKEOUT']


def _planned_date(value):
    if value and (len(value) != 10 or date.fromisoformat(value).isoformat() != value):
        raise ValueError('planned date must be YYYY-MM-DD')
    return value


def _deadline(value):
    if value and datetime.fromisoformat(value).utcoffset() is None:
        raise ValueError('timezone required')
    return value


class DraftTask(BaseModel):
    model_config = ConfigDict(extra='forbid')
    title: str = Field(min_length=1, max_length=100)
    place_type: PlaceType = 'SUPERMARKET'
    deadline: str = Field(default='', max_length=50)
    planned_date: str = Field(default='', max_length=10)
    priority: int = Field(default=2, strict=True, ge=1, le=3)

    @field_validator('title')
    @classmethod
    def nonblank_title(cls, value):
        if not value.strip():
            raise ValueError('missing title')
        return value

    _check_planned_date = field_validator('planned_date')(_planned_date)
    _check_deadline = field_validator('deadline')(_deadline)


class DraftItem(DraftTask):
    item_id: str = Field(min_length=1, max_length=100)
    version: int = Field(strict=True, ge=1, le=2**31 - 2)

    @field_validator('item_id')
    @classmethod
    def nonblank_identity(cls, value):
        if not value.strip():
            raise ValueError('missing draft identity')
        return value


class DraftUpdate(BaseModel):
    model_config = ConfigDict(extra='forbid')
    item_id: str = Field(min_length=1, max_length=100)
    expected_version: int = Field(strict=True, ge=1, le=2**31 - 2)
    title: str | None = Field(default=None, min_length=1, max_length=100)
    place_type: PlaceType | None = None
    deadline: str | None = Field(default=None, max_length=50)
    planned_date: str | None = Field(default=None, max_length=10)
    priority: int | None = Field(default=None, strict=True, ge=1, le=3)

    @model_validator(mode='before')
    @classmethod
    def require_patch(cls, value):
        if isinstance(value, dict):
            changed = set(value) - {'item_id', 'expected_version'}
            if not changed or any(value[key] is None for key in changed):
                raise ValueError('patch must supply non-null changes')
            if 'title' in value and isinstance(value['title'], str) and not value['title'].strip():
                raise ValueError('missing title')
        return value

    _check_planned_date = field_validator('planned_date')(_planned_date)
    _check_deadline = field_validator('deadline')(_deadline)

    @model_serializer(mode='wrap')
    def supplied_fields_only(self, handler):
        # Omitted patch fields must stay omitted in responses and persisted receipts.
        return {key: value for key, value in handler(self).items() if key in self.model_fields_set}


class ChatRequest(BaseModel):
    request_id: UUID | None = None
    messages: list[Message] = Field(min_length=1, max_length=20)
    now: datetime
    route_active: bool = False
    draft_items: list[DraftItem] = Field(default_factory=list, max_length=10)

    @field_validator('draft_items')
    @classmethod
    def unique_draft_ids(cls, items):
        if len({item.item_id for item in items}) != len(items):
            raise ValueError('duplicate draft identities')
        return items


class ChatStatusRequest(ChatRequest):
    request_id: UUID


class Proposal(BaseModel):
    model_config = ConfigDict(extra='forbid')
    action: Literal['reply', 'create_task', 'create_tasks', 'update_task_drafts', 'list_tasks', 'plan_route']
    reply: str = Field(max_length=1000)
    title: str = Field(default='', max_length=100)
    place_type: PlaceType = 'SUPERMARKET'
    deadline: str = Field(default='', max_length=50)
    planned_date: str = Field(default='', max_length=10)
    priority: int = Field(default=2, strict=True, ge=1, le=3)
    tasks: list[DraftTask] = Field(default_factory=list, max_length=10)
    updates: list[DraftUpdate] = Field(default_factory=list, max_length=10)
    destination: str = Field(default='', max_length=100)
    mode: Literal['', 'walking', 'cycling'] = ''

    _check_planned_date = field_validator('planned_date')(_planned_date)
    _check_deadline = field_validator('deadline')(_deadline)


def _chat_payload(body):
    payload = body.model_dump(mode='json', exclude={'request_id'})
    # Existing durable requests were fingerprinted before draft_items existed.
    if not body.draft_items:
        payload.pop('draft_items', None)
    return payload


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
    if body.request_id is None:
        return _chat(body)
    result = propose(str(body.request_id), "chat",
                     _chat_payload(body),
                     lambda: _chat(body).model_dump(mode="json"))
    return _validate_proposal(result, body)


@router.post('/assistant/chat/status', response_model=IntentRunStatus)
def chat_status(body: ChatStatusRequest):
    return _request_status(body, 'chat', lambda result: _validate_proposal(result, body))


def _chat(body: ChatRequest):
    schema = json.dumps(Proposal.model_json_schema(), ensure_ascii=False)
    prompt = f'''你是顺路生活助手。现在是{body.now.isoformat()}。当前正在导航={body.route_active}。历史对话中的旧路线不代表仍在导航；当前未导航时，新的出行请求必须重新规划，不能沿用旧路线或旧耗时。只创建待办而没有目的地时，保存后可请用户提供本次目的地。用简短中文回复，只输出符合以下 schema 的 JSON：{schema}。
你只能提出建议，不能声称已保存、完成或启动。新建待办使用 create_tasks，tasks 为 1 至 10 条草稿，单条也使用 create_tasks；用户逐项确认后手机才保存。create_task 仅用于兼容旧版单任务草稿。
手机可能在 messages 中提供“当前有未确认的待办草稿”及其 title/place_type/deadline JSON。这是尚未保存的当前字段，不是执行结果。用户修改这条草稿时，只修改本次明确指定的字段，其他字段原样保留；deadline 为空表示未设截止时间，不得从旧对话补回。信息不明确时先 reply 追问；修改草稿不代表确认保存。
结构化当前草稿 draft_items={json.dumps([item.model_dump(mode='json') for item in body.draft_items], ensure_ascii=False)}。这些是手机尚未保存的实际条目，以此为准，不得从旧对话恢复其他条目。每条 item_id/version 由手机生成，绝对不可生成或更换。
有 draft_items 时，只能使用 update_task_drafts 精确修改明确指向的条目或 reply 澄清，不能 create_task/create_tasks/plan_route 覆盖未确认草稿；用户要求出行或新增时，先提示确认或取消剩余草稿。update_task_drafts 的 updates 每条必须携带现有 item_id 和 expected_version=当前 version，只提交本次明确修改的字段，未提及字段不要输出（包括不要输出 null）；空字符串 deadline/planned_date 表示清除该日期。多条指代不明确时 reply 追问。修改不是确认保存。
多个新任务在同一 tasks 数组给出，不能合成一条；不清楚的信息先 reply 追问。不要将否定、取消、假设表达当成创建或导航指令。
仅支持打印、购物、买药、取快递、取外卖；其他类型先解释限制。取快递/外卖的地点必须由用户随后指定，不能猜测。
deadline 仅在用户明确表达截止时间且可确定时填含时区 ISO8601，否则留空并在需要时追问；不要把期望办理时间当截止时间。
planned_date 是计划办理日期，只有明确表达时才按当前 now 推算严格 YYYY-MM-DD（例如“今天取快递”），否则空字符串；它不改变 deadline。priority 只能为 1、2、3，1=普通，2=较重要，3=重要（最高），默认 2；未明确调整时保留原值。
list_tasks 用于查看待办，实际列表由手机读取。plan_route 必须有用户表达的目的地名称，不能生成坐标、路线或耗时。
家、宿舍、学校、公司及用户自定义常用地点（例如快递站、外卖点、东门驿站）都是合法目的地名称，原样放入 destination，手机会匹配常用地点或请用户搜索。
用户只说一个明确地点名称（例如“示例大学东校区图书馆”）也按 plan_route 处理，保留完整名称。不要要求用户先手动定位或勾选待办，手机会自动定位并计算顺路建议。
mode 仅在本次用户明确说步行/走路或骑行/骑自行车时填写 walking/cycling，否则必须留空，由手机询问出行方式，不沿用旧对话的方式。
例如“我要回宿舍”返回 action=plan_route,destination=宿舍,mode=""；“骑车去图书馆”返回 mode=cycling。如果需要追问而无法生成有效操作，action 必须是 reply，不能返回空字段的 create_task/plan_route。
对话中的确认不代表此前工具执行成功；以手机给出的真实执行结果为准。当前不支持通过对话删除或完成任务，提示去待办操作。'''
    content = completion([{'role': 'system', 'content': prompt}] + [m.model_dump() for m in body.messages],
                         os.getenv('AGENT_MODEL', 'qwen3.8-max'), True)
    return _validate_proposal(content, body)


def _validate_proposal(value, body: ChatRequest | None = None):
    try:
        proposal = (Proposal.model_validate_json(value) if isinstance(value, str)
                    else Proposal.model_validate(value))
        if proposal.action == 'create_task' and not proposal.title.strip():
            raise ValueError('missing title')
        if proposal.action == 'create_tasks' and not proposal.tasks:
            raise ValueError('missing tasks')
        if proposal.action != 'create_tasks' and proposal.tasks:
            raise ValueError('unexpected tasks')
        if proposal.action != 'update_task_drafts' and proposal.updates:
            raise ValueError('unexpected draft updates')
        if body and body.draft_items and proposal.action in ('create_task', 'create_tasks', 'plan_route'):
            raise ValueError('cannot replace pending drafts')
        if proposal.action == 'update_task_drafts':
            available = {item.item_id: item.version for item in body.draft_items} if body else {}
            identities = [update.item_id for update in proposal.updates]
            if not identities or len(set(identities)) != len(identities):
                raise ValueError('missing or duplicate draft updates')
            if any(available.get(update.item_id) != update.expected_version for update in proposal.updates):
                raise ValueError('unknown or stale draft update')
        if proposal.action == 'plan_route' and not proposal.destination.strip():
            raise ValueError('missing destination')
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
