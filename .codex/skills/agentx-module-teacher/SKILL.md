---
name: agentx-module-teacher
description: Explain, teach, and walk through a real AgentX module when the user asks to learn a module or names a module such as session, requirement, process, execution, query, or workspace. Use for requests like "讲解 requirement 模块", "带我看 process", or "explain the execution module". Start with a Mermaid module/dependency diagram, then trace the real code path and paste the exact relevant source into the chat instead of making the user jump between files.
---

# AgentX Module Teacher

## Mission

This skill is for teaching the AgentX codebase to a human owner who wants to regain control of the project.

Your job is not to give a shallow summary. Your job is to:

1. Identify the module's place in the real running system.
2. Draw the module and its neighboring dependencies first.
3. Find the real code path in the current repository.
4. Paste the exact source being discussed directly into the chat.
5. Keep answering follow-up questions without forcing the user to jump across files.

Prefer completeness over brevity. If understanding requires long code excerpts, paste them.

## Supported Module Names

Primary module names:

- `session`
- `requirement`
- `ticket`
- `planning`
- `workforce`
- `execution`
- `workspace`
- `mergegate`
- `contextpack`
- `delivery`
- `process`
- `query`

If the user gives a feature name instead of a module name, map it to the nearest real module and state that mapping explicitly before continuing.

Examples:

- "需求确认" usually maps to `requirement` plus `ticket`
- "任务调度" usually maps to `process` plus `planning` plus `execution`
- "clone repo 发布" usually maps to `delivery`
- "进度页" usually maps to `query`

## Minimal Context Intake

Do not load the whole repo blindly.

Always start with:

1. Workspace `AGENTS.md`
2. `docs/learning-path.md`
3. `docs/reference/truth-sources.md`
4. `docs/code-index.md`
5. The target module doc from `docs/modules/<module>.md`
6. The target module's owned tables in `docs/schema/agentx_schema_v0.sql`
7. The real code under `src/main/java/com/agentx/agentxbackend/<module>/`

Load `docs/architecture/end-to-end-chain.md` when the module participates in the main runtime chain.

Load `docs/architecture/runtime-artifacts.md` when the module touches worktree, clone repo, context artifact, or runtime-data directories.

Use `docs/archive/legacy-design-20260317/` only if the current docs are ambiguous. If you cite archive material, label it as historical rather than current truth.

## Code Discovery Rules

Prefer the real package and method names over conceptual descriptions.

Helpful commands:

```powershell
Get-ChildItem src/main/java/com/agentx/agentxbackend/<module> -Recurse -File
Select-String -Path src/main/java/com/agentx/agentxbackend/<module>/**/*.java -Pattern "Controller|Scheduler|Listener|Service|UseCase|Facade|Projector|Assembler"
Select-String -Path src/main/java/com/agentx/agentxbackend/**/*.java -Pattern "<ModuleName>"
```

If `rg` works in the environment, prefer it. If not, use PowerShell search commands.

Do not trust old assumptions about where logic "should" be. Verify with current code.

## Teaching Workflow

Follow this exact order.

### 1. Re-state the module boundary

Start by saying:

- what this module owns
- what it does not own
- which tables are directly owned
- which neighboring modules matter for this explanation

Make clear whether the module is mostly:

- API and state transition
- orchestration
- query/read model
- runtime execution
- git/workspace lifecycle

### 2. Draw the overall dependency graph first

The first substantial artifact in the answer must be a Mermaid graph.

Rules:

1. Put the target module in the center.
2. Include only the most relevant neighboring modules, usually 3 to 6 nodes total.
3. Use verified dependencies only.
4. If the module depends on `process` orchestration, show that explicitly.
5. If the module feeds `query` read models, show that explicitly.

Preferred format:

```mermaid
graph LR
  session["session"] --> requirement["requirement"]
  requirement["requirement"] --> ticket["ticket"]
  process["process"] --> requirement["requirement"]
  requirement["requirement"] --> query["query"]
```

Immediately after that, add a second Mermaid graph for the module's internal layer shape when useful:

```mermaid
graph TD
  api["api"] --> app["application"]
  app["application"] --> domain["domain"]
  infra["infrastructure"] --> domain["domain"]
```

### 3. Explain the runtime position

Before diving into code, explain where the module sits in the real chain:

- which API call or scheduler wakes it up
- which domain state or table row it changes
- which event, ticket, task, run, or query view it produces next

If helpful, give a one-line chain such as:

`session create -> requirement draft -> requirement confirm -> process orchestration -> planning -> execution -> mergegate -> delivery -> query projection`

### 4. Walk the key code in execution order

Do not start with random files.

Pick 3 to 7 code checkpoints in the order the system actually executes them.

For each checkpoint, always include:

1. The class and method name
2. A clickable absolute file path
3. Why this method matters in the chain
4. The exact source code pasted into the chat
5. A short explanation of:
   - inputs
   - outputs
   - state changes
   - table effects
   - downstream calls

Prefer pasting one complete method or one small class at a time. Keep the excerpt contiguous.

Do not say "open this file yourself" or "see the source here" without also pasting the relevant code.

### 5. Handle questions by staying on the same thread

If the user says:

- "继续"
- "这个没懂"
- "展开这个方法"
- "这段代码什么意思"

then continue from the same call chain.

When answering a follow-up:

1. Re-anchor the current position in the chain.
2. Paste the exact method, block, or neighboring method again if needed.
3. Explain it in simpler language.
4. Only then move deeper.

Do not bounce the user to another file unless you also paste the next relevant code block.

## AgentX-Specific Teaching Rules

### Process

For `process`, always show:

- which listener, scheduler, or orchestrator is driving the next step
- which other modules it coordinates
- why it is orchestration instead of data ownership

### Query

For `query`, always separate:

- raw table truth
- aggregation logic
- user-visible fields

Make it explicit when a field is computed rather than stored.

### Requirement

For `requirement`, always show:

- relation to `session`
- relation to `ticket`
- `requirement_docs` and `requirement_doc_versions`
- draft creation versus confirmation

### Execution

For `execution`, always show:

- relation to `workforce`
- relation to `workspace`
- relation to `mergegate`
- `task_context_snapshots`, `task_runs`, `task_run_events`

### Delivery

For `delivery`, always show:

- why `DELIVERED != DONE`
- clone publish path
- git evidence and runtime artifact location

### Contextpack

For `contextpack`, always show:

- snapshot creation
- snapshot reuse
- how `task_runs.context_snapshot_id` binds the selected context

## Output Style

Optimize for teaching, not compression.

Preferred answer shape:

1. "模块图"
2. "模块定位"
3. "运行主线"
4. "关键代码 1/N", "关键代码 2/N", ...
5. "这个模块最容易看错的地方"
6. "如果你下一步继续学，建议看什么"

Use concise prose between code blocks, but do not be stingy with code.

If the user is confused, switch to plainer language and smaller code chunks.

## Hard Rules

1. Do not invent dependency arrows that you did not verify in docs or code.
2. Do not use archived design docs as the main truth source.
3. Do not summarize a method when the user clearly wants the source.
4. Do not optimize for token savings.
5. Do not skip file paths, table names, or method names.
6. Do not jump across unrelated modules before the current chain is clear.

