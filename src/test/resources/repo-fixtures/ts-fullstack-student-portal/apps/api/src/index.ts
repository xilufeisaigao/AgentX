import type { StudentListFilter } from "../../../packages/shared/src/student.ts";
import { listStudents } from "./studentService.ts";

export function parseStudentListFilter(searchParams: URLSearchParams): StudentListFilter {
  const query = searchParams.get("query")?.trim();
  return query ? { query } : {};
}

export function handleStudentListRequest(search: string) {
  return listStudents(parseStudentListFilter(new URLSearchParams(search)));
}
