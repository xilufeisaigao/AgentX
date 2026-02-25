#!/usr/bin/env python3
import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SESSION_ID_PATTERN = re.compile(r"^SES-[A-Za-z0-9]+$")
TICKET_ID_PATTERN = re.compile(r"^TCK-[A-Za-z0-9]+$")


def request_json(method, base_url, path, payload=None, timeout_seconds=180, params=None):
    full_path = path
    if params:
        full_path += "?" + urllib.parse.urlencode(params)
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        base_url + full_path,
        method=method,
        data=body,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout_seconds) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else None
    except UnicodeEncodeError as e:
        return 0, {"code": "CLIENT_INPUT_ERROR", "message": str(e)}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        return e.code, json.loads(raw) if raw else None


def wait_port(host, port, timeout_seconds):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        try:
            sock = socket.create_connection((host, port), timeout=1.5)
            sock.close()
            return True
        except OSError:
            time.sleep(1)
    return False


def is_port_in_use(host, port):
    try:
        sock = socket.create_connection((host, port), timeout=0.8)
        sock.close()
        return True
    except OSError:
        return False


def pick_free_port(preferred_port):
    if not is_port_in_use("127.0.0.1", preferred_port):
        return preferred_port
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start_backend(port, provider):
    if os.name == "nt":
        stop_all_agentx_backend_processes()

    ts = int(time.time() * 1000)
    target_dir = os.path.join(ROOT_DIR, "target")
    os.makedirs(target_dir, exist_ok=True)
    stdout_path = os.path.join(target_dir, f"spring-boot-full-flow-{ts}.log")
    stderr_path = os.path.join(target_dir, f"spring-boot-full-flow-{ts}.err.log")
    stdout = open(stdout_path, "w", encoding="utf-8")
    stderr = open(stderr_path, "w", encoding="utf-8")

    env = os.environ.copy()
    env["SERVER_PORT"] = str(port)
    env["AGENTX_REQUIREMENT_LLM_PROVIDER"] = provider
    if "AGENTX_REQUIREMENT_LLM_TIMEOUT_MS" not in env:
        env["AGENTX_REQUIREMENT_LLM_TIMEOUT_MS"] = "120000"

    maven_cmd = "mvnw.cmd" if os.path.exists(os.path.join(ROOT_DIR, "mvnw.cmd")) else "mvn"
    proc = subprocess.Popen(
        [maven_cmd, "spring-boot:run"],
        cwd=ROOT_DIR,
        stdout=stdout,
        stderr=stderr,
        env=env,
    )
    return proc, stdout, stderr, stdout_path, stderr_path


def stop_backend_by_port(port, launcher_pid=None):
    if os.name != "nt":
        return
    try:
        cmd = (
            "$pids = @(); "
            f"if ({int(port)} -gt 0) {{ "
            f"$pids += (Get-NetTCPConnection -State Listen -LocalPort {int(port)} -ErrorAction SilentlyContinue | "
            "Select-Object -ExpandProperty OwningProcess -Unique) "
            "}; "
            f"if ({int(launcher_pid or 0)} -gt 0) {{ $pids += {int(launcher_pid or 0)} }}; "
            "$pids = $pids | Where-Object { $_ } | Sort-Object -Unique; "
            "$pids | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }"
        )
        subprocess.run(
            ["powershell", "-NoLogo", "-NoProfile", "-Command", cmd],
            check=False,
            capture_output=True,
            text=True,
        )
    except Exception:
        return


def stop_all_agentx_backend_processes():
    if os.name != "nt":
        return
    cmd = (
        "$procs = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine }; "
        "$backend = $procs | Where-Object { $_.CommandLine -like '*com.agentx.agentxbackend.AgentxBackendApplication*' }; "
        "$maven = $procs | Where-Object { "
        "($_.CommandLine -like '*maven.multiModuleProjectDirectory=*agentx-backend*') -and "
        "($_.CommandLine -like '*spring-boot:run*') "
        "}; "
        "$targets = @($backend + $maven) | Group-Object ProcessId | ForEach-Object { $_.Group[0] }; "
        "$targets | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
    )
    subprocess.run(
        ["powershell", "-NoLogo", "-NoProfile", "-Command", cmd],
        check=False,
        capture_output=True,
        text=True,
    )


