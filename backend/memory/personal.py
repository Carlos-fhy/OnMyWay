"""Single-user, versioned personal memory with evidence and task-time retrieval.

The authenticated device owns permissions and business actions. This service owns
memory state; derived summaries never become independent evidence or authority.
"""
from backend.paths import DATA_DIR

import hashlib
import json
import math
import os
import re
import sqlite3
import time
from collections import Counter
from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from backend.data_services.backup import _authorize
from backend.memory.legacy import CLAUSE_BOUNDARY, FORGET, NEGATED_FORGET, NO_REMEMBER, SENSITIVE, STABLE, TEMPORARY


router = APIRouter(prefix="/memory", tags=["personal-memory"])
SCHEMA_VERSION = 1
MemoryKind = Literal["preference", "context", "episode", "insight"]
Topic = Literal["general", "travel", "printing", "food", "errand"]
Scope = Literal["personal", "session", "trip"]
Origin = Literal["manual", "conversation", "legacy", "event", "derived"]


class MemoryDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, allow_inf_nan=False)
    id: str = Field(default="", max_length=160)
    revision: int | None = Field(default=None, strict=True, ge=1)
    kind: MemoryKind = "preference"
    text: str = Field(min_length=1, max_length=1000)
    topic: Topic = "general"
    scope: Scope = "personal"
    scope_id: str = Field(default="", max_length=160)
    valid_from: datetime | None = None
    valid_until: datetime | None = None
    source_kind: Origin = "manual"
    source_id: str = Field(default="", max_length=200)
    quote: str = Field(default="", max_length=4000)
    derived_from: list[str] = Field(default_factory=list, max_length=100)

    @field_validator("valid_from", "valid_until")
    @classmethod
    def aware(cls, value):
        if value is not None and value.utcoffset() is None:
            raise ValueError("记忆时间需要包含时区")
        return value

    @model_validator(mode="after")
    def bounded_context(self):
        if self.scope != "personal" and not self.scope_id:
            raise ValueError("会话或行程记忆需要范围编号")
        if self.scope == "personal" and self.scope_id:
            raise ValueError("个人记忆不使用会话或行程编号")
        if self.kind == "context" and self.valid_until is None:
            raise ValueError("临时情境必须有明确有效期")
        if self.valid_from and self.valid_until and self.valid_from >= self.valid_until:
            raise ValueError("记忆有效期必须晚于开始时间")
        return self


class MemoryCommand(BaseModel):
    model_config = ConfigDict(extra="forbid")
    operation_id: UUID
    store_id: str = Field(min_length=1, max_length=100)
    expected_revision: int = Field(strict=True, ge=0)
    operation: Literal["upsert", "forget", "settings", "import", "reflect"]
    item: MemoryDraft | None = None
    id: str = Field(default="", max_length=160)
    item_revision: int | None = Field(default=None, strict=True, ge=1)
    reference_enabled: bool | None = Field(default=None, strict=True)
    learning_enabled: bool | None = Field(default=None, strict=True)
    items: list[MemoryDraft] = Field(default_factory=list, max_length=500)


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _digest(value):
    return hashlib.sha256(_json(value).encode("utf-8")).hexdigest()


def _time(value=None):
    value = value or datetime.now(timezone.utc)
    if isinstance(value, str):
        value = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if value.utcoffset() is None:
        raise ValueError("时间需要包含时区")
    return value


def _stamp(value=None):
    return _time(value).isoformat()


