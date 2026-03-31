# Fixture Guidelines

Repo fixture seeding currently lives in:

`src/test/java/com/agentx/platform/support/eval/RealWorkflowEvalFixtures.java`

Guidelines:

1. Keep fixture repos minimal
2. Seed only what the real workflow needs as starting context
3. Commit the seeded fixture once before the workflow starts
4. Prefer one named fixture method per fixture id
5. Keep the fixture domain realistic enough to exercise architect/coding/verify behavior

If a new scenario only changes prompt or expected behavior, do not create a new fixture id unnecessarily.
