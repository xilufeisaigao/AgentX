import assert from "node:assert/strict";

import { studentSeed } from "../packages/shared/src/student.ts";
import { renderStudentPage } from "../apps/web/src/studentPage.ts";

const page = renderStudentPage(studentSeed, "alice");

assert.ok(page.filterBar.includes("status-all"));
assert.equal(page.students[0].status, "ACTIVE");
