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
HTTP_TIMEOUT_SECONDS = int(os.getenv("AGENTX_ARCH_AUTO_HTTP_TIMEOUT_SECONDS", "20"))
EVENT_WAIT_SECONDS = int(os.getenv("AGENTX_ARCH_AUTO_EVENT_WAIT_SECONDS", "120"))
STATUS_WAIT_SECONDS = int(os.getenv("AGENTX_ARCH_AUTO_STATUS_WAIT_SECONDS", "180"))
DONE_WAIT_SECONDS = int(os.getenv("AGENTX_ARCH_AUTO_DONE_WAIT_SECONDS", "240"))
SCHEMA_PATH = os.path.join(
    os.path.dirname(__file__), "..", "requirement_api", "bootstrap_requirement_schema.sql"
)


def mysql_base_cmd(extra_args=None):
    cmd = ["mysql"]
    if extra_args:
        cmd.extend(extra_args)
    cmd.extend([
        "-h", DB_HOST,
        "-P", DB_PORT,
        "-u", DB_USER,
    ])
    # When password is empty, do not pass "-p" which would prompt and hang in CI.
    if DB_PASSWORD:
        cmd.append(f"-p{DB_PASSWORD}")
    return cmd


def run_mysql(sql, check=True):
    cmd = mysql_base_cmd() + ["-e", sql]
    subprocess.run(cmd, check=check, capture_output=True, text=True)


def run_mysql_scalar(sql):
    cmd = mysql_base_cmd(["-N", "-s"]) + ["-e", sql]
    result = subprocess.run(cmd, check=True, capture_output=True, text=True)
    return (result.stdout or "").strip()


def run_mysql_file(path):
    cmd = mysql_base_cmd()
    with open(path, "r", encoding="utf-8") as f:
        subprocess.run(cmd, check=True, input=f.read(), text=True, capture_output=True)


def request_json(method, path, payload=None, params=None, timeout=HTTP_TIMEOUT_SECONDS):
    full_path = path
    if params:
        full_path += "?" + urllib.parse.urlencode(params)
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + full_path,
        method=method,
        data=body,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        return e.code, json.loads(raw) if raw else None


def post_json(path, payload=None):
    return request_json("POST", path, payload)


def get_json(path, params=None):
    return request_json("GET", path, params=params)


def wait_for_event_count(ticket_id, event_type, expected_count, timeout_seconds=EVENT_WAIT_SECONDS):
    deadline = time.time() + timeout_seconds
    latest_events = []
    while time.time() < deadline:
        status, events = get_json(f"/api/v0/tickets/{ticket_id}/events")
        if status != 200:
            time.sleep(0.5)
            continue
        latest_events = events or []
        count = sum(1 for e in latest_events if e.get("event_type") == event_type)
        if count >= expected_count:
            return latest_events
        time.sleep(0.5)
    return latest_events


def wait_for_ticket_status(session_id, ticket_id, expected_status, timeout_seconds=STATUS_WAIT_SECONDS):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        status, tickets = get_json(
            f"/api/v0/sessions/{session_id}/tickets",
            params={"assignee_role": "architect_agent"},
        )
        if status != 200:
            time.sleep(0.5)
            continue
        for ticket in tickets or []:
            if ticket.get("ticket_id") == ticket_id and ticket.get("status") == expected_status:
                return ticket
        time.sleep(0.5)
    return None


class ArchitectAutoSchedulerFlowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        run_mysql_file(SCHEMA_PATH)

    def setUp(self):
        self.session_id = f"SES-AUTO-{uuid.uuid4().hex}"
        run_mysql(
            "use {db}; "
            "insert into sessions(session_id, title, status, created_at, updated_at) "
            "values('{sid}', 'architect auto scheduler test', 'ACTIVE', now(), now());".format(
                db=DB_NAME,
                sid=self.session_id,
            )
        )

    def test_should_resume_after_user_responded_without_manual_auto_process_api(self):
        status, created = post_json(
            f"/api/v0/sessions/{self.session_id}/tickets",
            {
                "type": "HANDOFF",
                "title": "handoff requires architecture follow-up",
                "created_by_role": "requirement_agent",
                "assignee_role": "architect_agent",
                "payload_json": "{\"kind\":\"handoff_packet\"}",
            },
        )
        self.assertEqual(200, status)
        ticket_id = created["ticket_id"]

        events_first_round = wait_for_event_count(ticket_id, "DECISION_REQUESTED", 1)
        first_round_count = sum(1 for e in events_first_round if e.get("event_type") == "DECISION_REQUESTED")
        self.assertGreaterEqual(
            first_round_count,
            1,
            "Architect scheduler did not process OPEN ticket automatically; check scheduler/auto-processor config.",
        )

        waiting_ticket = wait_for_ticket_status(self.session_id, ticket_id, "WAITING_USER")
        self.assertIsNotNone(waiting_ticket)

        status, responded = post_json(
            f"/api/v0/tickets/{ticket_id}/events",
            {
                "event_type": "USER_RESPONDED",
                "actor_role": "user",
                "body": "We choose option A, continue with this direction.",
                "data_json": "{\"selected\":\"OPT-A\"}",
            },
        )
        self.assertEqual(200, status)
        self.assertEqual("USER_RESPONDED", responded["event_type"])

        done_ticket = wait_for_ticket_status(self.session_id, ticket_id, "DONE", timeout_seconds=DONE_WAIT_SECONDS)
        self.assertIsNotNone(
            done_ticket,
            "Architect scheduler did not finish planning after USER_RESPONDED.",
        )

        events_with_artifact = wait_for_event_count(ticket_id, "ARTIFACT_LINKED", 1, timeout_seconds=DONE_WAIT_SECONDS)
        artifact_count = sum(1 for e in events_with_artifact if e.get("event_type") == "ARTIFACT_LINKED")
        self.assertGreaterEqual(artifact_count, 1)
        has_done_status = False
        for event in events_with_artifact:
            if event.get("event_type") != "STATUS_CHANGED":
                continue
            data_json = event.get("data_json") or "{}"
            data = json.loads(data_json)
            if data.get("to_status") == "DONE":
                has_done_status = True
                break
        self.assertTrue(has_done_status, "Expected STATUS_CHANGED to DONE from architect auto planner.")

        module_count = int(run_mysql_scalar(
            "use {db}; "
            "select count(*) from work_modules where session_id='{sid}';".format(
                db=DB_NAME,
                sid=self.session_id,
            )
        ) or "0")
        task_count = int(run_mysql_scalar(
            "use {db}; "
            "select count(*) from work_tasks t "
            "join work_modules m on t.module_id=m.module_id "
            "where m.session_id='{sid}';".format(
                db=DB_NAME,
                sid=self.session_id,
            )
        ) or "0")
        self.assertGreaterEqual(module_count, 1)
        self.assertGreaterEqual(task_count, 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)

