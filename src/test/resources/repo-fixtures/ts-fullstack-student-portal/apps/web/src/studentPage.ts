import type { Student } from "../../../packages/shared/src/student.ts";
import { buildStudentFilterViewModel } from "./studentFilters.ts";

export interface StudentPageModel {
  title: string;
  filterBar: string[];
  students: Student[];
}

export function renderStudentPage(students: Student[], searchValue = ""): StudentPageModel {
  const filterModel = buildStudentFilterViewModel(searchValue);
  return {
    title: "Student Portal",
    filterBar: ["query-input", ...filterModel.availableStatuses.map((status) => "status-" + status.toLowerCase())],
    students
  };
}
