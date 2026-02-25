#!/usr/bin/env python3
import concurrent.futures
import json
import os
import subprocess
import time
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

TASK_COUNT = int(os.getenv("AGENTX_STRESS_TASK_COUNT", "120"))
WORKER_COUNT = int(os.getenv("AGENTX_STRESS_WORKER_COUNT", "40"))
THREADS = int(os.getenv("AGENTX_STRESS_THREADS", "40"))


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
    cmd = mysql_base_cmd()
    result = subprocess.run(cmd, check=check, input=sql, capture_output=True, text=True)
    return result.stdout.strip()


def run_mysql_file(path):
    cmd = mysql_base_cmd()
    with open(path, "r", encoding="utf-8") as f:
        subprocess.run(cmd, check=True, input=f.read(), text=True, capture_output=True)


def query_single_value(sql):
    cmd = mysql_base_cmd(["-N", "-B"]) + ["-e", sql]
    result = subprocess.run(cmd, check=True, capture_output=True, text=True)
    return result.stdout.strip()


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


def seed_data(session_id, module_id, toolpack_id, workers, tasks):
    values_workers = []
    values_bindings = []
    for worker_id in workers:
        values_workers.append(
            "('{wid}','READY',now(),now())".format(wid=worker_id)
        )
        values_bindings.append(
            "('{wid}','{tp}')".format(wid=worker_id, tp=toolpack_id)
        )
    values_tasks = []
    values_snapshots = []
    for task_id in tasks:
        values_tasks.append(
            "('{tid}','{mid}','stress task {tid}','tmpl.impl.v0','READY_FOR_ASSIGN','[\\\"{tp}\\\"]',null,'architect_agent',now(),now())".format(
                tid=task_id,
                mid=module_id,
                tp=toolpack_id,
            )
        )
        snapshot_id = "CTXS-" + uuid.uuid4().hex
        values_snapshots.append(
            "('{sid}','{tid}','IMPL','READY','MANUAL_REFRESH','{fp}','file:.agentx/context/{tid}.json','file:.agentx/skills/{tid}.md',null,null,now(),date_add(now(),interval 30 day),now(),now())".format(
                sid=snapshot_id,
                tid=task_id,
                fp="fp-" + uuid.uuid4().hex[:20],
            )
        )

    run_mysql(
        "use {db}; "
        "insert into sessions(session_id,title,status,created_at,updated_at) values('{sid}','stress session','ACTIVE',now(),now()); "
        "insert into toolpacks(toolpack_id,name,version,kind,description,created_at) "
        "values('{tp}','stress-toolpack','1','misc','stress toolpack',now()); "
        "insert into work_modules(module_id,session_id,name,description,created_at,updated_at) "
        "values('{mid}','{sid}','stress-module','stress module',now(),now()); "
        "insert into workers(worker_id,status,created_at,updated_at) values {workers_sql}; "
        "insert into worker_toolpacks(worker_id,toolpack_id) values {bindings_sql}; "
        "insert into work_tasks(task_id,module_id,title,task_template_id,status,required_toolpacks_json,active_run_id,created_by_role,created_at,updated_at) values {tasks_sql}; "
        "insert into task_context_snapshots(snapshot_id,task_id,run_kind,status,trigger_type,source_fingerprint,task_context_ref,task_skill_ref,error_code,error_message,compiled_at,retained_until,created_at,updated_at) values {snapshots_sql};".format(
            db=DB_NAME,
            sid=session_id,
            tp=toolpack_id,
            mid=module_id,
            workers_sql=",".join(values_workers),
            bindings_sql=",".join(values_bindings),
            tasks_sql=",".join(values_tasks),
            snapshots_sql=",".join(values_snapshots),
        )
    )


def claim_loop(worker_id):
    claimed = []
    while True:
        status, body = request_json("POST", f"/api/v0/workers/{worker_id}/claim", None, timeout=30)
        if status == 204:
            break
        if status != 200:
            return claimed, status
        claimed.append((body["task_id"], body["run_id"]))
    return claimed, 200


