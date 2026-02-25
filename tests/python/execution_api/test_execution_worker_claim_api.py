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
SCHEMA_PATH = os.path.join(os.path.dirname(__file__), "bootstrap_execution_schema.sql")


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
    first_line = output.splitlines()[0]
    return first_line.split("\t")


def query_pairs(sql):
    cmd = mysql_base_cmd() + ["-N", "-B", "-e", sql]
    result = subprocess.run(cmd, check=True, capture_output=True, text=True)
    output = result.stdout.strip()
    pairs = {}
    if not output:
        return pairs
    for line in output.splitlines():
        cols = line.split("\t")
        if len(cols) != 2:
            continue
        pairs[cols[0]] = cols[1]
    return pairs


def request_json(method, path, payload=None):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
        method=method,
        data=body,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        return e.code, json.loads(raw) if raw else None


def post_json(path, payload=None):
    return request_json("POST", path, payload)


class ExecutionWorkerClaimApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        run_mysql_file(SCHEMA_PATH)

    def setUp(self):
        suffix = uuid.uuid4().hex
        self.session_id = f"SES-EX-{suffix}"
        self.worker_id = f"WRK-EX-{suffix}"
        self.tp_java = f"TP-JAVA-{suffix}"
        self.tp_maven = f"TP-MAVEN-{suffix}"
        self.module_name = f"execution-module-{suffix[:8]}"
        self.task_title = f"execution-task-{suffix[:8]}"
        run_mysql(
            "use {db}; "
            "insert into sessions(session_id, title, status, created_at, updated_at) "
            "values('{sid}', 'execution api test', 'ACTIVE', now(), now()); "
            "insert into toolpacks(toolpack_id, name, version, kind, description, created_at) "
            "values('{tp_java}', 'java', '21', 'language', 'java toolpack', now()); "
            "insert into toolpacks(toolpack_id, name, version, kind, description, created_at) "
            "values('{tp_maven}', 'maven', '3.9.6', 'build', 'maven toolpack', now()); "
            "insert into workers(worker_id, status, created_at, updated_at) "
            "values('{wid}', 'READY', now(), now()); "
            "insert into worker_toolpacks(worker_id, toolpack_id) values('{wid}', '{tp_java}'); "
            "insert into worker_toolpacks(worker_id, toolpack_id) values('{wid}', '{tp_maven}');".format(
                db=DB_NAME,
                sid=self.session_id,
                wid=self.worker_id,
                tp_java=self.tp_java,
                tp_maven=self.tp_maven,
            )
        )

    def test_worker_claim_then_heartbeat_should_persist_expected_rows(self):
        task = self._create_task([self.tp_java, self.tp_maven])
        self.assertEqual("READY_FOR_ASSIGN", task["status"])
        task_id = task["task_id"]
        snapshot_id = self._insert_ready_snapshot(task_id, "IMPL")

        status, claimed = post_json(f"/api/v0/workers/{self.worker_id}/claim")
        self.assertEqual(200, status)
        self.assertEqual(task_id, claimed["task_id"])
        self.assertEqual(snapshot_id, claimed["context_snapshot_id"])
        run_id = claimed["run_id"]

        status, heartbeat = post_json(f"/api/v0/runs/{run_id}/heartbeat", {})
        self.assertEqual(200, status)
        self.assertEqual("RUNNING", heartbeat["status"])

        task_row = query_single_row(
            "use {db}; "
            "select status, ifnull(active_run_id, '') from work_tasks where task_id='{tid}';".format(
                db=DB_NAME,
                tid=task_id,
            )
        )
        self.assertEqual(["ASSIGNED", run_id], task_row)

        run_row = query_single_row(
            "use {db}; "
            "select status, task_id, worker_id, context_snapshot_id from task_runs where run_id='{rid}';".format(
                db=DB_NAME,
                rid=run_id,
            )
        )
        self.assertEqual(["RUNNING", task_id, self.worker_id, snapshot_id], run_row)

        workspace_row = query_single_row(
            "use {db}; "
            "select status from git_workspaces where run_id='{rid}';".format(
                db=DB_NAME,
                rid=run_id,
            )
        )
        self.assertEqual(["ALLOCATED"], workspace_row)

        event_counts = query_pairs(
            "use {db}; "
            "select event_type, count(*) from task_run_events "
            "where run_id='{rid}' "
            "group by event_type;".format(
                db=DB_NAME,
                rid=run_id,
            )
        )
        self.assertEqual("1", event_counts.get("RUN_STARTED"))
        self.assertEqual("1", event_counts.get("HEARTBEAT"))

    def test_claim_should_return_412_when_context_snapshot_not_ready_and_release_task(self):
        task = self._create_task([self.tp_java])
        task_id = task["task_id"]

        status, err = post_json(f"/api/v0/workers/{self.worker_id}/claim")
        self.assertEqual(412, status)
        self.assertEqual("PRECONDITION_FAILED", err["code"])

        task_row = query_single_row(
            "use {db}; "
            "select status, ifnull(active_run_id, '__NULL__') from work_tasks where task_id='{tid}';".format(
                db=DB_NAME,
                tid=task_id,
            )
        )
        self.assertEqual(["READY_FOR_ASSIGN", "__NULL__"], task_row)

        run_count = query_single_row(
            "use {db}; "
            "select count(*) from task_runs where task_id='{tid}';".format(
                db=DB_NAME,
                tid=task_id,
            )
        )
        self.assertEqual(["0"], run_count)

    def test_claim_should_respect_dependency_gate_until_upstream_done(self):
        status, module = post_json(
            f"/api/v0/sessions/{self.session_id}/modules",
            {
                "name": self.module_name,
                "description": "execution dependency test module",
            },
        )
        self.assertEqual(200, status)
        module_id = module["module_id"]

        upstream_task_id = f"TASK-UP-{uuid.uuid4().hex[:12]}"
        dependent_task_id = f"TASK-DOWN-{uuid.uuid4().hex[:12]}"
        missing_toolpack = f"TP-MISSING-{uuid.uuid4().hex[:10]}"
        run_mysql(
            "use {db}; "
            "insert into work_tasks("
            "task_id,module_id,title,task_template_id,status,required_toolpacks_json,active_run_id,created_by_role,created_at,updated_at"
            ") values("
            "'{up}','{mid}','upstream task','tmpl.impl.v0','WAITING_WORKER','[\\\"{missing}\\\"]',null,'architect_agent',now(),now()"
            "); "
            "insert into work_tasks("
            "task_id,module_id,title,task_template_id,status,required_toolpacks_json,active_run_id,created_by_role,created_at,updated_at"
            ") values("
            "'{down}','{mid}','dependent task','tmpl.impl.v0','READY_FOR_ASSIGN','[\\\"{tp}\\\"]',null,'architect_agent',now(),now()"
            "); "
            "insert into work_task_dependencies("
            "task_id,depends_on_task_id,required_upstream_status,created_at"
            ") values("
            "'{down}','{up}','DONE',now()"
            ");".format(
                db=DB_NAME,
                up=upstream_task_id,
                down=dependent_task_id,
                mid=module_id,
                tp=self.tp_java,
                missing=missing_toolpack,
            )
        )
        self._insert_ready_snapshot(dependent_task_id, "IMPL")

        status, claimed = post_json(f"/api/v0/workers/{self.worker_id}/claim")
        self.assertEqual(204, status)
        self.assertIsNone(claimed)

        task_row = query_single_row(
            "use {db}; "
            "select status, ifnull(active_run_id, '__NULL__') from work_tasks where task_id='{tid}';".format(
                db=DB_NAME,
                tid=dependent_task_id,
            )
        )
        self.assertEqual(["READY_FOR_ASSIGN", "__NULL__"], task_row)

        run_mysql(
            "use {db}; "
            "update work_tasks set status='DONE', updated_at=now() where task_id='{up}';".format(
                db=DB_NAME,
                up=upstream_task_id,
            )
        )

        status, claimed = post_json(f"/api/v0/workers/{self.worker_id}/claim")
        self.assertEqual(200, status)
        self.assertEqual(dependent_task_id, claimed["task_id"])

    def _create_task(self, required_toolpacks):
        status, module = post_json(
            f"/api/v0/sessions/{self.session_id}/modules",
            {
                "name": self.module_name,
                "description": "execution api test module",
            },
        )
        self.assertEqual(200, status)
        module_id = module["module_id"]
        status, task = post_json(
            f"/api/v0/modules/{module_id}/tasks",
            {
                "title": self.task_title,
                "task_template_id": "tmpl.impl.v0",
                "required_toolpacks_json": json.dumps(required_toolpacks),
            },
        )
        self.assertEqual(200, status)
        return task

    def _insert_ready_snapshot(self, task_id, run_kind):
        snapshot_id = f"CTXS-{uuid.uuid4().hex}"
        run_mysql(
            "use {db}; "
            "insert into task_context_snapshots("
            "snapshot_id, task_id, run_kind, status, trigger_type, source_fingerprint, "
            "task_context_ref, task_skill_ref, error_code, error_message, compiled_at, retained_until, created_at, updated_at"
            ") values ("
            "'{snapshot_id}', '{task_id}', '{run_kind}', 'READY', 'MANUAL_REFRESH', '{fingerprint}', "
            "'file:.agentx/context/{task_id}.json', 'file:.agentx/skills/{task_id}.md', null, null, now(), "
            "date_add(now(), interval 30 day), now(), now()"
            ");".format(
                db=DB_NAME,
                snapshot_id=snapshot_id,
                task_id=task_id,
                run_kind=run_kind,
                fingerprint=f"fp-{uuid.uuid4().hex[:24]}",
            )
        )
        return snapshot_id


if __name__ == "__main__":
    unittest.main(verbosity=2)

