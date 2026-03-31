package com.example.student;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentServiceTest {

    @Test
    void shouldAddAndFindStudent() {
        StudentService service = new StudentService();
        service.add(new Student("S-1001", "Alice", "2026", "alice@example.com"));

        assertEquals(1, service.list().size());
        assertTrue(service.findByStudentId("S-1001").isPresent());
    }
}
