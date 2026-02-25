package com.agentx.agentxbackend.contextpack.application;

import com.agentx.agentxbackend.contextpack.domain.model.TaskSkill;

import java.util.List;
import java.util.Locale;

final class TaskSkillTemplateSupport {

    private TaskSkillTemplateSupport() {
    }

    static List<String> buildConventions(String taskTemplateId) {
        String normalized = taskTemplateId == null ? "" : taskTemplateId.trim().toLowerCase(Locale.ROOT);
        if ("tmpl.verify.v0".equals(normalized)) {
            return List.of(
                "Do not widen verification scope without explicit evidence requirements.",
                "Prefer deterministic commands and capture concise failure reasons."
            );
        }
        return List.of(
            "Respect module boundaries and dependency direction (api -> application -> domain <- infrastructure).",
            "Keep edits minimal and directly tied to acceptance criteria.",
            "Update tests for changed transitions/invariants before marking delivery."
        );
    }

    static List<String> buildRecommendedCommands(
        String taskTemplateId,
        List<String> requiredToolpackIds,
        String runKind
    ) {
        boolean hasMaven = requiredToolpackIds.stream().anyMatch(id -> "TP-MAVEN-3".equalsIgnoreCase(id));
        boolean hasPython = requiredToolpackIds.stream().anyMatch(id -> "TP-PYTHON-3".equalsIgnoreCase(id));
        String normalizedTemplate = taskTemplateId == null ? "" : taskTemplateId.trim().toLowerCase(Locale.ROOT);
        if ("VERIFY".equalsIgnoreCase(runKind)) {
            if ("tmpl.init.v0".equals(normalizedTemplate)) {
                if (hasMaven) {
                    return List.of("mvn -q -DskipTests validate");
                }
                if (hasPython) {
                    return List.of("python -m pytest -q");
                }
                return List.of("Run compile-only verification for baseline initialization.");
            }
            if (hasMaven) {
                return List.of("mvn -q test", "mvn -q -Dtest=* verify");
            }
            if (hasPython) {
                return List.of("python -m pytest -q");
            }
            return List.of("Run the project-defined verification command set.");
        }
        List<String> commands = new java.util.ArrayList<>();
        if (hasMaven) {
            commands.add("mvn -q -DskipTests compile");
            commands.add("mvn -q test");
        }
        if (hasPython) {
            commands.add("python -m pytest -q");
        }
        if (commands.isEmpty()) {
            commands.add("Use module-specific build/test commands declared in project docs.");
        }
        return List.copyOf(commands);
    }

    static List<String> buildPitfalls(String runKind) {
        if ("VERIFY".equalsIgnoreCase(runKind)) {
            return List.of(
                "Do not auto-fix code during verify-only tasks.",
                "Do not skip failing tests without explicit rationale."
            );
        }
        return List.of(
            "Do not modify out-of-scope modules/files without explicit dependency justification.",
            "Do not bypass failing tests by weakening assertions.",
            "Do not emit placeholder implementations that cannot run."
        );
    }

    static List<String> buildExpectedOutputs(String runKind) {
        if ("VERIFY".equalsIgnoreCase(runKind)) {
            return List.of("Verification summary with pass/fail evidence and linked commands.");
        }
        return List.of(
            "Code changes aligned to task scope.",
            "Updated tests and concise verification evidence.",
            "Ticket comments/events describing delivered artifacts and residual risks."
        );
    }

    static String renderTaskSkillMarkdown(TaskSkill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Task Skill\n\n");
        sb.append("- skill_id: ").append(nullSafe(skill.skillId())).append('\n');
        sb.append("- task_id: ").append(nullSafe(skill.taskId())).append('\n');
        sb.append("- generated_at: ").append(nullSafe(skill.generatedAt() == null ? null : skill.generatedAt().toString())).append("\n\n");

        appendSection(sb, "Source Fragments", skill.sourceFragments());
        appendSection(sb, "Toolpack Assumptions", skill.toolpackAssumptions());
        appendSection(sb, "Conventions", skill.conventions());
        appendSection(sb, "Recommended Commands", skill.recommendedCommands());
        appendSection(sb, "Pitfalls", skill.pitfalls());
        appendSection(sb, "Stop Rules", skill.stopRules());
        appendSection(sb, "Expected Outputs", skill.expectedOutputs());
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, List<String> lines) {
        sb.append("## ").append(title).append('\n');
        if (lines == null || lines.isEmpty()) {
            sb.append("- <none>\n\n");
            return;
        }
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                sb.append("- ").append(line.trim()).append('\n');
            }
        }
        sb.append('\n');
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
