#!/usr/bin/env python3
import json
import os
import subprocess
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

BACKLOG_B_COUNT = int(os.getenv("AGENTX_MIXED_BACKLOG_B_COUNT", "256"))
ELIGIBLE_A_COUNT = int(os.getenv("AGENTX_MIXED_ELIGIBLE_A_COUNT", "32"))


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
    cmd = mysql_base_cmd()
    result = subprocess.run(cmd, check=check, input=sql, capture_output=True, text=True)
    return result.stdout.strip()


def run_mysql_file(path):
    cmd = mysql_base_cmd()
    with open(path, "r", encoding="utf-8") as f:
        subprocess.run(cmd, check=True, input=f.read(), text=True, capture_output=True)


def request_json(method, path, payload=None, timeout=20):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
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


def seed_data(session_id, module_id, toolpack_a, toolpack_b, worker_id, backlog_tasks, eligible_tasks):
    rows_backlog = []
    rows_eligible = []
    rows_snapshots = []
    for task_id in backlog_tasks:
        rows_backlog.append(
            "('{tid}','{mid}','backlog {tid}','tmpl.impl.v0','READY_FOR_ASSIGN','[\\\"{tp}\\\"]',null,'architect_agent',now(),now())".format(
                tid=task_id,
                mid=module_id,
                tp=toolpack_b,
            )
        )
        rows_snapshots.append(
            "('{sid}','{tid}','IMPL','READY','MANUAL_REFRESH','{fp}','file:.agentx/context/{tid}.json','file:.agentx/skills/{tid}.md',null,null,now(),date_add(now(),interval 30 day),now(),now())".format(
                sid="CTXS-" + uuid.uuid4().hex,
                tid=task_id,
                fp="fp-" + uuid.uuid4().hex[:20],
            )
        )

    for task_id in eligible_tasks:
        rows_eligible.append(
            "('{tid}','{mid}','eligible {tid}','tmpl.impl.v0','READY_FOR_ASSIGN','[\\\"{tp}\\\"]',null,'architect_agent',now(),now())".format(
                tid=task_id,
                mid=module_id,
                tp=toolpack_a,
            )
        )
        rows_snapshots.append(
            "('{sid}','{tid}','IMPL','READY','MANUAL_REFRESH','{fp}','file:.agentx/context/{tid}.json','file:.agentx/skills/{tid}.md',null,null,now(),date_add(now(),interval 30 day),now(),now())".format(
                sid="CTXS-" + uuid.uuid4().hex,
                tid=task_id,
                fp="fp-" + uuid.uuid4().hex[:20],
            )
        )

    run_mysql(
        "use {db}; "
        "insert into sessions(session_id,title,status,created_at,updated_at) values('{sid}','mixed backlog stress','ACTIVE',now(),now()); "
        "insert into toolpacks(toolpack_id,name,version,kind,description,created_at) values "
        "('{tp_a}','toolpack-a','1','misc','eligible toolpack',now()),"
        "('{tp_b}','toolpack-b','1','misc','backlog toolpack',now()); "
        "insert into workers(worker_id,status,created_at,updated_at) values('{wid}','READY',now(),now()); "
        "insert into worker_toolpacks(worker_id,toolpack_id) values('{wid}','{tp_a}'); "
        "insert into work_modules(module_id,session_id,name,description,created_at,updated_at) "
        "values('{mid}','{sid}','mixed-module','mixed module',now(),now()); "
        "insert into work_tasks(task_id,module_id,title,task_template_id,status,required_toolpacks_json,active_run_id,created_by_role,created_at,updated_at) values {tasks}; "
        "insert into task_context_snapshots(snapshot_id,task_id,run_kind,status,trigger_type,source_fingerprint,task_context_ref,task_skill_ref,error_code,error_message,compiled_at,retained_until,created_at,updated_at) values {snapshots};".format(
            db=DB_NAME,
            sid=session_id,
            tp_a=toolpack_a,
            tp_b=toolpack_b,
            wid=worker_id,
            mid=module_id,
            tasks=",".join(rows_backlog + rows_eligible),
            snapshots=",".join(rows_snapshots),
        )
    )


def main():
    run_mysql_file(SCHEMA_PATH)
    suffix = uuid.uuid4().hex[:12]
    session_id = "SES-MIXED-" + suffix
    module_id = "MOD-MIXED-" + suffix
    toolpack_a = "TP-MIXED-A-" + suffix
    toolpack_b = "TP-MIXED-B-" + suffix
    worker_id = "WRK-MIXED-" + suffix

    backlog_tasks = [f"TASK-MIXED-B-{i:04d}-{suffix}" for i in range(BACKLOG_B_COUNT)]
    eligible_tasks = [f"TASK-MIXED-A-{i:04d}-{suffix}" for i in range(ELIGIBLE_A_COUNT)]
    seed_data(session_id, module_id, toolpack_a, toolpack_b, worker_id, backlog_tasks, eligible_tasks)

    claimed = []
    while True:
        status, body = request_json("POST", f"/api/v0/workers/{worker_id}/claim")
        if status == 204:
            break
        if status != 200:
            raise RuntimeError(f"claim failed: status={status}, body={body}")
        claimed.append(body["task_id"])

    claimed_set = set(claimed)
    expected_set = set(eligible_tasks)
    if claimed_set != expected_set:
        missing = sorted(expected_set - claimed_set)[:10]
        unexpected = sorted(claimed_set - expected_set)[:10]
        raise RuntimeError(
            "mixed backlog claim mismatch, expected_only_a_tasks, "
            f"missing={missing}, unexpected={unexpected}, total_claimed={len(claimed_set)}"
        )

    print("mixed_backlog_stress_ok=1")
    print(f"session_id={session_id}")
    print(f"worker_id={worker_id}")
    print(f"backlog_b_count={BACKLOG_B_COUNT}")
    print(f"eligible_a_count={ELIGIBLE_A_COUNT}")
    print(f"claimed_count={len(claimed_set)}")


if __name__ == "__main__":
    main()