def _open():
    path = Path(os.getenv("ONMYWAY_MEMORY_DB", "").strip() or
                DATA_DIR / "personal_memory.sqlite3")
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path, timeout=10)
    db.row_factory = sqlite3.Row
    try:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA busy_timeout=10000")
        with db:
            db.execute("CREATE TABLE IF NOT EXISTS personal_memory_schema (id INTEGER PRIMARY KEY CHECK(id=1), version INTEGER NOT NULL)")
            version = db.execute("SELECT version FROM personal_memory_schema WHERE id=1").fetchone()
            if version is not None and version[0] != SCHEMA_VERSION:
                raise HTTPException(409, "记忆数据库版本不兼容，请升级服务")
            db.execute("INSERT OR IGNORE INTO personal_memory_schema VALUES (1, ?)", (SCHEMA_VERSION,))
            db.execute("""CREATE TABLE IF NOT EXISTS personal_memory_state (
                id INTEGER PRIMARY KEY CHECK(id=1), store_id TEXT NOT NULL,
                revision INTEGER NOT NULL, reference_enabled INTEGER NOT NULL,
                learning_enabled INTEGER NOT NULL, learning_started_at REAL NOT NULL DEFAULT 0)""")
            columns = {row[1] for row in db.execute("PRAGMA table_info(personal_memory_state)")}
            if "learning_started_at" not in columns:
                db.execute("ALTER TABLE personal_memory_state ADD COLUMN learning_started_at REAL NOT NULL DEFAULT 0")
                db.execute("UPDATE personal_memory_state SET learning_started_at=?", (time.time(),))
            db.execute("""INSERT OR IGNORE INTO personal_memory_state
                (id,store_id,revision,reference_enabled,learning_enabled,learning_started_at)
                VALUES(1, ?, 0, 1, 0, ?)""", (str(uuid4()), time.time()))
            db.execute("""CREATE TABLE IF NOT EXISTS personal_memories (
                id TEXT PRIMARY KEY, revision INTEGER NOT NULL,
                status TEXT NOT NULL, data TEXT NOT NULL)""")
            db.execute("""CREATE TABLE IF NOT EXISTS personal_memory_receipts (
                operation_id TEXT PRIMARY KEY, digest TEXT NOT NULL,
                applied_ids TEXT NOT NULL, rejected_count INTEGER NOT NULL)""")
        return db
    except BaseException:
        db.close()
        raise


def _items(db):
    return [dict(json.loads(row["data"]), id=row["id"], revision=row["revision"], status=row["status"])
            for row in db.execute("SELECT * FROM personal_memories ORDER BY id")]


def _snapshot(db):
    state = db.execute("SELECT * FROM personal_memory_state WHERE id=1").fetchone()
    return dict(store_id=state["store_id"], revision=state["revision"],
                reference_enabled=bool(state["reference_enabled"]),
                learning_enabled=bool(state["learning_enabled"]), items=_items(db))


def read_snapshot():
    with closing(_open()) as db:
        db.execute("BEGIN")
        return _snapshot(db)


