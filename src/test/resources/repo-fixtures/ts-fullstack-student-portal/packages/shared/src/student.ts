export type StudentStatus = "ACTIVE" | "INACTIVE";

export interface Student {
  id: string;
  name: string;
  grade: string;
  email: string;
  status: StudentStatus;
}

export interface StudentListFilter {
  query?: string;
}

export const studentSeed: Student[] = [
  {
    id: "S-1001",
    name: "Alice Zhang",
    grade: "2026",
    email: "alice@example.com",
    status: "ACTIVE"
  },
  {
    id: "S-1002",
    name: "Bob Chen",
    grade: "2027",
    email: "bob@example.com",
    status: "INACTIVE"
  }
];

export function normalizeQuery(query?: string): string | undefined {
  const normalized = query?.trim().toLowerCase();
  return normalized ? normalized : undefined;
}
