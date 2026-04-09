---
name: agentx-interview-bank-curator
description: Curate and maintain AgentX interview question docs. Use when the user wants to archive new interview questions, merge them into existing topic docs, create new topic docs when needed, or keep answers aligned with current truth vs target design.
---

# AgentX Interview Bank Curator

Use this skill when the user asks to:

1. archive interview questions discussed in chat
2. add new interview questions into the repo docs
3. reorganize existing interview docs by module or importance
4. create a new interview topic doc when no suitable topic exists

## Fixed storage rules

Interview questions live under:

1. `docs/interview/README.md`
2. `docs/interview/*.md`

Never mix interview Q&A directly into runtime truth docs unless the user explicitly asks for that.

## Minimal loading path

Read these files first:

1. `AGENTS.md`
2. `docs/interview/README.md`
3. the nearest matching doc under `docs/interview/*.md`
4. the source-of-truth architecture/runtime/doc files needed to answer the question correctly

Only load the relevant truth docs for the topic. Do not bulk-read the entire docs tree unless necessary.

## Classification workflow

For each new question:

1. decide the nearest existing topic doc
2. if a suitable topic doc exists, insert the question there
3. if no suitable topic doc exists, create a new doc under `docs/interview/`
4. update `docs/interview/README.md` to link the new topic doc

Preferred topic grouping:

1. runtime / rag / retrieval / context compilation / agent frameworks
2. architecture / layering / state machine / workflow orchestration
3. controlplane / API / query / UI
4. evaluation / reports / scenario packs
5. database / persistence / table truth
6. general agent / system design / product design

## Entry format

Every question entry must obey these rules:

1. do not use numeric question ordering
2. use a heading in the form `### [重要程度：高|中|低] 问题`
3. keep one question per section
4. answers should be complete enough for interview prep, not just short notes
5. when needed, clearly distinguish:
   - current code truth
   - target design / pending implementation
6. when a question is about project design, architecture, runtime boundaries, or trade-offs, answer through AgentX's actual project design rather than only giving a generic industry answer

## Ordering rules

Inside one topic doc:

1. keep higher-importance questions earlier
2. keep closely related questions adjacent
3. when inserting a new question, prefer adding it near similar questions instead of appending blindly

## Answer quality rules

Each answer should:

1. explain the project-specific reason, not just a generic concept definition
2. preserve AgentX fixed workflow and three-layer boundaries
3. avoid inventing parallel concepts
4. clearly mark when something is current truth versus target design
5. stay reusable for future interview prep
6. if the question is design-related, explicitly connect the answer back to:
   - fixed workflow
   - three-layer architecture
   - context compilation boundary
   - structured-truth-first principle

## Hard guardrails

Never do these things:

1. rewrite runtime truth docs to pretend an unimplemented feature already exists
2. merge interview-style explanations into domain truth docs without explicit user request
3. use numbering for the interview questions
4. omit importance labels
5. create duplicate topic docs when an existing one already fits

## Response style

When curating:

1. say where the question was inserted
2. mention whether it went into an existing topic doc or a new doc
3. mention if the answer was written from current truth, target design, or both
