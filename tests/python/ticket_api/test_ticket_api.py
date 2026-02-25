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
HTTP_TIMEOUT_SECONDS = int(os.getenv("AGENTX_TICKET_HTTP_TIMEOUT_SECONDS", "20"))
EVENT_WAIT_SECONDS = int(os.getenv("AGENTX_TICKET_EVENT_WAIT_SECONDS", "30"))
STATUS_WAIT_SECONDS = int(os.getenv("AGENTX_TICKET_STATUS_WAIT_SECONDS", "30"))
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


def request_json(method, path, payload=None):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
        method=method,
        data=body,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_SECONDS) as resp:
            data = resp.read().decode("utf-8")
            return resp.status, json.loads(data) if data else None
    except urllib.error.HTTPError as e:
        data = e.read().decode("utf-8")
        return e.code, json.loads(data) if data else None


def get_json(path, params=None):
    full_path = path
    if params:
        full_path += "?" + urllib.parse.urlencode(params)
    return request_json("GET", full_path)


def post_json(path, payload=None):
    return request_json("POST", path, payload)


def wait_for_ticket_event(ticket_id, event_type, timeout_seconds=EVENT_WAIT_SECONDS):
    deadline = time.time() + timeout_seconds
    latest_events = []
    while time.time() < deadline:
        status, events = get_json(f"/api/v0/tickets/{ticket_id}/events")
        if status != 200:
            time.sleep(0.2)
            continue
        latest_events = events or []
        match = next((e for e in latest_events if e.get("event_type") == event_type), None)
        if match is not None:
            return match, latest_events
        time.sleep(0.2)
    return None, latest_events


def wait_for_ticket_status(session_id, ticket_id, expected_status, timeout_seconds=STATUS_WAIT_SECONDS):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        status, tickets = get_json(f"/api/v0/sessions/{session_id}/tickets")
        if status != 200:
            time.sleep(0.2)
            continue
        for ticket in tickets or []:
            if ticket.get("ticket_id") == ticket_id and ticket.get("status") == expected_status:
                return ticket
        time.sleep(0.2)
    return None


class TicketApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        run_mysql_file(SCHEMA_PATH)

    def setUp(self):
        self.session_id = f"SES-TK-{uuid.uuid4().hex}"
        run_mysql(
            "use {db}; "
            "insert into sessions(session_id, title, status, created_at, updated_at) "
            "values('{sid}', 'ticket test session', 'ACTIVE', now(), now());".format(
                db=DB_NAME,
                sid=self.session_id,
            )
        )

    def test_single_ticket_lifecycle_for_architect_request_and_close(self):
        status, created = post_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {
                "type": "DECISION",
                "title": "decision flow",
                "created_by_role": "requirement_agent",
                "assignee_role": "architect_agent",
                "payload_json": "{\"kind\":\"handoff_packet\"}",
            },
        )
        self.assertEqual(200, status)
        self.assertEqual("OPEN", created["status"])
        ticket_id = created["ticket_id"]

        status, claimed = post_json(
            f"/api/v0/tickets/{ticket_id}/claim",
            {"claimed_by": "architect-agent-instance", "lease_seconds": 120},
        )
        self.assertEqual(200, status)
        self.assertEqual("IN_PROGRESS", claimed["status"])

        status, event = post_json(
            f"/api/v0/tickets/{ticket_id}/events",
            {
                "event_type": "DECISION_REQUESTED",
                "actor_role": "architect_agent",
                "body": "Need user choose architecture path",
                "data_json": (
                    "{\"question\":\"Sync or async processing?\","
                    "\"options\":[\"OPT-A\",\"OPT-B\"],"
                    "\"source_ticket_id\":\"%s\"}" % ticket_id
                ),
            },
        )
        self.assertEqual(200, status)
        self.assertEqual("DECISION_REQUESTED", event["event_type"])

        status, inbox = get_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {
                "status": "WAITING_USER",
                "assignee_role": "architect_agent",
                "type": "DECISION",
            },
        )
        self.assertEqual(200, status)
        self.assertEqual(1, len(inbox))
        self.assertEqual(ticket_id, inbox[0]["ticket_id"])
        self.assertEqual("WAITING_USER", inbox[0]["status"])

        status, event2 = post_json(
            f"/api/v0/tickets/{ticket_id}/events",
            {
                "event_type": "USER_RESPONDED",
                "actor_role": "user",
                "body": "choose A",
                "data_json": "{\"selected\":\"A\"}",
            },
        )
        self.assertEqual(200, status)
        self.assertEqual("USER_RESPONDED", event2["event_type"])

        status, resumed = get_json(f"/api/v0/sessions/{self.session_id}/tickets", {"status": "IN_PROGRESS"})
        self.assertEqual(200, status)
        self.assertEqual(1, len(resumed))
        self.assertEqual(ticket_id, resumed[0]["ticket_id"])

        status, closed_event = post_json(
            f"/api/v0/tickets/{ticket_id}/events",
            {
                "event_type": "STATUS_CHANGED",
                "actor_role": "architect_agent",
                "body": "architecture review completed",
                "data_json": "{\"to_status\":\"DONE\",\"reason\":\"decision applied\"}",
            },
        )
        self.assertEqual(200, status)
        self.assertEqual("STATUS_CHANGED", closed_event["event_type"])

        status, done_tickets = get_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {"status": "DONE", "assignee_role": "architect_agent", "type": "DECISION"},
        )
        self.assertEqual(200, status)
        self.assertEqual(1, len(done_tickets))
        self.assertEqual(ticket_id, done_tickets[0]["ticket_id"])

    def test_create_ticket_invalid_assignee_returns_bad_request(self):
        status, err = post_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {
                "type": "DECISION",
                "title": "bad assignee",
                "created_by_role": "architect_agent",
                "assignee_role": "user",
                "payload_json": "{}",
            },
        )
        self.assertEqual(400, status)
        self.assertEqual("BAD_REQUEST", err["code"])

    def test_list_tickets_should_support_type_and_assignee_filters(self):
        status, t1 = post_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {
                "type": "DECISION",
                "title": "decision task",
                "created_by_role": "requirement_agent",
                "assignee_role": "architect_agent",
                "payload_json": "{}",
            },
        )
        self.assertEqual(200, status)
        status, t2 = post_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {
                "type": "CLARIFICATION",
                "title": "clarification needed",
                "created_by_role": "architect_agent",
                "assignee_role": "requirement_agent",
                "payload_json": "{}",
            },
        )
        self.assertEqual(200, status)

        status, filtered = get_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {"status": "OPEN", "assignee_role": "architect_agent", "type": "DECISION"},
        )
        self.assertEqual(200, status)
        self.assertEqual(1, len(filtered))
        self.assertEqual(t1["ticket_id"], filtered[0]["ticket_id"])

        status, filtered2 = get_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {"status": "OPEN", "assignee_role": "requirement_agent", "type": "CLARIFICATION"},
        )
        self.assertEqual(200, status)
        self.assertEqual(1, len(filtered2))
        self.assertEqual(t2["ticket_id"], filtered2[0]["ticket_id"])

    def test_status_changed_should_reject_illegal_transition(self):
        status, created = post_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {
                "type": "DECISION",
                "title": "illegal transition test",
                "created_by_role": "requirement_agent",
                "assignee_role": "architect_agent",
                "payload_json": "{}",
            },
        )
        self.assertEqual(200, status)
        ticket_id = created["ticket_id"]

        status, err = post_json(
            f"/api/v0/tickets/{ticket_id}/events",
            {
                "event_type": "STATUS_CHANGED",
                "actor_role": "architect_agent",
                "body": "try close directly from OPEN",
                "data_json": "{\"to_status\":\"DONE\"}",
            },
        )
        self.assertEqual(409, status)
        self.assertEqual("CONFLICT", err["code"])

    def test_claim_terminal_ticket_returns_conflict(self):
        status, created = post_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {
                "type": "DECISION",
                "title": "terminal test",
                "created_by_role": "requirement_agent",
                "assignee_role": "architect_agent",
                "payload_json": "{}",
            },
        )
        self.assertEqual(200, status)
        ticket_id = created["ticket_id"]

        run_mysql(
            "use {db}; update tickets set status='DONE', updated_at=now() "
            "where ticket_id='{tid}';".format(db=DB_NAME, tid=ticket_id)
        )

        status, err = post_json(
            f"/api/v0/tickets/{ticket_id}/claim",
            {"claimed_by": "agent-1"},
        )
        self.assertEqual(409, status)
        self.assertEqual("CONFLICT", err["code"])

    def test_architect_auto_processor_generates_decision_and_clarification(self):
        status, handoff = post_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {
                "type": "HANDOFF",
                "title": "handoff for architecture tradeoff",
                "created_by_role": "requirement_agent",
                "assignee_role": "architect_agent",
                "payload_json": "{\"kind\":\"handoff_packet\",\"trigger\":\"ARCHITECTURE_CHANGE_REQUESTED\"}",
            },
        )
        self.assertEqual(200, status)

        status, arch_review = post_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {
                "type": "ARCH_REVIEW",
                "title": "review confirmed requirement baseline",
                "created_by_role": "requirement_agent",
                "assignee_role": "architect_agent",
                "payload_json": "{\"kind\":\"handoff_packet\",\"trigger\":\"REQUIREMENT_CONFIRMED\"}",
            },
        )
        self.assertEqual(200, status)

        status, auto = post_json(
            "/api/v0/architect/auto-process",
            {"session_id": self.session_id, "max_tickets": 8},
        )
        self.assertEqual(200, status)
        self.assertIn("processed_count", auto)

        handoff_request, _ = wait_for_ticket_event(handoff["ticket_id"], "DECISION_REQUESTED")
        self.assertIsNotNone(handoff_request)
        handoff_data = json.loads(handoff_request["data_json"])
        self.assertIn(handoff_data["request_kind"], {"DECISION", "CLARIFICATION"})

        arch_request, _ = wait_for_ticket_event(arch_review["ticket_id"], "DECISION_REQUESTED")
        self.assertIsNotNone(arch_request)
        arch_data = json.loads(arch_request["data_json"])
        self.assertIn(arch_data["request_kind"], {"DECISION", "CLARIFICATION"})

        provider = os.getenv("AGENTX_REQUIREMENT_LLM_PROVIDER", "").strip().lower()
        if provider == "mock":
            self.assertEqual("DECISION", handoff_data["request_kind"])
            self.assertEqual("CLARIFICATION", arch_data["request_kind"])

        handoff_waiting = wait_for_ticket_status(self.session_id, handoff["ticket_id"], "WAITING_USER")
        arch_waiting = wait_for_ticket_status(self.session_id, arch_review["ticket_id"], "WAITING_USER")
        self.assertIsNotNone(handoff_waiting)
        self.assertIsNotNone(arch_waiting)


if __name__ == "__main__":
    unittest.main(verbosity=2)

