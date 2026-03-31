import assert from "node:assert/strict";

import { normalizeQuery, studentSeed } from "../packages/shared/src/student.ts";

assert.equal(normalizeQuery(" Alice "), "alice");
assert.ok(studentSeed.some((student) => student.status === "ACTIVE"));
