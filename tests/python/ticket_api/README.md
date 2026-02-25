# Ticket API Python Tests

## Coverage

`test_ticket_api.py` validates the ticket workflow against real backend + real MySQL:

1. Create ARCH_REVIEW ticket (`OPEN`)
2. Claim ticket (`IN_PROGRESS`)
3. Append `DECISION_REQUESTED` on the same ticket -> enters `WAITING_USER`
4. Query `GET /sessions/{sessionId}/tickets` with `status + assignee_role + type` filters
5. Append `USER_RESPONDED` -> returns `IN_PROGRESS`
6. Append `STATUS_CHANGED(to_status=DONE)` -> ticket closed
7. Invalid assignee role returns `400 BAD_REQUEST`
8. Illegal status transition via `STATUS_CHANGED` returns `409 CONFLICT`
9. Claim terminal ticket returns `409 CONFLICT`
10. Architect auto-processor (`POST /api/v0/architect/auto-process`) consumes `HANDOFF/ARCH_REVIEW` and emits `DECISION_REQUESTED` with `request_kind=DECISION|CLARIFICATION`
11. Ticket event replay API (`GET /api/v0/tickets/{ticketId}/events`) returns generated event chain for audit
12. Test data is isolated by unique `session_id`; script does not delete existing records.
13. Scheduler-driven architect continuation: after `USER_RESPONDED`, backend auto-plans `work_modules/work_tasks`, appends `ARTIFACT_LINKED`, and closes ticket to `DONE` (covered by `test_architect_auto_scheduler_flow.py`)

## Run

```powershell
python tests/python/ticket_api/test_ticket_api.py
python tests/python/ticket_api/test_architect_auto_scheduler_flow.py
```

可选环境变量（用于真实 LLM 场景下的慢速链路）：

```powershell
$env:AGENTX_ARCH_AUTO_HTTP_TIMEOUT_SECONDS="20"
$env:AGENTX_ARCH_AUTO_EVENT_WAIT_SECONDS="120"
$env:AGENTX_ARCH_AUTO_STATUS_WAIT_SECONDS="180"
$env:AGENTX_ARCH_AUTO_DONE_WAIT_SECONDS="240"
```
