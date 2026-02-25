#!/usr/bin/env python3
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE_URL = os.getenv("AGENTX_BASE_URL", "http://127.0.0.1:8080")
HTTP_TIMEOUT_SECONDS = int(os.getenv("AGENTX_HTTP_TIMEOUT_SECONDS", "150"))


def request(method, path, payload=None):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
        method=method,
        data=body,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_SECONDS) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        parsed = json.loads(raw) if raw else {}
        return e.code, parsed


def main():
    status, session = request(
        "POST",
        "/api/v0/sessions",
        {"title": f"real llm requirement test {int(time.time() * 1000)}"},
    )
    if status != 200:
        print("Create session failed:", status)
        print(json.dumps(session, ensure_ascii=False, indent=2))
        return 1
    session_id = session["session_id"]

    status, result = request(
        "POST",
        f"/api/v0/sessions/{session_id}/requirement-agent/drafts",
        {
            "title": "Customer Notification Workflow",
            "user_input": (
                "确认需求。背景：我们要替换人工短信流程，目标用户是电商运营和客服。"
                "目标是完善客户通知流程，范围包括订单创建/支付/发货/失败重试通知，"
                "不包含营销消息，验收标准是核心通知到达率>=99%，通知延迟<60秒。"
            ),
            "persist": True,
        },
    )
    if status != 201:
        print("Requirement-agent call failed:", status)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1
    if result.get("provider") != "bailian":
        print("Expected provider=bailian but got:", result.get("provider"))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1
    if result.get("phase") != "DRAFT_CREATED":
        print("Expected phase=DRAFT_CREATED but got:", result.get("phase"))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1

    print("Requirement-agent real API call succeeded.")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
