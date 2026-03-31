import assert from "node:assert/strict";

import { studentSeed } from "../packages/shared/src/student.ts";
import { renderStudentPage } from "../apps/web/src/studentPage.ts";

const page = renderStudentPage(studentSeed, "alice");

assert.equal(page.title, "Student Portal");
assert.ok(page.filterBar.includes("query-input"));
