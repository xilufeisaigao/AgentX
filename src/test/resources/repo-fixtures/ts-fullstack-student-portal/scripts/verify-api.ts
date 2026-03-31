import assert from "node:assert/strict";

import { handleStudentListRequest } from "../apps/api/src/index.ts";

const students = handleStudentListRequest("query=alice");

assert.equal(students.length, 1);
assert.equal(students[0].email, "alice@example.com");