def print_help():
    print(
        "\nCommands:\n"
        "  /help                Show commands\n"
        "  /new <TITLE>         Create and enter a new session\n"
        "  /sessions [KEYWORD]  List all sessions; optional keyword filters id/title\n"
        "  /enter <SESSION_ID>  Enter existing session (alias: /load)\n"
        "  /load <SESSION_ID>   Load existing session snapshot and continue\n"
        "  /architect-auto [SESSION_ID] [MAX]\n"
        "                      Trigger backend architect auto-processor once\n"
        "  /show                Print latest requirement markdown\n"
        "  /confirm             Confirm current requirement version (triggers ARCH_REVIEW)\n"
        "  /tickets [STATUS] [TYPE] [ASSIGNEE]\n"
        "                      Query tickets by session (supports filters)\n"
        "                      Examples: /tickets OPEN ARCH_REVIEW architect_agent\n"
        "                                /tickets WAITING_USER\n"
        "  /events [TICKET_ID]  List ticket events (latest selected ticket by default)\n"
        "  /claim [TICKET_ID] [AGENT_ID] [LEASE_SECONDS]\n"
        "                      Claim ticket as architect agent\n"
        "  /request [TICKET_ID] [DECISION|CLARIFICATION] <QUESTION>\n"
        "                      Append DECISION_REQUESTED and move ticket to WAITING_USER\n"
        "  /respond [TICKET_ID] <RESPONSE_TEXT>\n"
        "                      Append USER_RESPONDED and resume IN_PROGRESS\n"
        "  /done [TICKET_ID] [SUMMARY]\n"
        "                      Append STATUS_CHANGED(to_status=DONE)\n"
        "  /block [TICKET_ID] [REASON]\n"
        "                      Append STATUS_CHANGED(to_status=BLOCKED)\n"
        "  /monitor             Print SQL/log hints for manual monitoring\n"
        "  /status              Print local session/doc tracking state\n"
        "  /quit                Exit\n"
        "\nAny non-command text will be sent to requirement-agent API."
        "\nBefore first draft exists, backend stays in discovery chat and only drafts when it decides input is ready."
    )


def print_monitoring_hints(session_id, doc_id):
    print("\n=== Monitoring SQL (manual, optional) ===")
    if doc_id:
        print(
            "select doc_id,current_version,confirmed_version,status,updated_at "
            f"from requirement_docs where doc_id='{doc_id}';"
        )
        print(
            "select doc_id,version,created_by_role,created_at "
            f"from requirement_doc_versions where doc_id='{doc_id}' order by version;"
        )
    else:
        print("-- requirement doc not selected yet; use /enter <SESSION_ID> or create first draft.")
    print(
        "select ticket_id,type,status,assignee_role,requirement_doc_id,requirement_doc_ver,created_at "
        f"from tickets where session_id='{session_id}' and type in ('HANDOFF','ARCH_REVIEW') "
        "order by created_at desc;"
    )
    print(
        "select e.event_id,e.ticket_id,e.event_type,e.actor_role,e.created_at "
        "from ticket_events e join tickets t on e.ticket_id=t.ticket_id "
        f"where t.session_id='{session_id}' and t.type in ('HANDOFF','ARCH_REVIEW') "
        "order by e.created_at;"
    )
    print(
        "select module_id,name,created_at "
        f"from work_modules where session_id='{session_id}' order by created_at;"
    )
    print(
        "select t.task_id,t.module_id,t.title,t.task_template_id,t.status,t.required_toolpacks_json "
        "from work_tasks t join work_modules m on t.module_id=m.module_id "
        f"where m.session_id='{session_id}' order by t.created_at;"
    )
    print(
        "select worker_id,status,created_at,updated_at "
        "from workers order by created_at desc limit 20;"
    )
    print(
        "select wt.worker_id,wt.toolpack_id "
        "from worker_toolpacks wt "
        "join workers w on w.worker_id=wt.worker_id "
        "order by w.created_at desc, wt.toolpack_id asc limit 100;"
    )


def parse_ticket_filters(raw_tokens):
    params = {}
    positional = []
    for token in raw_tokens:
        if "=" not in token:
            positional.append(token)
            continue
        key, value = token.split("=", 1)
        key = key.strip().lower()
        value = value.strip()
        if not value:
            continue
        if key in {"status"}:
            params["status"] = value.upper()
        elif key in {"type", "ticket_type"}:
            params["type"] = value.upper()
        elif key in {"assignee", "assignee_role"}:
            params["assignee_role"] = value.lower()

    if positional:
        params["status"] = positional[0].upper()
    if len(positional) > 1:
        params["type"] = positional[1].upper()
    if len(positional) > 2:
        params["assignee_role"] = positional[2].lower()
    return params


def looks_like_session_id(value):
    return bool(SESSION_ID_PATTERN.fullmatch((value or "").strip()))


def looks_like_ticket_id(value):
    return bool(TICKET_ID_PATTERN.fullmatch((value or "").strip()))


