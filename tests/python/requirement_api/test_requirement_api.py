#!/usr/bin/env python3
import json
import os
import subprocess
import time
import unittest
import urllib.error
import urllib.request
import uuid

BASE_URL = os.getenv("AGENTX_BASE_URL", "http://127.0.0.1:8080")
DB_HOST = os.getenv("AGENTX_DB_HOST", "127.0.0.1")
DB_PORT = os.getenv("AGENTX_DB_PORT", "3306")
DB_NAME = os.getenv("AGENTX_DB_NAME", "agentx_backend")
DB_USER = os.getenv("AGENTX_DB_USERNAME", "root")
DB_PASSWORD = os.getenv("AGENTX_DB_PASSWORD", "")
SCHEMA_PATH = os.path.join(os.path.dirname(__file__), "bootstrap_requirement_schema.sql")


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


def post(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
        method="POST",
        data=data,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        parsed = json.loads(body) if body else None
        return e.code, parsed


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


class RequirementApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        run_mysql_file(SCHEMA_PATH)

    def setUp(self):
        self.session_id = f"SES-PY-{uuid.uuid4().hex}"
        run_mysql(
            "use {db}; "
            "insert into sessions(session_id, title, status, created_at, updated_at) "
            "values('{sid}', 'py test session', 'ACTIVE', now(), now());".format(
                db=DB_NAME,
                sid=self.session_id,
            )
        )

    def test_happy_path_create_version_confirm(self):
        status, doc = post(
            f"/api/v0/sessions/{self.session_id}/requirement-docs",
            {"title": "python flow"},
        )
        self.assertEqual(201, status)
        self.assertEqual("DRAFT", doc["status"])
        self.assertEqual(0, doc["current_version"])
        doc_id = doc["doc_id"]

        content = build_valid_markdown(
            "python flow",
            "first version user input",
            "initial draft from test",
        )
        status, version = post(
            f"/api/v0/requirement-docs/{doc_id}/versions",
            {"content": content, "created_by_role": "user"},
        )
        self.assertEqual(201, status)
        self.assertEqual(doc_id, version["doc_id"])
        self.assertEqual(1, version["version"])

        status, confirmed = post(f"/api/v0/requirement-docs/{doc_id}/confirm")
        self.assertEqual(200, status)
        self.assertEqual("CONFIRMED", confirmed["status"])
        self.assertEqual(1, confirmed["confirmed_version"])

        ticket_count = query_single_value(
            "use {db}; "
            "select count(*) from tickets "
            "where session_id='{sid}' and type='ARCH_REVIEW';".format(
                db=DB_NAME,
                sid=self.session_id,
            )
        )
        self.assertEqual("1", ticket_count)

        ticket_event_count = query_single_value(
            "use {db}; "
            "select count(*) "
            "from ticket_events e "
            "join tickets t on e.ticket_id=t.ticket_id "
            "where t.session_id='{sid}' "
            "  and t.type='ARCH_REVIEW' "
            "  and e.event_type='COMMENT' "
            "  and e.actor_role='requirement_agent';".format(
                db=DB_NAME,
                sid=self.session_id,
            )
        )
        self.assertEqual("1", ticket_event_count)

    def test_confirm_without_version_returns_conflict(self):
        status, doc = post(
            f"/api/v0/sessions/{self.session_id}/requirement-docs",
            {"title": "no version"},
        )
        self.assertEqual(201, status)
        doc_id = doc["doc_id"]

        status, err = post(f"/api/v0/requirement-docs/{doc_id}/confirm")
        self.assertEqual(409, status)
        self.assertEqual("CONFLICT", err["code"])

    def test_create_version_invalid_role_returns_bad_request(self):
        status, doc = post(
            f"/api/v0/sessions/{self.session_id}/requirement-docs",
            {"title": "role check"},
        )
        self.assertEqual(201, status)
        doc_id = doc["doc_id"]

        status, err = post(
            f"/api/v0/requirement-docs/{doc_id}/versions",
            {
                "content": build_valid_markdown("role check", "input", "role check"),
                "created_by_role": "architect_agent",
            },
        )
        self.assertEqual(400, status)
        self.assertEqual("BAD_REQUEST", err["code"])

    def test_create_version_invalid_template_returns_bad_request(self):
        status, doc = post(
            f"/api/v0/sessions/{self.session_id}/requirement-docs",
            {"title": "template check"},
        )
        self.assertEqual(201, status)
        doc_id = doc["doc_id"]

        status, err = post(
            f"/api/v0/requirement-docs/{doc_id}/versions",
            {"content": "# not req-doc-v1", "created_by_role": "user"},
        )
        self.assertEqual(400, status)
        self.assertEqual("BAD_REQUEST", err["code"])

    def test_create_version_doc_not_found_returns_not_found(self):
        status, err = post(
            "/api/v0/requirement-docs/REQ-DOES-NOT-EXIST/versions",
            {
                "content": build_valid_markdown("not found", "input", "not found"),
                "created_by_role": "user",
            },
        )
        self.assertEqual(404, status)
        self.assertEqual("NOT_FOUND", err["code"])


if __name__ == "__main__":
    unittest.main(verbosity=2)

