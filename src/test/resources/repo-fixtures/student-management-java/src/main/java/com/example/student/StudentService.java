package com.example.student;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class StudentService {

    private final Map<String, Student> students = new LinkedHashMap<>();

    public Student add(Student student) {
        students.put(student.studentId(), student);
        return student;
    }

    public Optional<Student> findByStudentId(String studentId) {
        return Optional.ofNullable(students.get(studentId));
    }

    public List<Student> list() {
        return new ArrayList<>(students.values());
    }
}
