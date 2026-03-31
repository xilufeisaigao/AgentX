import test from "node:test";
import assert from "node:assert/strict";

import { handleStudentListRequest, parseStudentListFilter } from "../src/index.ts";

test("api should parse query filter", () => {
  assert.deepEqual(parseStudentListFilter(new URLSearchParams("query=alice")), { query: "alice" });
});

test("api should filter seeded students by query", () => {
  const students = handleStudentListRequest("query=alice");
  assert.equal(students.length, 1);
  assert.equal(students[0].name, "Alice Zhang");
});
