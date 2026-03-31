import test from "node:test";
import assert from "node:assert/strict";

import { normalizeQuery, studentSeed } from "../src/student.ts";

test("shared contract should expose seeded students", () => {
  assert.equal(studentSeed.length, 2);
  assert.equal(studentSeed[0].status, "ACTIVE");
});

test("normalizeQuery should trim and lowercase", () => {
  assert.equal(normalizeQuery("  Alice  "), "alice");
  assert.equal(normalizeQuery("   "), undefined);
});
