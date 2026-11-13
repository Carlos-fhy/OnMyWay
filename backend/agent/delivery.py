"""Bounded delivery constraints supplied by a task owner, never by tool output."""
import json
import re

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError


class DeliveryError(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def delivery_contract(constraints):
    if "delivery_contract" not in constraints:
        return {}
    contract = constraints["delivery_contract"]
    def invalid():
        raise DeliveryError("invalid_delivery_contract", "交付约束无效：仅支持kind、content_schema、max_artifacts及completion_mode，Schema限有界结构约束。")
    if not isinstance(contract, dict) or set(contract) - {"kind", "content_schema", "completion_mode", "max_artifacts"}:
        invalid()
    if "max_artifacts" in contract and (type(contract["max_artifacts"]) is not int or not 1 <= contract["max_artifacts"] <= 8):
        invalid()
    if "kind" in contract and (not isinstance(contract["kind"], str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,59}", contract["kind"])):
        invalid()
    if "completion_mode" in contract and contract["completion_mode"] not in ("deliver", "await_user"):
        invalid()
    try:
        if len(json.dumps(contract, ensure_ascii=False, allow_nan=False).encode("utf-8")) > 8192:
            invalid()
        if "content_schema" in contract:
            schema = contract["content_schema"]
            allowed = {"type", "properties", "required", "additionalProperties", "items", "minItems", "maxItems",
                       "uniqueItems", "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum",
                       "exclusiveMaximum", "enum", "const", "description", "title"}
            def inspect(node, depth=0):
                if depth > 12 or not isinstance(node, (bool, dict)):
                    invalid()
                if isinstance(node, bool):
                    return
                if set(node) - allowed:
                    invalid()
                for child in node.get("properties", {}).values():
                    inspect(child, depth + 1)
                for key in ("items", "additionalProperties"):
                    if key in node:
                        inspect(node[key], depth + 1)
            Draft202012Validator.check_schema(schema)
            inspect(schema)
    except (SchemaError, TypeError, ValueError, RecursionError, UnicodeError):
        invalid()
    return contract


def validate_artifact(constraints, kind, content):
    contract = delivery_contract(constraints)
    if "kind" in contract and kind != contract["kind"]:
        raise DeliveryError("artifact_contract_mismatch", f"成果kind必须为{contract['kind']}；请按当前交付约束重新发布。")
    if "content_schema" in contract:
        value = content.get("content") if isinstance(content, dict) else None
        error = next(Draft202012Validator(contract["content_schema"]).iter_errors(value), None)
        if error is not None:
            path = "/".join(str(part).replace("~", "~0").replace("/", "~1") for part in error.absolute_path)
            if error.validator == "type":
                expected = error.validator_value
                expected = [expected] if isinstance(expected, str) else expected
                actual = {dict: "object", list: "array", str: "string", bool: "boolean", int: "integer",
                          float: "number", type(None): "null"}.get(type(error.instance), "unknown")
                hint = " Supply the structured JSON value directly, without stringifying it." if (
                    actual == "string" and any(kind in expected for kind in ("object", "array"))) else ""
                raise DeliveryError("artifact_contract_mismatch",
                    f"content/{path}: expected {' or '.join(expected)}, received {actual}.{hint} Not saved.")
            raise DeliveryError("artifact_contract_mismatch", f"成果content/{path}不满足{error.validator}约束；请按任务content_schema修正，未保存。")


def validate_completion(constraints):
    if delivery_contract(constraints).get("completion_mode") == "await_user":
        raise DeliveryError("user_decision_required", "当前任务要求等待用户决定；保留准备成果并使用ask_user，不得finish。用户变更要求时以新修订更新约束。")


def validate_question(constraints, need, missing_inputs):
    """Check explicit task contracts; natural-language necessity still needs evaluation."""
    contract = delivery_contract(constraints)
    if not contract or contract.get("completion_mode") == "await_user":
        return
    if need != "missing_input" or not missing_inputs:
        raise DeliveryError("missing_input_required", "当前要求直接交付成果，不要再次询问是否交付。若确有必要缺项，使用need=missing_input和missing_inputs（相对task.constraints的JSON Pointer:具体影响）。已有事实请直接发布，可选偏好说明假设。")
    for path, impact in missing_inputs.items():
        if not path.startswith("/") or re.search(r"~(?![01])", path) or not impact.strip() or len(path)>240 or len(impact)>300:
            raise DeliveryError("invalid_missing_input", "缺项必须使用有效JSON Pointer（最多240字符）及非空必要性说明（最多300字符）。")
        tokens = [part.replace("~1", "/").replace("~0", "~") for part in path[1:].split("/")]
        if any(not part for part in tokens) or tokens[0] in {"delivery_contract", "constraints", "task"}:
            raise DeliveryError("invalid_missing_input", "不能将交付契约或空路径作为缺失输入，请按当前目标完成交付。")
        value = constraints
        for part in tokens:
            if isinstance(value, dict):
                if part not in value:
                    value = None
                    break
                value = value[part]
            elif isinstance(value, list) and part.isdigit() and int(part) < len(value):
                value = value[int(part)]
            else:
                raise DeliveryError("invalid_missing_input", "缺项路径不能穿过已有标量或无效数组位置。")
        if value is not None and not (isinstance(value, str) and not value.strip()):
            raise DeliveryError("input_already_supplied", f"{path}已在当前约束中提供，不能重复确认；同时检查goal及user_input中的已有答案。")
