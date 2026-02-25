#!/usr/bin/env python3
import json
import os
import subprocess
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
SCHEMA_PATH = os.path.join(
    os.path.dirname(__file__),
    "..",
    "execution_api",
    "bootstrap_execution_schema.sql",
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
    result = subprocess.run(cmd, check=check, capture_output=True, text=True)
    return result.stdout.strip()


def run_mysql_file(path):
    cmd = mysql_base_cmd()
    with open(path, "r", encoding="utf-8") as f:
        subprocess.run(cmd, check=True, input=f.read(), text=True, capture_output=True)


def query_single_row(sql):
    cmd = mysql_base_cmd() + ["-N", "-B", "-e", sql]
    result = subprocess.run(cmd, check=True, capture_output=True, text=True)
    output = result.stdout.strip()
    if not output:
        return []
    return output.splitlines()[0].split("\t")


def request_json(method, path, payload=None):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
        method=method,
        data=body,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        return e.code, json.loads(raw) if raw else None


def post_json(path, payload=None):
    return request_json("POST", path, payload)


def get_json(path):
    return request_json("GET", path, None)


class WorkforceRuntimeFlowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        run_mysql_file(SCHEMA_PATH)

    def test_auto_provision_then_claim_then_lease_recovery(self):
        suffix = uuid.uuid4().hex
        session_id = f"SES-WR-{suffix}"
        toolpack_id = f"TP-AUTO-{suffix}"
        module_name = f"workforce-module-{suffix[:8]}"
        task_a_title = f"waiting-auto-provision-{suffix[:8]}"
        task_b_title = f"claim-and-recover-{suffix[:8]}"

        run_mysql(
            "use {db}; "
            "insert into sessions(session_id, title, status, created_at, updated_at) "
            "values('{sid}', 'workforce runtime test', 'ACTIVE', now(), now()); "
            "insert into toolpacks(toolpack_id, name, version, kind, description, created_at) "
            "values('{tp}', 'auto-toolpack', '1', 'misc', 'auto provision test toolpack', now());".format(
                db=DB_NAME,
                sid=session_id,
                tp=toolpack_id,
            )
        )

        status, module = post_json(
            f"/api/v0/sessions/{session_id}/modules",
            {"name": module_name, "description": "runtime flow module"},
        )
        self.assertEqual(200, status)
        module_id = module["module_id"]

        status, task_a = post_json(
            f"/api/v0/modules/{module_id}/tasks",
            {
                "title": task_a_title,
                "task_template_id": "tmpl.impl.v0",
                "required_toolpacks_json": json.dumps([toolpack_id]),
            },
        )
        self.assertEqual(200, status)
        self.assertEqual("WAITING_WORKER", task_a["status"])
        task_a_id = task_a["task_id"]

        run_mysql(
            "use {db}; "
            "update work_tasks "
            "set created_at='2001-01-01 00:00:00', updated_at='2001-01-01 00:00:00' "
            "where task_id='{tid}';".format(
                db=DB_NAME,
                tid=task_a_id,
            )
        )

        status, provision = post_json(
            "/api/v0/workforce/auto-provision",
            {"max_tasks": 1},
        )
        self.assertEqual(200, status)
        self.assertEqual(1, provision["scanned_waiting_tasks"])
        if provision["created_workers"] >= 1:
            created_worker_id = provision["created_worker_ids"][0]
            binding_row = query_single_row(
                "use {db}; "
                "select count(*) from worker_toolpacks "
                "where worker_id='{wid}' and toolpack_id='{tp}';".format(
                    db=DB_NAME,
                    wid=created_worker_id,
                    tp=toolpack_id,
                )
            )
            self.assertEqual(["1"], binding_row)

        claim_worker_id = "WRK-CLAIM-" + suffix[:24]
        run_mysql(
            "use {db}; "
            "insert into workers(worker_id, status, created_at, updated_at) "
            "values('{wid}', 'READY', now(), now()); "
            "insert into worker_toolpacks(worker_id, toolpack_id) values('{wid}', '{tp}');".format(
                db=DB_NAME,
                wid=claim_worker_id,
                tp=toolpack_id,
            )
        )

        status, task_b = post_json(
            f"/api/v0/modules/{module_id}/tasks",
            {
                "title": task_b_title,
                "task_template_id": "tmpl.impl.v0",
                "required_toolpacks_json": json.dumps([toolpack_id]),
            },
        )
        self.assertEqual(200, status)
        self.assertEqual("READY_FOR_ASSIGN", task_b["status"])
        task_b_id = task_b["task_id"]

        snapshot_id = f"CTXS-{uuid.uuid4().hex}"
        run_mysql(
            "use {db}; "
            "insert into task_context_snapshots("
            "snapshot_id, task_id, run_kind, status, trigger_type, source_fingerprint, "
            "task_context_ref, task_skill_ref, error_code, error_message, compiled_at, retained_until, created_at, updated_at"
            ") values ("
            "'{sid}', '{tid}', 'IMPL', 'READY', 'MANUAL_REFRESH', '{fp}', "
            "'file:.agentx/context/{tid}.json', 'file:.agentx/skills/{tid}.md', null, null, now(), "
            "date_add(now(), interval 30 day), now(), now()"
            ");".format(
                db=DB_NAME,
                sid=snapshot_id,
                tid=task_b_id,
                fp=f"fp-{uuid.uuid4().hex[:20]}",
            )
        )

        status, claim = post_json(f"/api/v0/workers/{claim_worker_id}/claim")
        self.assertEqual(200, status)
        self.assertEqual(task_b_id, claim["task_id"])
        run_id = claim["run_id"]

        run_mysql(
            "use {db}; "
            "update task_runs set lease_until = date_sub(now(), interval 60 second) where run_id='{rid}';".format(
                db=DB_NAME,
                rid=run_id,
            )
        )

        status, recovery = post_json(
            "/api/v0/execution/lease-recovery",
            {"max_runs": 10},
        )
        self.assertEqual(200, status)
        self.assertGreaterEqual(recovery["recovered_runs"], 1)

        run_row = query_single_row(
            "use {db}; "
            "select status from task_runs where run_id='{rid}';".format(
                db=DB_NAME,
                rid=run_id,
            )
        )
        self.assertEqual(["FAILED"], run_row)

        task_row = query_single_row(
            "use {db}; "
            "select status, ifnull(active_run_id, '__NULL__') from work_tasks where task_id='{tid}';".format(
                db=DB_NAME,
                tid=task_b_id,
            )
        )
        self.assertEqual(["READY_FOR_ASSIGN", "__NULL__"], task_row)

        event_row = query_single_row(
            "use {db}; "
            "select count(*) from task_run_events where run_id='{rid}' and event_type='RUN_FINISHED';".format(
                db=DB_NAME,
                rid=run_id,
            )
        )
        self.assertEqual(["1"], event_row)

        workspace_row = query_single_row(
            "use {db}; "
            "select status from git_workspaces where run_id='{rid}';".format(
                db=DB_NAME,
                rid=run_id,
            )
        )
        self.assertEqual(["RELEASED"], workspace_row)

    def test_auto_provision_should_raise_clarification_for_missing_toolpack(self):
        suffix = uuid.uuid4().hex
        session_id = f"SES-MISS-{suffix}"
        module_name = f"missing-toolpack-module-{suffix[:8]}"
        missing_toolpack_id = f"TP-MISSING-{suffix[:12]}"

        run_mysql(
            "use {db}; "
            "insert into sessions(session_id, title, status, created_at, updated_at) "
            "values('{sid}', 'missing toolpack clarification test', 'ACTIVE', now(), now());".format(
                db=DB_NAME,
                sid=session_id,
            )
        )

        status, module = post_json(
            f"/api/v0/sessions/{session_id}/modules",
            {"name": module_name, "description": "missing toolpack task"},
        )
        self.assertEqual(200, status)
        module_id = module["module_id"]

        status, task = post_json(
            f"/api/v0/modules/{module_id}/tasks",
            {
                "title": "missing toolpack should trigger clarification",
                "task_template_id": "tmpl.impl.v0",
                "required_toolpacks_json": json.dumps([missing_toolpack_id]),
            },
        )
        self.assertEqual(200, status)
        self.assertEqual("WAITING_WORKER", task["status"])
        task_id = task["task_id"]

        run_mysql(
            "use {db}; "
            "update work_tasks "
            "set created_at='1971-01-01 00:00:00', updated_at='1971-01-01 00:00:00' "
            "where task_id='{tid}';".format(
                db=DB_NAME,
                tid=task_id,
            )
        )

        total_created_clarifications = 0
        clarification_ticket_id = None
        for _ in range(5):
            status, provision = post_json("/api/v0/workforce/auto-provision", {"max_tasks": 500})
            self.assertEqual(200, status)
            self.assertGreaterEqual(provision["scanned_waiting_tasks"], 1)
            self.assertGreaterEqual(provision["skipped_missing_toolpacks"], 1)
            total_created_clarifications += provision["created_clarification_tickets"]
            if provision["created_clarification_ticket_ids"]:
                clarification_ticket_id = provision["created_clarification_ticket_ids"][0]
                break

        status, tickets = get_json(
            f"/api/v0/sessions/{session_id}/tickets"
            "?status=WAITING_USER&assignee_role=architect_agent&type=CLARIFICATION"
        )
        self.assertEqual(200, status)
        self.assertGreaterEqual(len(tickets), 1)
        if clarification_ticket_id is None:
            clarification_ticket_id = tickets[0]["ticket_id"]
        self.assertTrue(any(t["ticket_id"] == clarification_ticket_id for t in tickets))
        self.assertGreaterEqual(total_created_clarifications, 1)

        status, events = get_json(f"/api/v0/tickets/{clarification_ticket_id}/events")
        self.assertEqual(200, status)
        self.assertGreaterEqual(len(events), 1)
        decision_requested_events = [e for e in events if e["event_type"] == "DECISION_REQUESTED"]
        self.assertGreaterEqual(len(decision_requested_events), 1)

        data_json = decision_requested_events[-1].get("data_json")
        self.assertIsNotNone(data_json)
        data = json.loads(data_json)
        self.assertEqual("CLARIFICATION", data.get("request_kind"))
        self.assertIn(missing_toolpack_id, data.get("missing_toolpacks", []))

        task_row = query_single_row(
            "use {db}; "
            "select status, ifnull(active_run_id, '__NULL__') "
            "from work_tasks where task_id='{tid}';".format(
                db=DB_NAME,
                tid=task_id,
            )
        )
        self.assertEqual(["WAITING_WORKER", "__NULL__"], task_row)


if __name__ == "__main__":
    unittest.main(verbosity=2)

