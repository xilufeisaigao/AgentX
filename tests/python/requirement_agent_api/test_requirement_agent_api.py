#!/usr/bin/env python3
import json
import os
import subprocess
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE_URL = os.getenv("AGENTX_BASE_URL", "http://127.0.0.1:8080")
DB_HOST = os.getenv("AGENTX_DB_HOST", "127.0.0.1")
DB_PORT = os.getenv("AGENTX_DB_PORT", "3306")
DB_NAME = os.getenv("AGENTX_DB_NAME", "agentx_backend")
DB_USER = os.getenv("AGENTX_DB_USERNAME", "root")
DB_PASSWORD = os.getenv("AGENTX_DB_PASSWORD", "")
HTTP_TIMEOUT_SECONDS = int(os.getenv("AGENTX_REQUIREMENT_AGENT_HTTP_TIMEOUT_SECONDS", "180"))
DECISION_WAIT_SECONDS = int(os.getenv("AGENTX_REQUIREMENT_AGENT_DECISION_WAIT_SECONDS", "90"))
DONE_WAIT_SECONDS = int(os.getenv("AGENTX_REQUIREMENT_AGENT_DONE_WAIT_SECONDS", "90"))
SCHEMA_PATH = os.path.join(
    os.path.dirname(__file__), "..", "requirement_api", "bootstrap_requirement_schema.sql"
)


def mysql_base_cmd():
    cmd = [
        "mysql",
        "-h", DB_HOST,
        "-P", DB_PORT,
        "-u", DB_USER,
    ]
    # When password is empty, do not pass "-p" which would prompt and hang in CI.
    if DB_PASSWORD:
        cmd.append(f"-p{DB_PASSWORD}")
    return cmd


def run_mysql(sql, check=True):
    cmd = mysql_base_cmd() + ["-e", sql]
    subprocess.run(cmd, check=check, capture_output=True, text=True)


def run_mysql_file(path):
    cmd = mysql_base_cmd()
    with open(path, "r", encoding="utf-8") as f:
        subprocess.run(cmd, check=True, input=f.read(), text=True, capture_output=True)


def query_single_value(sql):
    cmd = mysql_base_cmd() + ["-N", "-B", "-e", sql]
    result = subprocess.run(cmd, check=True, capture_output=True, text=True)
    return result.stdout.strip()


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
        return e.code, json.loads(raw) if raw else None


def post(path, payload=None):
    return request("POST", path, payload)


def get(path, params=None):
    full_path = path
    if params:
        full_path += "?" + urllib.parse.urlencode(params)
    return request("GET", full_path)


def build_valid_markdown(title_suffix, user_input, change_log):
    return f"""---
schema_version: req_doc_v1
---

# Requirement {title_suffix}

## 1. Summary
Summary for {user_input}

## 2. Goals
- [G-1] Deliver user value for: {user_input}

## 3. Non-Goals
- [NG-1] Do not include architecture implementation details.

## 4. Scope
### In
- [S-IN-1] Value-layer requirements in this iteration.
### Out
- [S-OUT-1] Architecture and infra decisions.

## 5. Acceptance Criteria
- [AC-1] Stakeholders can validate expected business outcome.

## 6. Value Constraints
- [VC-1] Must keep auditability and explicit confirmation gates.

## 7. Risks & Tradeoffs
- [R-1] Scope change may increase iteration cost.

## 8. Open Questions
- [Q-1][OPEN] Any unresolved acceptance detail?

## 9. References
### Decisions
- [DEC-TBD] None.
### ADRs
- [ADR-TBD] None.

## 10. Change Log
- v1: {change_log}
"""


class RequirementAgentApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        run_mysql_file(SCHEMA_PATH)

    def setUp(self):
        self.session_id = f"SES-RA-{uuid.uuid4().hex}"
        run_mysql(
            "use {db}; "
            "insert into sessions(session_id, title, status, created_at, updated_at) "
            "values('{sid}', 'requirement-agent test session', 'ACTIVE', now(), now());".format(
                db=DB_NAME,
                sid=self.session_id,
            )
        )

    def test_discovery_chat_should_not_persist_doc(self):
        status, resp = post(
            f"/api/v0/sessions/{self.session_id}/requirement-agent/drafts",
            {
                "title": "Order Fulfillment",
                "user_input": "你好，我们先聊需求方向",
                "persist": True,
            },
        )
        self.assertEqual(200, status)
        self.assertFalse(resp["persisted"])
        self.assertEqual("DISCOVERY_CHAT", resp["phase"])
        self.assertIsNone(resp["doc_id"])
        self.assertIsNone(resp["content"])
        self.assertFalse(resp["ready_to_draft"])

        count = query_single_value(
            "use {db}; select count(*) from requirement_docs where session_id='{sid}';".format(
                db=DB_NAME, sid=self.session_id
            )
        )
        self.assertEqual("0", count)

    def test_architecture_change_should_create_handoff_ticket(self):
        status, resp = post(
            f"/api/v0/sessions/{self.session_id}/requirement-agent/drafts",
            {
                "title": "Order Fulfillment",
                "user_input": "请给我数据库分库分表和微服务拆分方案",
                "persist": True,
            },
        )
        self.assertEqual(200, status)
        self.assertFalse(resp["persisted"])
        self.assertEqual("HANDOFF_CREATED", resp["phase"])

        handoff_count = query_single_value(
            "use {db}; select count(*) from tickets where session_id='{sid}' and type='HANDOFF';".format(
                db=DB_NAME, sid=self.session_id
            )
        )
        self.assertEqual("1", handoff_count)

        handoff_event_count = query_single_value(
            "use {db}; "
            "select count(*) "
            "from ticket_events e "
            "join tickets t on e.ticket_id=t.ticket_id "
            "where t.session_id='{sid}' "
            "  and t.type='HANDOFF' "
            "  and e.event_type='COMMENT' "
            "  and e.actor_role='requirement_agent';".format(
                db=DB_NAME,
                sid=self.session_id,
            )
        )
        self.assertEqual("1", handoff_event_count)

    def test_generate_new_draft_and_persist(self):
        request_payloads = [
            {
                "title": "Order Fulfillment",
                "user_input": (
                    "确认需求。目标是提升订单履约效率，范围包括下单通知和物流通知，"
                    "不包含营销触达，验收标准是通知成功率达到99%。"
                ),
                "persist": True,
            },
            {
                "title": "Order Fulfillment",
                "user_input": (
                    "补充事实：通知渠道=短信+APP推送；触发状态=下单成功、发货、签收；"
                    "接收角色=下单用户；时效要求=触发后30秒内送达。确认需求并生成草稿。"
                ),
                "persist": True,
            },
            {
                "title": "Order Fulfillment",
                "user_input": (
                    "再次确认需求：生成并落库需求文档。"
                    "目标=提升履约效率；范围=下单/发货/签收通知；"
                    "非范围=营销触达；验收=通知成功率>=99%，平均发送延迟<=30秒。"
                ),
                "persist": True,
            },
        ]
        status = 0
        resp = None
        for payload in request_payloads:
            status, resp = post(
                f"/api/v0/sessions/{self.session_id}/requirement-agent/drafts",
                payload,
            )
            if status == 201:
                break
        self.assertEqual(201, status)
        self.assertTrue(resp["persisted"])
        self.assertIn(resp["provider"], {"mock", "bailian"})
        self.assertEqual("qwen3.5-plus-2026-02-15", resp["model"])
        self.assertEqual(1, resp["version"])
        self.assertEqual("IN_REVIEW", resp["status"])
        self.assertIn("schema_version: req_doc_v1", resp["content"])
        self.assertEqual("DRAFT_CREATED", resp["phase"])

        count = query_single_value(
            "use {db}; select count(*) from requirement_docs where session_id='{sid}';".format(
                db=DB_NAME, sid=self.session_id
            )
        )
        self.assertEqual("1", count)

    def test_generate_revision_for_existing_doc(self):
        status, doc = post(
            f"/api/v0/sessions/{self.session_id}/requirement-docs",
            {"title": "Inventory Management"},
        )
        self.assertEqual(201, status)
        doc_id = doc["doc_id"]

        status, _ = post(
            f"/api/v0/requirement-docs/{doc_id}/versions",
            {
                "content": build_valid_markdown(
                    "Inventory Management",
                    "initial requirement input",
                    "initial version",
                ),
                "created_by_role": "user",
            },
        )
        self.assertEqual(201, status)

        status, resp = post(
            f"/api/v0/sessions/{self.session_id}/requirement-agent/drafts",
            {
                "doc_id": doc_id,
                "user_input": "Need reservation logic for cross-warehouse transfers",
                "persist": True,
            },
        )
        self.assertEqual(201, status)
        self.assertEqual(doc_id, resp["doc_id"])
        self.assertEqual(2, resp["version"])
        self.assertEqual("IN_REVIEW", resp["status"])
        self.assertEqual("DRAFT_REVISED", resp["phase"])

    def test_generate_draft_dry_run_should_not_persist(self):
        payloads = [
            {
                "title": "Billing Aggregation",
                "user_input": (
                    "确认需求。目标是月度账单汇总导出，范围包括CSV导出，不包含财务结算，"
                    "验收标准是10万条数据导出在30秒内完成。"
                ),
                "persist": False,
            },
            {
                "title": "Billing Aggregation",
                "user_input": (
                    "补充事实：导出格式=CSV；字段=账单号、用户号、账期、金额、状态；"
                    "角色=财务运营；导出触发=手动；时效=10万条30秒内。请生成草稿。"
                ),
                "persist": False,
            },
            {
                "title": "Billing Aggregation",
                "user_input": "信息已齐全，请立即生成需求文档草稿。",
                "persist": False,
            },
        ]
        status = 0
        resp = None
        for payload in payloads:
            status, resp = post(
                f"/api/v0/sessions/{self.session_id}/requirement-agent/drafts",
                payload,
            )
            if status == 200 and (resp or {}).get("phase") == "DRAFT_CREATED":
                break
        self.assertEqual(200, status)
        self.assertFalse(resp["persisted"])
        self.assertIsNone(resp["doc_id"])
        self.assertIsNone(resp["version"])
        self.assertEqual("DRAFT_CREATED", resp["phase"])
        self.assertIn("schema_version: req_doc_v1", resp["content"])

        count = query_single_value(
            "use {db}; select count(*) from requirement_docs where session_id='{sid}';".format(
                db=DB_NAME, sid=self.session_id
            )
        )
        self.assertEqual("0", count)

    def test_confirm_then_architect_should_auto_plan_modules_and_tasks(self):
        request_payloads = [
            {
                "title": "Order Center MVP",
                "user_input": (
                    "确认需求。目标是降低人工处理成本，范围包括下单、支付状态回传、订单查询，"
                    "不包含推荐系统，验收标准是100并发下成功率>=99%且响应<300ms。"
                ),
                "persist": True,
            },
            {
                "title": "Order Center MVP",
                "user_input": (
                    "补充事实：用户角色=商场运营；核心字段=订单号、订单描述、订单状态；"
                    "状态流转=待支付->已支付->已完成/已取消；请直接生成并落库需求草稿。"
                ),
                "persist": True,
            },
            {
                "title": "Order Center MVP",
                "user_input": (
                    "信息已齐全，确认需求并生成文档草稿。"
                    "目标=降低人工成本；范围=下单/支付回传/订单查询；"
                    "非范围=推荐系统；验收=100并发成功率>=99%，平均响应<300ms。"
                ),
                "persist": True,
            },
        ]

        status = 0
        draft = None
        for payload in request_payloads:
            status, draft = post(
                f"/api/v0/sessions/{self.session_id}/requirement-agent/drafts",
                payload,
            )
            if status == 201 and draft and draft.get("persisted"):
                break

        self.assertEqual(201, status)
        doc_id = draft["doc_id"]
        self.assertTrue(doc_id)

        status, confirmed = post(f"/api/v0/requirement-docs/{doc_id}/confirm")
        self.assertEqual(200, status)
        self.assertEqual("CONFIRMED", confirmed["status"])

        ticket_id = ""
        decision_seen = False
        waiting_deadline = time.time() + DECISION_WAIT_SECONDS
        while time.time() < waiting_deadline:
            ticket_id = query_single_value(
                "use {db}; "
                "select ticket_id from tickets "
                "where session_id='{sid}' and type='ARCH_REVIEW' "
                "order by created_at desc limit 1;".format(
                    db=DB_NAME,
                    sid=self.session_id,
                )
            )
            if ticket_id:
                status, events = get(f"/api/v0/tickets/{ticket_id}/events")
                if status == 200 and any(e.get("event_type") == "DECISION_REQUESTED" for e in (events or [])):
                    decision_seen = True
                    break
            time.sleep(0.5)
        self.assertTrue(ticket_id, "ARCH_REVIEW ticket was not created in time.")
        ticket_status = query_single_value(
            "use {db}; select status from tickets where ticket_id='{tid}';".format(
                db=DB_NAME,
                tid=ticket_id,
            )
        )
        self.assertIn(ticket_status, {"WAITING_USER", "IN_PROGRESS"})

        status, events = get(f"/api/v0/tickets/{ticket_id}/events")
        self.assertEqual(200, status)
        self.assertTrue(
            decision_seen or any(e.get("event_type") == "DECISION_REQUESTED" for e in (events or [])),
            "DECISION_REQUESTED not found within {}s, ticket_status={}, events={}".format(
                DECISION_WAIT_SECONDS,
                ticket_status,
                [e.get("event_type") for e in (events or [])],
            ),
        )

        status, _ = post(
            f"/api/v0/tickets/{ticket_id}/events",
            {
                "event_type": "USER_RESPONDED",
                "actor_role": "user",
                "body": "先按低风险方案推进，直接拆分任务开干。",
                "data_json": "{\"selected\":\"LOW_RISK\"}",
            },
        )
        self.assertEqual(200, status)

        done_deadline = time.time() + DONE_WAIT_SECONDS
        while time.time() < done_deadline:
            current_status = query_single_value(
                "use {db}; "
                "select status from tickets where ticket_id='{tid}';".format(
                    db=DB_NAME,
                    tid=ticket_id,
                )
            )
            if current_status == "DONE":
                break
            time.sleep(0.5)
        final_status = query_single_value(
            "use {db}; "
            "select status from tickets where ticket_id='{tid}';".format(
                db=DB_NAME,
                tid=ticket_id,
            )
        )
        self.assertEqual("DONE", final_status)

        module_count = query_single_value(
            "use {db}; "
            "select count(*) from work_modules where session_id='{sid}';".format(
                db=DB_NAME,
                sid=self.session_id,
            )
        )
        task_count = query_single_value(
            "use {db}; "
            "select count(*) from work_tasks t "
            "join work_modules m on t.module_id=m.module_id "
            "where m.session_id='{sid}';".format(
                db=DB_NAME,
                sid=self.session_id,
            )
        )
        self.assertTrue(int(module_count) >= 1)
        self.assertTrue(int(task_count) >= 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)