def main():
    run_mysql_file(SCHEMA_PATH)

    suffix = uuid.uuid4().hex
    session_id = "SES-STRESS-" + suffix
    module_id = "MOD-STRESS-" + suffix[:16]
    toolpack_id = "TP-STRESS-" + suffix[:16]
    workers = [f"WRK-STRESS-{i:03d}-{suffix[:8]}" for i in range(WORKER_COUNT)]
    tasks = [f"TASK-STRESS-{i:04d}-{suffix[:8]}" for i in range(TASK_COUNT)]

    seed_data(session_id, module_id, toolpack_id, workers, tasks)

    started = time.perf_counter()
    claimed_pairs = []
    claim_statuses = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=THREADS) as pool:
        futures = [pool.submit(claim_loop, worker_id) for worker_id in workers]
        for fut in concurrent.futures.as_completed(futures):
            claimed, status = fut.result()
            claim_statuses.append(status)
            claimed_pairs.extend(claimed)
    elapsed = time.perf_counter() - started

    non_200 = [s for s in claim_statuses if s not in (200, 204)]
    if non_200:
        raise RuntimeError(f"Unexpected claim statuses: {non_200[:10]}")

    unique_tasks = {task_id for task_id, _ in claimed_pairs}
    unique_runs = {run_id for _, run_id in claimed_pairs}
    if len(unique_tasks) != TASK_COUNT:
        raise RuntimeError(f"Claimed task count mismatch: expected={TASK_COUNT}, actual={len(unique_tasks)}")
    if len(unique_runs) != TASK_COUNT:
        raise RuntimeError(f"Run count mismatch: expected={TASK_COUNT}, actual={len(unique_runs)}")

    db_run_count = int(query_single_value(
        "use {db}; select count(*) from task_runs where task_id like 'TASK-STRESS-%-{suffix}';".format(
            db=DB_NAME,
            suffix=suffix[:8],
        )
    ))
    db_distinct_task_count = int(query_single_value(
        "use {db}; select count(distinct task_id) from task_runs where task_id like 'TASK-STRESS-%-{suffix}';".format(
            db=DB_NAME,
            suffix=suffix[:8],
        )
    ))
    if db_run_count != TASK_COUNT or db_distinct_task_count != TASK_COUNT:
        raise RuntimeError(
            f"DB run/task mismatch, runs={db_run_count}, distinct_task_runs={db_distinct_task_count}, expected={TASK_COUNT}"
        )

    run_ids = list(unique_runs)
    half = len(run_ids) // 2
    expire_ids = run_ids[:half]
    if expire_ids:
        run_mysql(
            "use {db}; update task_runs set lease_until=date_sub(now(), interval 120 second) where run_id in ({ids});".format(
                db=DB_NAME,
                ids=",".join("'" + rid + "'" for rid in expire_ids),
            )
        )
    status, recovery = request_json("POST", "/api/v0/execution/lease-recovery", {"max_runs": TASK_COUNT})
    if status != 200:
        raise RuntimeError(f"Lease recovery failed: status={status}, body={recovery}")

    failed_count = int(query_single_value(
        "use {db}; select count(*) from task_runs where run_id in ({ids}) and status='FAILED';".format(
            db=DB_NAME,
            ids=",".join("'" + rid + "'" for rid in expire_ids) if expire_ids else "''",
        )
    )) if expire_ids else 0
    if failed_count != len(expire_ids):
        raise RuntimeError(f"Recovered failed count mismatch, expected={len(expire_ids)}, actual={failed_count}")

    print("stress_ok=1")
    print(f"session_id={session_id}")
    print(f"task_count={TASK_COUNT}")
    print(f"worker_count={WORKER_COUNT}")
    print(f"claimed_runs={len(unique_runs)}")
    print(f"elapsed_seconds={elapsed:.3f}")
    print(f"throughput_claims_per_sec={TASK_COUNT/elapsed:.2f}")
    print(f"recovered_runs={recovery['recovered_runs']}")


if __name__ == "__main__":
    main()

