import type { StudentListFilter } from "../../../packages/shared/src/student.ts";

export interface StudentFilterViewModel {
  searchValue: string;
  availableStatuses: string[];
  filter: StudentListFilter;
}

export function buildStudentFilterViewModel(searchValue = ""): StudentFilterViewModel {
  const trimmed = searchValue.trim();
  return {
    searchValue,
    availableStatuses: ["ALL"],
    filter: trimmed ? { query: trimmed } : {}
  };
}