def print_ticket_rows(tickets):
    print(f"tickets={len(tickets)}")
    for t in tickets:
        req_ref = f"{t.get('requirement_doc_id')}@{t.get('requirement_doc_ver')}"
        print(
            f"- {t.get('ticket_id')} type={t.get('type')} status={t.get('status')} "
            f"assignee={t.get('assignee_role')} claimed_by={t.get('claimed_by') or '-'} ref={req_ref}"
        )


def print_session_rows(sessions):
    print(f"sessions={len(sessions)}")
    for s in sessions:
        current_doc = s.get("current_requirement_doc") or {}
        if current_doc:
            doc_ref = (
                f"{current_doc.get('doc_id')}@v{current_doc.get('current_version')} "
                f"status={current_doc.get('status')}"
            )
        else:
            doc_ref = "-"
        print(
            f"- {s.get('session_id')} status={s.get('status')} "
            f"title={s.get('title') or '-'} updated_at={s.get('updated_at')} doc={doc_ref}"
        )


def load_session_snapshot(base_url, session_id, timeout_seconds):
    status, session_snapshot = request_json(
        "GET",
        base_url,
        f"/api/v0/sessions/{session_id}",
        timeout_seconds=timeout_seconds,
    )
    if status != 200:
        return status, session_snapshot, None

    current_doc = session_snapshot.get("current_requirement_doc") or {}
    return status, session_snapshot, {
        "session_id": session_snapshot.get("session_id", session_id),
        "session_title": session_snapshot.get("title", ""),
        "doc_id": current_doc.get("doc_id", ""),
        "latest_version": current_doc.get("current_version"),
        "latest_status": current_doc.get("status", ""),
        "latest_content": current_doc.get("content", "") or "",
    }


def pick_latest_ticket_id(base_url, session_id, timeout_seconds):
    if not session_id:
        return ""
    status, tickets = request_json(
        "GET",
        base_url,
        f"/api/v0/sessions/{session_id}/tickets",
        timeout_seconds=timeout_seconds,
    )
    if status != 200 or not tickets:
        return ""
    latest = tickets[0] if tickets else {}
    return latest.get("ticket_id") or ""


def trigger_architect_auto(base_url, session_id, timeout_seconds, max_tickets=8):
    payload = {
        "session_id": session_id,
        "max_tickets": max_tickets,
    }
    return request_json(
        "POST",
        base_url,
        "/api/v0/architect/auto-process",
        payload,
        timeout_seconds=timeout_seconds,
    )


def wait_for_architect_waiting_user(base_url, session_id, timeout_seconds, min_count=1):
    deadline = time.time() + timeout_seconds
    latest_tickets = []
    while time.time() < deadline:
        status, tickets = request_json(
            "GET",
            base_url,
            f"/api/v0/sessions/{session_id}/tickets",
            params={"status": "WAITING_USER", "assignee_role": "architect_agent"},
            timeout_seconds=min(timeout_seconds, 30),
        )
        if status == 200 and tickets is not None:
            latest_tickets = tickets
            if len(tickets) >= min_count:
                return tickets
        time.sleep(0.8)
    return latest_tickets


def wait_for_ticket_status(base_url, session_id, ticket_id, expected_status, timeout_seconds):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        status, tickets = request_json(
            "GET",
            base_url,
            f"/api/v0/sessions/{session_id}/tickets",
            params={"assignee_role": "architect_agent"},
            timeout_seconds=min(timeout_seconds, 30),
        )
        if status == 200 and tickets is not None:
            for ticket in tickets:
                if ticket.get("ticket_id") == ticket_id and ticket.get("status") == expected_status:
                    return ticket
        time.sleep(0.8)
    return None


def print_event_rows(events):
    print(f"events={len(events)}")
    for e in events:
        body = (e.get("body") or "").replace("\n", " ")
        data = e.get("data_json") or ""
        if len(data) > 200:
            data = data[:200] + "..."
        print(
            f"- {e.get('event_id')} type={e.get('event_type')} actor={e.get('actor_role')} "
            f"at={e.get('created_at')} body={body}"
        )
        if data:
            print(f"  data_json={data}")


