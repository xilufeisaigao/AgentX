import type { Student, StudentListFilter } from "../../../packages/shared/src/student.ts";
import { normalizeQuery, studentSeed } from "../../../packages/shared/src/student.ts";

function matchesQuery(student: Student, query: string): boolean {
  return [student.id, student.name, student.grade, student.email]
    .some((value) => value.toLowerCase().includes(query));
}

export function listStudents(filter: StudentListFilter = {}): Student[] {
  const query = normalizeQuery(filter.query);
  return studentSeed.filter((student) => !query || matchesQuery(student, query));
}