@router.get("")
def get_memory(x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    from backend.execution import events
    drain = getattr(events, "project_pending_memories", None)
    if drain is not None:
        try:
            drain(limit=50)
        except (sqlite3.Error, OSError, HTTPException):
            pass
    return read_snapshot()


def _begin_operation(db, operation_id, digest, store_id, expected_revision):
    db.execute("BEGIN IMMEDIATE")
    receipt = db.execute("SELECT * FROM personal_memory_receipts WHERE operation_id=?", (str(operation_id),)).fetchone()
    if receipt:
        if receipt["digest"] != digest:
            raise HTTPException(409, "记忆操作编号已用于不同内容")
        return dict(status="duplicate", snapshot=_snapshot(db),
                    applied_ids=json.loads(receipt["applied_ids"]), rejected_count=receipt["rejected_count"])
    state = db.execute("SELECT * FROM personal_memory_state WHERE id=1").fetchone()
    if state["store_id"] != store_id or state["revision"] != expected_revision:
        raise HTTPException(409, "记忆已发生变化，请刷新后重试")
    return None


def _finish(db, operation_id, digest, applied, rejected=0, changed=False):
    if changed or applied:
        db.execute("UPDATE personal_memory_state SET revision=revision+1 WHERE id=1")
    db.execute("INSERT INTO personal_memory_receipts VALUES(?, ?, ?, ?)",
               (str(operation_id), digest, _json(applied), rejected))
    result = dict(status="applied", snapshot=_snapshot(db), applied_ids=applied, rejected_count=rejected)
    db.commit()
    return result


def _invalidate(db, source_id, *, deleted=False, root_status=None, at=None):
    """Erase derived text as well as indexable source text, retaining only tombstones."""
    pending, visited = [source_id], set()
    while pending:
        current = pending.pop()
        if current in visited:
            continue
        visited.add(current)
        for item in _items(db):
            if current in item.get("derived_from", []) and item["status"] == "active":
                pending.append(item["id"])
        if current == source_id and not deleted and root_status is None:
            continue
        row = db.execute("SELECT * FROM personal_memories WHERE id=?", (current,)).fetchone()
        if row is None:
            continue
        data = json.loads(row["data"])
        data.update(text="", quote="", source_id="", derived_from=[], updated_at=_stamp(at))
        db.execute("UPDATE personal_memories SET revision=revision+1,status=?,data=? WHERE id=?",
                   ((root_status or "deleted") if current == source_id else "invalidated", _json(data), current))


def _write(db, item, *, at=None, allow_deleted=False):
    item = item if isinstance(item, MemoryDraft) else MemoryDraft.model_validate(item)
    identity = item.id or str(uuid4())
    row = db.execute("SELECT * FROM personal_memories WHERE id=?", (identity,)).fetchone()
    if row and row["status"] != "active" and not allow_deleted:
        raise HTTPException(409, "该记忆已失效；重新添加请使用新编号")
    data = item.model_dump(mode="json", exclude={"id", "revision"})
    data.update(created_at=json.loads(row["data"])["created_at"] if row else _stamp(at), updated_at=_stamp(at))
    if row:
        _invalidate(db, identity, at=at)
        db.execute("UPDATE personal_memories SET revision=revision+1,status='active',data=? WHERE id=?", (_json(data), identity))
    else:
        db.execute("INSERT INTO personal_memories VALUES (?,1,'active',?)", (identity, _json(data)))
    return identity


def _reflect(db, *, at=None):
    groups = {}
    for item in _items(db):
        if item["kind"] == "episode" and item["status"] == "active":
            groups.setdefault((item["scope"], item["scope_id"]), []).append(item)
    result = []
    for (scope, scope_id), episodes in groups.items():
        episodes = sorted(episodes, key=lambda item: (item["valid_from"] or item["created_at"], item["id"]))[-12:]
        identity = "summary-" + _digest([(item["id"], item["revision"]) for item in episodes])[:32]
        if db.execute("SELECT 1 FROM personal_memories WHERE id=?", (identity,)).fetchone():
            continue
        for old in _items(db):
            if (old["kind"] == "insight" and old["status"] == "active" and old["source_id"] == "event-summary-v1"
                    and old["scope"] == scope and old["scope_id"] == scope_id):
                _invalidate(db, old["id"], root_status="invalidated", at=at)
        summary = "行程事件摘要（按来源记录，不推断未收到的执行结果）：" + "；".join(item["text"] for item in episodes)
        result.append(_write(db, dict(id=identity, kind="insight", text=summary[:1000], topic="travel",
            scope=scope, scope_id=scope_id, source_kind="derived", source_id="event-summary-v1",
            valid_from=max(_time(item["valid_from"] or item["created_at"]) for item in episodes),
            derived_from=[item["id"] for item in episodes]), at=at))
    return result


@router.post("/commands")
def command(body: MemoryCommand, x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    digest = _digest(body.model_dump(mode="json"))
    with closing(_open()) as db:
        try:
            previous = _begin_operation(db, body.operation_id, digest, body.store_id, body.expected_revision)
            if previous:
                return previous
            applied, rejected, changed = [], 0, False
            if body.operation == "upsert":
                item = body.item
                if item is None or item.source_kind != "manual" or item.kind not in {"preference", "context"} or item.derived_from:
                    raise HTTPException(422, "手动保存只接受明确偏好或临时情境，不能伪造事件或推断来源")
                existing = next((old for old in _items(db) if old["id"] == item.id), None)
                if existing and existing["kind"] in {"episode", "insight"}:
                    raise HTTPException(409, "事件与派生记忆不能改写为用户偏好；可以忘记或另添明确说明")
                if existing and item.revision != existing["revision"]:
                    raise HTTPException(409, "记忆条目已变化，请重新打开编辑")
                if SENSITIVE.search(item.text) or SENSITIVE.search(item.quote):
                    raise HTTPException(422, "请勿保存凭据或敏感编号")
                applied.append(_write(db, item.model_copy(update=dict(quote=item.text, source_id="manual"))))
            elif body.operation == "forget":
                row = db.execute("SELECT status,revision FROM personal_memories WHERE id=?", (body.id,)).fetchone()
                if row is None:
                    raise HTTPException(404, "记忆不存在")
                if body.item_revision is not None and body.item_revision != row["revision"]:
                    raise HTTPException(409, "记忆条目已变化，请刷新后再忘记")
                if row["status"] != "deleted":
                    _invalidate(db, body.id, deleted=True)
                    applied.append(body.id)
            elif body.operation == "settings":
                if body.reference_enabled is None and body.learning_enabled is None:
                    raise HTTPException(422, "请提供需要修改的开关")
                before = db.execute("SELECT reference_enabled,learning_enabled FROM personal_memory_state WHERE id=1").fetchone()
                for name in ("reference_enabled", "learning_enabled"):
                    value = getattr(body, name)
                    if value is not None:
                        db.execute(f"UPDATE personal_memory_state SET {name}=? WHERE id=1", (int(value),))
                after = db.execute("SELECT reference_enabled,learning_enabled FROM personal_memory_state WHERE id=1").fetchone()
                if not all(before) and all(after):
                    db.execute("UPDATE personal_memory_state SET learning_started_at=? WHERE id=1", (time.time(),))
                changed = True
            elif body.operation == "import":
                for item in body.items:
                    if not item.id or item.source_kind != "legacy" or item.kind not in {"preference", "context"} or item.derived_from:
                        raise HTTPException(422, "导入需要稳定编号及 legacy 来源")
                    if db.execute("SELECT 1 FROM personal_memories WHERE id=?", (item.id,)).fetchone():
                        rejected += 1
                        continue
                    if SENSITIVE.search(item.text) or SENSITIVE.search(item.quote):
                        rejected += 1
                        continue
                    applied.append(_write(db, item))
            else:
                applied.extend(_reflect(db))
            return _finish(db, body.operation_id, digest, applied, rejected, changed)
        except BaseException:
            db.rollback()
            raise


def _quoted(quote, source):
    return bool(quote) and (quote == source.strip() or quote in [part.strip() for part in CLAUSE_BOUNDARY.split(source) if part.strip()])


def _sensitive_source(source, items):
    # Known memory IDs are addressing data, not credentials. Only exact, bounded
    # IDs from the authority may bypass the conservative long-token detector.
    for item in items:
        source = re.sub(r"(?<![A-Za-z0-9_-])" + re.escape(item["id"]) + r"(?![A-Za-z0-9_-])", "memory-reference", source)
    return SENSITIVE.search(source)


def _context_interval(quote, at, session_id, trip_id):
    midnight = at.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=1)
    if re.search(r"这周|本周", quote):
        return "personal", "", midnight + timedelta(days=6 - at.weekday())
    days = re.search(r"(?:未来|接下来)([1-9]|[12][0-9]|30)天", quote)
    if days:
        return "personal", "", at + timedelta(days=int(days[1]))
    if session_id:
        return "session", session_id, midnight
    if trip_id:
        return "trip", trip_id, midnight
    return None


def apply_conversation(updates, *, operation_id, store_id, expected_revision, source_text,
                       source_id, at, session_id="", trip_id=""):
    at = _time(at)
    updates = updates if isinstance(updates, list) else []
    digest = _digest(dict(updates=updates, store_id=store_id, expected_revision=expected_revision,
        source_text=source_text, source_id=source_id, at=_stamp(at), session_id=session_id, trip_id=trip_id))
    with closing(_open()) as db:
        try:
            previous = _begin_operation(db, operation_id, digest, store_id, expected_revision)
            if previous:
                return previous
            state = _snapshot(db)
            if not state["reference_enabled"] or not state["learning_enabled"] or NO_REMEMBER.search(source_text) or _sensitive_source(source_text, state["items"]):
                return _finish(db, operation_id, digest, [], len(updates))
            applied, rejected, changed_targets = [], 0, set()
            for update in updates[:16]:
                try:
                    if not isinstance(update, dict):
                        raise ValueError("invalid candidate")
                    op = update.get("op")
                    quote = str(update.get("quote", "")).strip()
                    text = str(update.get("text", "")).strip()
                    kind = update.get("kind", "preference")
                    if op not in {"add", "update", "forget"} or not _quoted(quote, source_text) or len(quote) > 1000:
                        raise ValueError("source not anchored")
                    target_id = str(update.get("id", ""))
                    target = next((item for item in state["items"] if item["id"] == target_id and item["status"] == "active"), None)
                    if op != "add":
                        mentioned = [item for item in state["items"] if item["status"] == "active" and
                            (item["text"] in quote or re.search(r"(?<![A-Za-z0-9_-])" + re.escape(item["id"]) + r"(?![A-Za-z0-9_-])", quote))]
                        if target is None or len(mentioned) != 1 or mentioned[0]["id"] != target_id or target_id in changed_targets:
                            raise ValueError("ambiguous target")
                    if op == "forget":
                        if not FORGET.search(quote) or NEGATED_FORGET.search(source_text):
                            raise ValueError("no forget intent")
                        _invalidate(db, target_id, deleted=True, at=at)
                        applied.append(target_id)
                        changed_targets.add(target_id)
                        continue
                    if FORGET.search(source_text) or text != quote or kind not in {"preference", "context"}:
                        raise ValueError("invalid learned content")
                    if kind == "preference":
                        if not STABLE.search(quote) or TEMPORARY.search(quote) or re.search(r"这周|本周|未来\d+天|接下来\d+天", quote):
                            raise ValueError("not a durable preference")
                        scope, scope_id, expiry = "personal", "", None
                    else:
                        if re.search(r"(?:取消|不再|不|别|不要|不用|先不|先别).{0,8}(?:打印|取件|取快递|任务|导航)|(?:完成|删除|删掉).{0,8}(?:任务|待办)", quote):
                            raise ValueError("business mutation is not personal context")
                        if not TEMPORARY.search(quote) and not re.search(r"这周|本周|未来\d+天|接下来\d+天", quote):
                            raise ValueError("no explicit temporary scope")
                        interval = _context_interval(quote, at, session_id, trip_id)
                        if interval is None:
                            raise ValueError("missing temporary scope")
                        scope, scope_id, expiry = interval
                    if op == "add":
                        if target_id or any(item["status"] == "active" and item["text"] == text and item["scope"] == scope and item["scope_id"] == scope_id for item in _items(db)):
                            raise ValueError("duplicate add")
                    elif target["kind"] != kind:
                        raise ValueError("cannot silently change memory kind")
                    item = MemoryDraft(id=target_id, kind=kind, text=text, topic=update.get("topic", "general"),
                        scope=scope, scope_id=scope_id, valid_from=at if kind == "context" else None,
                        valid_until=expiry, source_kind="conversation", source_id=source_id, quote=quote)
                    identity = _write(db, item, at=at)
                    applied.append(identity)
                    changed_targets.add(identity)
                except (ValueError, TypeError):
                    rejected += 1
            rejected += max(0, len(updates) - 16)
            return _finish(db, operation_id, digest, applied, rejected)
        except BaseException:
            db.rollback()
            raise


_EVENT_TEXT = {
    "TASK_CONFIRMED": "已收到任务确认记录，任务是否完成需另查完成记录",
    "TASK_COMPLETED": "设备上报用户确认的任务完成记录",
    "ROUTE_CONFIRMED": "已收到路线确认记录，尚不代表导航已执行",
    "NAVIGATION_STARTED": "设备上报导航开始",
    "NAVIGATION_ARRIVED": "设备上报导航到达",
    "NAVIGATION_CANCELLED": "设备上报导航取消",
    "NAVIGATION_FAILED": "设备上报导航失败",
}


def record_episode(event):
    """Call only after authenticated ingestion of an accepted business event."""
    kind = event.get("kind")
    if kind not in _EVENT_TEXT:
        raise ValueError("unsupported business event")
    event_id = str(UUID(str(event["event_id"])))
    at = _time(event["occurred_at"])
    identity = "event-" + event_id
    payload = event.get("payload") or {}
    trip_id = str(payload.get("route_session_id") or event.get("correlation_id") or "")[:160]
    details = []
    title_key = "task_title" if "task_title" in payload else "title"
    detail_fields = [("destination_name", "终点")]
    if kind in {"TASK_CONFIRMED", "TASK_COMPLETED"}:
        detail_fields.insert(0, (title_key, "事项"))
    for key, label in detail_fields:
        value = payload.get(key)
        if isinstance(value, str) and value.strip() and len(value) <= 100 and not SENSITIVE.search(value):
            details.append(label + "=" + value.strip())
    event_text = _EVENT_TEXT[kind] + ("；" + "；".join(details) if details else "")
    with closing(_open()) as db:
        try:
            db.execute("BEGIN IMMEDIATE")
            old = next((item for item in _items(db) if item["id"] == identity), None)
            if old is not None:
                return old
            state = db.execute("SELECT reference_enabled,learning_enabled,learning_started_at FROM personal_memory_state WHERE id=1").fetchone()
            if not state["reference_enabled"] or not state["learning_enabled"]:
                return {"id": identity, "status": "not_recorded", "reason": "learning_disabled"}
            received_at = event.get("received_at", time.time())
            if type(received_at) not in (int, float) or not math.isfinite(received_at):
                return {"id": identity, "status": "not_recorded", "reason": "unknown_acceptance_time"}
            if received_at < state["learning_started_at"]:
                return {"id": identity, "status": "not_recorded", "reason": "before_current_learning_period"}
            item = MemoryDraft(id=identity, kind="episode", text=event_text, topic="travel",
                scope="trip" if trip_id else "personal", scope_id=trip_id, valid_from=at,
                source_kind="event", source_id=event_id, quote=event_text)
            _write(db, item, at=at)
            db.execute("UPDATE personal_memory_state SET revision=revision+1 WHERE id=1")
            result = next(item for item in _items(db) if item["id"] == identity)
            db.commit()
            return result
        except BaseException:
            db.rollback()
            raise


_TOPICS = {
    "travel": re.compile(r"步行|走路|少走|暴走|骑行|骑车|出行|路线|导航|行程|宿舍|顺路|目的地|赶时间|取快递|打印论文|walk|travel|route|bike", re.I),
    "printing": re.compile(r"打印|复印|论文|print", re.I),
    "food": re.compile(r"吃|餐|饮食|饭|food|meal", re.I),
    "errand": re.compile(r"快递|办事|买药|超市|采购|errand|parcel", re.I),
}
_SYNONYMS = [("步行", "走路", "暴走", "少走", "走太远", "walk"),
             ("骑行", "骑车", "自行车", "bike"), ("打印", "复印", "论文", "print"),
             ("快递", "取件", "包裹", "parcel")]


def _tokens(text):
    text = text.lower()
    tokens = re.findall(r"[a-z0-9]+", text)
    for run in re.findall(r"[\u3400-\u9fff]+", text):
        tokens.extend(run[i:i + 2] for i in range(max(1, len(run) - 1)))
    for index, words in enumerate(_SYNONYMS):
        if any(word in text for word in words):
            tokens.append("synonym:" + str(index))
    return Counter(tokens)


def recall(query, *, at, session_id="", trip_id="", limit=12, budget=1800):
    at = _time(at)
    limit, budget = max(0, min(int(limit), 50)), max(0, min(int(budget), 20000))
    snapshot = read_snapshot()
    result = dict(store_id=snapshot["store_id"], revision=snapshot["revision"], items=[], evidence_ids=[],
                  context_chars=0, diagnostics=dict(candidate_count=0, eligible_count=0, budget_skipped=0,
                  method="bm25-chinese-bigram-topic-v1", budget_unit="characters"))
    if not snapshot["reference_enabled"]:
        result["diagnostics"]["disabled"] = True
        return result
    eligible = []
    for item in snapshot["items"]:
        if item["status"] != "active" or not item["text"]:
            continue
        if item["scope"] == "session" and item["scope_id"] != session_id:
            continue
        if item["scope"] == "trip" and item["scope_id"] != trip_id and item["kind"] not in {"episode", "insight"}:
            continue
        if item["valid_from"] and at < _time(item["valid_from"]):
            continue
        if item["valid_until"] and at >= _time(item["valid_until"]):
            continue
        eligible.append(item)
    result["diagnostics"]["eligible_count"] = len(eligible)
    docs = [_tokens(item["text"]) for item in eligible]
    average = sum(sum(doc.values()) for doc in docs) / max(1, len(docs)) or 1
    query_tokens = _tokens(query)
    query_topics = {name for name, pattern in _TOPICS.items() if pattern.search(query)}
    frequencies = Counter(token for doc in docs for token in doc)
    ranked = []
    for item, doc in zip(eligible, docs):
        score = 0.0
        length = sum(doc.values())
        for term in query_tokens:
            tf = doc[term]
            if tf:
                idf = math.log(1 + (len(docs) - frequencies[term] + 0.5) / (frequencies[term] + 0.5))
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * length / average))
        if item["topic"] in query_topics:
            score += 1.5
        if item["kind"] == "context" and query_topics and item["topic"] in query_topics:
            score += 2.0
        if item["topic"] == "general" and item["kind"] == "preference":
            score += 0.15
        if score > 0:
            ranked.append((score, item))
    ranked.sort(key=lambda pair: (-pair[0], pair[1]["id"]))
    result["diagnostics"]["candidate_count"] = len(ranked)
    for score, item in ranked:
        if len(result["items"]) >= limit:
            break
        provided = dict(item, retrieval_score=round(score, 6),
                        historical=item["kind"] in {"episode", "insight"} and item["scope_id"] != trip_id)
        cost = len(_json(result["items"] + [provided]))
        if cost > budget:
            result["diagnostics"]["budget_skipped"] += 1
            continue
        result["items"].append(provided)
        result["evidence_ids"].append(item["id"])
        result["context_chars"] = cost
    return result