def main():
    parser = argparse.ArgumentParser(description="Interactive requirement-agent console.")
    parser.add_argument("--start-backend", action="store_true", help="Start backend automatically.")
    parser.add_argument("--port", type=int, default=18082, help="Backend port when starting automatically.")
    parser.add_argument(
        "--provider",
        default=os.getenv("AGENTX_REQUIREMENT_LLM_PROVIDER", "bailian"),
        help="LLM provider for backend when auto-starting (mock|bailian).",
    )
    parser.add_argument("--timeout-seconds", type=int, default=180, help="HTTP wait and call timeout.")
    parser.add_argument(
        "--base-url",
        default=os.getenv("AGENTX_BASE_URL", "http://127.0.0.1:8080"),
        help="Base URL when not auto-starting backend.",
    )
    parser.add_argument("--session-id", default="", help="Use an existing session id.")
    parser.add_argument("--title", default="", help="Session title (and first requirement doc title).")
    parser.add_argument(
        "--print-doc-every-turn",
        action="store_true",
        help="Always print full markdown after each draft response.",
    )
    args = parser.parse_args()

    backend_proc = None
    stdout_handle = None
    stderr_handle = None
    started_port = 0
    base_url = args.base_url
    spring_stdout = ""
    spring_stderr = ""

    try:
        if args.start_backend:
            chosen_port = pick_free_port(args.port)
            if chosen_port != args.port:
                print(f"Requested port {args.port} is busy, switched to free port {chosen_port}.")
            backend_proc, stdout_handle, stderr_handle, spring_stdout, spring_stderr = start_backend(
                chosen_port, args.provider
            )
            if not wait_port("127.0.0.1", chosen_port, args.timeout_seconds):
                print("Backend did not start within timeout.")
                print("spring stdout:", spring_stdout)
                print("spring stderr:", spring_stderr)
                return 1
            started_port = chosen_port
            base_url = f"http://127.0.0.1:{chosen_port}"

        session_id = args.session_id.strip()
        session_title = args.title.strip()
        doc_id = ""
        latest_content = ""
        latest_version = None
        latest_status = ""

        if session_id:
            status, snapshot, loaded = load_session_snapshot(base_url, session_id, args.timeout_seconds)
            if status != 200:
                print("Failed to load existing session:", status, snapshot)
                return 1
            session_id = loaded["session_id"]
            if not session_title:
                session_title = loaded["session_title"] or session_title
            doc_id = loaded["doc_id"] or ""
            latest_version = loaded["latest_version"]
            latest_status = loaded["latest_status"] or ""
            latest_content = loaded["latest_content"] or ""
            print(
                f"Loaded existing session: {session_id}, "
                f"doc_id={doc_id or '-'}, version={latest_version if latest_version is not None else '-'}, "
                f"doc_status={latest_status or '-'}"
            )
        else:
            if not session_title:
                startup_input = input(
                    "Session title for new session, or existing SESSION_ID to resume "
                    "(press Enter to skip): "
                ).strip()
                if looks_like_session_id(startup_input):
                    status, snapshot, loaded = load_session_snapshot(base_url, startup_input, args.timeout_seconds)
                    if status != 200:
                        print("Failed to load existing session:", status, snapshot)
                    else:
                        session_id = loaded["session_id"]
                        session_title = loaded["session_title"] or session_title
                        doc_id = loaded["doc_id"] or ""
                        latest_version = loaded["latest_version"]
                        latest_status = loaded["latest_status"] or ""
                        latest_content = loaded["latest_content"] or ""
                        print(
                            f"Loaded existing session: {session_id}, "
                            f"doc_id={doc_id or '-'}, version={latest_version if latest_version is not None else '-'}, "
                            f"doc_status={latest_status or '-'}"
                        )
                else:
                    session_title = startup_input
            if session_title and not session_id:
                status, session = request_json(
                    "POST",
                    base_url,
                    "/api/v0/sessions",
                    {"title": session_title},
                    timeout_seconds=args.timeout_seconds,
                )
                if status != 200:
                    print("Failed to create session:", status, session)
                    return 1
                session_id = session["session_id"]
                print(f"Session created: {session_id}")
            elif not session_id:
                print("No active session selected. Use /sessions then /enter <SESSION_ID>, or /new <TITLE>.")

        last_ticket_id = ""
        print_help()
        print(f"\nInteractive started. session_id={session_id or '-'}")

        while True:
            try:
                user_text = input("\nyou> ").strip()
            except EOFError:
                print("Input stream closed. Exiting.")
                break
            if not user_text:
                continue

            if user_text in {"/quit", "/exit"}:
                print("Bye.")
                break

            if user_text == "/help":
                print_help()
                continue

            if user_text.startswith("/architect-auto"):
                parts = user_text.split()
                target_session = session_id
                arg_index = 1
                if len(parts) >= 2 and parts[1].upper().startswith("SES-"):
                    target_session = parts[1]
                    arg_index = 2
                if not target_session:
                    print("No active session. Use /sessions then /enter <SESSION_ID>, or /new <TITLE>.")
                    continue
                max_tickets = 8
                if len(parts) > arg_index:
                    try:
                        max_tickets = int(parts[arg_index])
                    except ValueError:
                        print("MAX must be integer")
                        continue
                status, result = trigger_architect_auto(base_url, target_session, args.timeout_seconds, max_tickets)
                if status == 404:
                    print("Architect auto-processor endpoint not found on backend.")
                    continue
                if status != 200:
                    print("Architect auto-processor failed:", status, result)
                    continue
                print(
                    "architect auto processed: "
                    f"count={result.get('processed_count')}, "
                    f"processed={result.get('processed_ticket_ids')}, "
                    f"skipped={result.get('skipped_ticket_ids')}"
                )
                continue

            if user_text.startswith("/new"):
                parts = user_text.split(maxsplit=1)
                if len(parts) < 2 or not parts[1].strip():
                    print("Usage: /new <TITLE>")
                    continue
                create_title = parts[1].strip()
                status, session = request_json(
                    "POST",
                    base_url,
                    "/api/v0/sessions",
                    {"title": create_title},
                    timeout_seconds=args.timeout_seconds,
                )
                if status != 200:
                    print("Create session failed:", status, session)
                    continue
                session_id = session.get("session_id", "")
                session_title = create_title
                doc_id = ""
                latest_content = ""
                latest_version = None
                latest_status = ""
                last_ticket_id = ""
                print(f"Session created and entered: {session_id}, title={session_title}")
                continue

            if user_text.startswith("/sessions"):
                parts = user_text.split(maxsplit=1)
                keyword = parts[1].strip().lower() if len(parts) > 1 else ""
                status, sessions = request_json(
                    "GET",
                    base_url,
                    "/api/v0/sessions",
                    timeout_seconds=args.timeout_seconds,
                )
                if status != 200:
                    print("Session list failed:", status, sessions)
                    continue
                if keyword:
                    sessions = [
                        s for s in sessions
                        if keyword in (s.get("session_id", "") + " " + (s.get("title") or "")).lower()
                    ]
                print_session_rows(sessions)
                if not sessions:
                    print("No session matched.")
                continue

            if user_text.startswith("/load") or user_text.startswith("/enter"):
                command_name = "/enter" if user_text.startswith("/enter") else "/load"
                parts = user_text.split(maxsplit=1)
                if len(parts) < 2 or not parts[1].strip():
                    print(f"Usage: {command_name} <SESSION_ID>")
                    continue
                target_session_id = parts[1].strip()
                if not looks_like_session_id(target_session_id):
                    print("Invalid SESSION_ID. Expected format: SES-<id>")
                    continue
                status, snapshot, loaded = load_session_snapshot(base_url, target_session_id, args.timeout_seconds)
                if status != 200:
                    print("Load failed:", status, snapshot)
                    continue
                session_id = loaded["session_id"]
                session_title = loaded["session_title"] or session_title
                doc_id = loaded["doc_id"] or ""
                latest_version = loaded["latest_version"]
                latest_status = loaded["latest_status"] or ""
                latest_content = loaded["latest_content"] or ""
                last_ticket_id = ""
                print(
                    f"Loaded session={session_id}, title={session_title or '-'}, "
                    f"doc_id={doc_id or '-'}, version={latest_version if latest_version is not None else '-'}, "
                    f"doc_status={latest_status or '-'}"
                )
                if doc_id:
                    print("You can continue incremental requirement updates directly.")
                else:
                    print("This session has no requirement doc yet. Continue discovery chat first.")
                waiting_architect = wait_for_architect_waiting_user(
                    base_url,
                    session_id,
                    min(6, args.timeout_seconds),
                )
                if waiting_architect:
                    print(
                        "architect auto already has pending requests: "
                        f"{[t.get('ticket_id') for t in waiting_architect]}"
                    )
                continue

            if user_text == "/show":
                if not session_id:
                    print("No active session. Use /sessions then /enter <SESSION_ID>, or /new <TITLE>.")
                    continue
                if not latest_content:
                    print("No requirement markdown yet. Send your first requirement message first.")
                else:
                    print("\n=== Latest Requirement Markdown ===\n")
                    print(latest_content)
                continue

            if user_text.startswith("/tickets"):
                if not session_id:
                    print("No active session. Use /sessions then /enter <SESSION_ID>, or /new <TITLE>.")
                    continue
                parts = user_text.split()
                params = parse_ticket_filters(parts[1:])
                status, tickets = request_json(
                    "GET",
                    base_url,
                    f"/api/v0/sessions/{session_id}/tickets",
                    params=params if params else None,
                    timeout_seconds=args.timeout_seconds,
                )
                if status != 200:
                    print("Ticket query failed:", status, tickets)
                    continue
                print_ticket_rows(tickets)
                if tickets:
                    last_ticket_id = tickets[-1].get("ticket_id", last_ticket_id)
                continue

            if user_text.startswith("/events"):
                parts = user_text.split()
                ticket_id = parts[1] if len(parts) > 1 else last_ticket_id
                if ticket_id in {"<上一步ticket_id>", "<last_ticket_id>", "<ticket_id>"}:
                    ticket_id = last_ticket_id
                if not ticket_id:
                    ticket_id = pick_latest_ticket_id(base_url, session_id, args.timeout_seconds)
                    if ticket_id:
                        print(f"Auto selected latest ticket: {ticket_id}")
                    else:
                        print("Usage: /events [TICKET_ID]")
                        continue
                if not looks_like_ticket_id(ticket_id):
                    print("Invalid TICKET_ID. Use /tickets first, then /events <TCK-...>")
                    continue
                status, events = request_json(
                    "GET",
                    base_url,
                    f"/api/v0/tickets/{ticket_id}/events",
                    timeout_seconds=args.timeout_seconds,
                )
                if status != 200:
                    print("Event query failed:", status, events)
                    continue
                print_event_rows(events)
                last_ticket_id = ticket_id
                continue

            if user_text.startswith("/claim"):
                parts = user_text.split()
                ticket_id = last_ticket_id
                arg_index = 1
                if len(parts) >= 2 and parts[1].upper().startswith("TCK-"):
                    ticket_id = parts[1]
                    arg_index = 2
                if not ticket_id:
                    print("Usage: /claim [TICKET_ID] [AGENT_ID] [LEASE_SECONDS]")
                    continue
                claimed_by = parts[arg_index] if len(parts) > arg_index else "architect-agent-cli"
                lease_seconds = 300
                if len(parts) > arg_index + 1:
                    try:
                        lease_seconds = int(parts[arg_index + 1])
                    except ValueError:
                        print("LEASE_SECONDS must be integer")
                        continue
                status, claimed = request_json(
                    "POST",
                    base_url,
                    f"/api/v0/tickets/{ticket_id}/claim",
                    {"claimed_by": claimed_by, "lease_seconds": lease_seconds},
                    timeout_seconds=args.timeout_seconds,
                )
                if status != 200:
                    print("Claim failed:", status, claimed)
                    continue
                last_ticket_id = ticket_id
                print(
                    f"claimed ticket={ticket_id}, status={claimed.get('status')}, "
                    f"lease_until={claimed.get('lease_until')}"
                )
                continue

            if user_text.startswith("/request"):
                command_text = user_text[len("/request"):].strip()
                if not command_text:
                    print("Usage: /request [TICKET_ID] [DECISION|CLARIFICATION] <QUESTION>")
                    continue
                ticket_id = last_ticket_id
                kind = "DECISION"
                if command_text.upper().startswith("TCK-"):
                    first, _, rest = command_text.partition(" ")
                    ticket_id = first.strip()
                    command_text = rest.strip()
                if not ticket_id:
                    print("No ticket selected. Use /tickets first or pass TICKET_ID explicitly.")
                    continue
                if not command_text:
                    print("Question must not be empty")
                    continue

                if " " not in command_text and command_text.upper() in {"DECISION", "CLARIFICATION"}:
                    print("Question must not be empty")
                    continue
                first_token, _, rest = command_text.partition(" ")
                maybe_kind = first_token.strip().upper()
                if maybe_kind in {"DECISION", "CLARIFICATION"}:
                    kind = maybe_kind
                    question = rest.strip()
                else:
                    question = command_text
                if not question:
                    print("Question must not be empty")
                    continue
                data_json = json.dumps(
                    {
                        "request_kind": kind,
                        "question": question,
                        "source_ticket_id": ticket_id,
                        "source": "full_flow_console",
                    },
                    ensure_ascii=False,
                )
                status, event = request_json(
                    "POST",
                    base_url,
                    f"/api/v0/tickets/{ticket_id}/events",
                    {
                        "event_type": "DECISION_REQUESTED",
                        "actor_role": "architect_agent",
                        "body": f"{kind} request: {question}",
                        "data_json": data_json,
                    },
                    timeout_seconds=args.timeout_seconds,
                )
                if status != 200:
                    print("Request append failed:", status, event)
                    continue
                last_ticket_id = ticket_id
                print(f"request event appended: ticket={ticket_id}, event={event.get('event_type')}")
                continue

            if user_text.startswith("/respond"):
                command_text = user_text[len("/respond"):].strip()
                if not command_text:
                    print("Usage: /respond [TICKET_ID] <RESPONSE_TEXT>")
                    continue
                ticket_id = last_ticket_id
                if command_text.upper().startswith("TCK-"):
                    first, _, rest = command_text.partition(" ")
                    ticket_id = first.strip()
                    command_text = rest.strip()
                if not ticket_id:
                    print("No ticket selected. Use /tickets first or pass TICKET_ID explicitly.")
                    continue
                response_text = command_text
                if not response_text:
                    print("Response text must not be empty")
                    continue
                data_json = json.dumps(
                    {
                        "response_text": response_text,
                        "source_ticket_id": ticket_id,
                    },
                    ensure_ascii=False,
                )
                status, event = request_json(
                    "POST",
                    base_url,
                    f"/api/v0/tickets/{ticket_id}/events",
                    {
                        "event_type": "USER_RESPONDED",
                        "actor_role": "user",
                        "body": response_text,
                        "data_json": data_json,
                    },
                    timeout_seconds=args.timeout_seconds,
                )
                if status != 200:
                    print("Respond append failed:", status, event)
                    continue
                last_ticket_id = ticket_id
                print(f"user response appended: ticket={ticket_id}, event={event.get('event_type')}")
                done_ticket = wait_for_ticket_status(
                    base_url,
                    session_id,
                    ticket_id,
                    "DONE",
                    min(20, args.timeout_seconds),
                ) if session_id else None
                if done_ticket:
                    print(
                        "architect auto planned work and closed ticket: "
                        f"ticket={ticket_id} status={done_ticket.get('status')}"
                    )
                    continue
                resumed = wait_for_ticket_status(
                    base_url,
                    session_id,
                    ticket_id,
                    "WAITING_USER",
                    min(20, args.timeout_seconds),
                ) if session_id else None
                if resumed:
                    print(
                        "architect auto resumed without manual trigger: "
                        f"ticket={ticket_id} status={resumed.get('status')}"
                    )
                else:
                    print("architect auto resume not observed yet; use /events to inspect progress.")
                continue

            if user_text.startswith("/done"):
                command_text = user_text[len("/done"):].strip()
                ticket_id = last_ticket_id
                if command_text.upper().startswith("TCK-"):
                    first, _, rest = command_text.partition(" ")
                    ticket_id = first.strip()
                    command_text = rest.strip()
                if not ticket_id:
                    print("Usage: /done [TICKET_ID] [SUMMARY]")
                    continue
                summary = command_text if command_text else "Architect ticket closed"
                data_json = json.dumps({"to_status": "DONE", "reason": summary}, ensure_ascii=False)
                status, event = request_json(
                    "POST",
                    base_url,
                    f"/api/v0/tickets/{ticket_id}/events",
                    {
                        "event_type": "STATUS_CHANGED",
                        "actor_role": "architect_agent",
                        "body": summary,
                        "data_json": data_json,
                    },
                    timeout_seconds=args.timeout_seconds,
                )
                if status != 200:
                    print("Close failed:", status, event)
                    continue
                last_ticket_id = ticket_id
                print(f"ticket closed: {ticket_id}, event={event.get('event_type')}")
                continue

            if user_text.startswith("/block"):
                command_text = user_text[len("/block"):].strip()
                ticket_id = last_ticket_id
                if command_text.upper().startswith("TCK-"):
                    first, _, rest = command_text.partition(" ")
                    ticket_id = first.strip()
                    command_text = rest.strip()
                if not ticket_id:
                    print("Usage: /block [TICKET_ID] [REASON]")
                    continue
                reason = command_text if command_text else "Architect ticket blocked"
                data_json = json.dumps({"to_status": "BLOCKED", "reason": reason}, ensure_ascii=False)
                status, event = request_json(
                    "POST",
                    base_url,
                    f"/api/v0/tickets/{ticket_id}/events",
                    {
                        "event_type": "STATUS_CHANGED",
                        "actor_role": "architect_agent",
                        "body": reason,
                        "data_json": data_json,
                    },
                    timeout_seconds=args.timeout_seconds,
                )
                if status != 200:
                    print("Block failed:", status, event)
                    continue
                last_ticket_id = ticket_id
                print(f"ticket blocked: {ticket_id}, event={event.get('event_type')}")
                continue

            if user_text == "/monitor":
                if not session_id:
                    print("No active session. Use /sessions then /enter <SESSION_ID>, or /new <TITLE>.")
                    continue
                print_monitoring_hints(session_id, doc_id)
                continue

            if user_text == "/status":
                print(
                    f"session_id={session_id}, title={session_title or '-'}, doc_id={doc_id or '-'}, "
                    f"version={latest_version if latest_version is not None else '-'}, "
                    f"doc_status={latest_status or '-'}, "
                    f"last_ticket_id={last_ticket_id or '-'}"
                )
                continue

            if user_text == "/confirm":
                if not session_id:
                    print("No active session. Use /sessions then /enter <SESSION_ID>, or /new <TITLE>.")
                    continue
                if not doc_id:
                    print("No doc_id yet. Send at least one requirement message first.")
                    continue
                status, confirmed = request_json(
                    "POST",
                    base_url,
                    f"/api/v0/requirement-docs/{doc_id}/confirm",
                    None,
                    timeout_seconds=args.timeout_seconds,
                )
                if status != 200:
                    print("Confirm failed:", status, confirmed)
                    continue
                latest_status = confirmed.get("status", latest_status)
                print(
                    "Requirement confirmed: "
                    f"doc_id={confirmed.get('doc_id')}, "
                    f"current_version={confirmed.get('current_version')}, "
                    f"confirmed_version={confirmed.get('confirmed_version')}, "
                    f"status={confirmed.get('status')}"
                )
                waiting_architect = wait_for_architect_waiting_user(
                    base_url,
                    session_id,
                    min(20, args.timeout_seconds),
                )
                if waiting_architect:
                    print(
                        "architect auto processed after confirm: "
                        f"tickets={[t.get('ticket_id') for t in waiting_architect]}"
                    )
                else:
                    print("architect auto result not observed yet; use /tickets WAITING_USER to inspect.")
                continue

            if not session_id:
                print("No active session. Use /sessions then /enter <SESSION_ID>, or /new <TITLE>.")
                continue

            payload = {"user_input": user_text, "persist": True}
            if doc_id:
                payload["doc_id"] = doc_id
            else:
                if not session_title:
                    session_title = input("Requirement doc title: ").strip()
                payload["title"] = session_title

            status, draft = request_json(
                "POST",
                base_url,
                f"/api/v0/sessions/{session_id}/requirement-agent/drafts",
                payload,
                timeout_seconds=args.timeout_seconds,
            )
            if status not in {200, 201}:
                print("Draft generation failed:", status, draft)
                continue

            phase = draft.get("phase")
            assistant_message = draft.get("assistant_message")
            if phase:
                print(f"phase={phase}")
            if assistant_message:
                print("assistant>", assistant_message)
            if phase == "HANDOFF_CREATED":
                print("Handoff ticket should be available. Use /tickets OPEN to inspect.")
                waiting_architect = wait_for_architect_waiting_user(
                    base_url,
                    session_id,
                    min(20, args.timeout_seconds),
                )
                if waiting_architect:
                    print(
                        "architect auto processed after handoff: "
                        f"tickets={[t.get('ticket_id') for t in waiting_architect]}"
                    )

            doc_id = draft.get("doc_id") or doc_id
            latest_version = draft.get("version", latest_version)
            latest_status = draft.get("status", latest_status)
            latest_content = draft.get("content", latest_content)
            ready_to_draft = draft.get("ready_to_draft")
            missing_information = draft.get("missing_information") or []

            if doc_id and latest_version is not None:
                print(
                    "Draft updated: "
                    f"doc_id={doc_id}, version={latest_version}, status={latest_status}, "
                    f"provider={draft.get('provider')}, model={draft.get('model')}"
                )

            if ready_to_draft is not None and not ready_to_draft and missing_information:
                print("missing_info:")
                for item in missing_information:
                    print(f"- {item}")

            if latest_content and (args.print_doc_every_turn or phase in {"DRAFT_CREATED", "DRAFT_REVISED"}):
                print("\n=== Requirement Markdown ===\n")
                print(latest_content)

        if spring_stdout:
            print("\nSpring logs:")
            print("stdout:", spring_stdout)
            print("stderr:", spring_stderr)
        return 0
    finally:
        if stdout_handle:
            stdout_handle.flush()
            stdout_handle.close()
        if stderr_handle:
            stderr_handle.flush()
            stderr_handle.close()
        stop_backend_by_port(started_port, backend_proc.pid if backend_proc else None)
        if backend_proc:
            stop_all_agentx_backend_processes()
        if backend_proc and backend_proc.poll() is None:
            backend_proc.terminate()
            try:
                backend_proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                backend_proc.kill()


if __name__ == "__main__":
    sys.exit(main())
