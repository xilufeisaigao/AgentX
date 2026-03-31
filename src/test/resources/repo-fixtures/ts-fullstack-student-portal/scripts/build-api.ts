import assert from "node:assert/strict";

import { handleStudentListRequest, parseStudentListFilter } from "../apps/api/src/index.ts";

assert.deepEqual(parseStudentListFilter(new URLSearchParams("query=alice")), { query: "alice" });
assert.equal(handleStudentListRequest("query=alice").length, 1);
