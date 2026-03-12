package com.agentx.agentxbackend.contextpack.application;

import com.agentx.agentxbackend.contextpack.domain.model.TaskSkill;
import com.agentx.agentxbackend.contextpack.application.port.out.RepoContextQueryPort;

import java.util.List;
import java.util.Locale;

final class TaskSkillTemplateSupport {

    private TaskSkillTemplateSupport() {
    }

    static List<String> buildConventions(String taskTemplateId) {
        String normalized = taskTemplateId == null ? "" : taskTemplateId.trim().toLowerCase(Locale.ROOT);
        if ("tmpl.init.v0".equals(normalized)) {
            return List.of(
                "Bootstrap only the repository baseline needed for downstream tasks to start safely.",
                "If the confirmed requirement already specifies a framework, runtime, or build stack, the scaffold must use that exact stack instead of a generic language-only skeleton.",
                "Treat framework alignment as part of init scope: update build config, app entrypoint, and minimal runtime config as needed to match the confirmed requirement.",
                "Do not ask for permission to adopt Spring Boot or another explicitly required framework during init unless the requirement is silent or conflicting.",
                "Limit changes to scaffold files such as build config, app entrypoint, minimal runtime config, and top-level docs.",
                "Do not implement business endpoints, controllers, feature services, or feature tests during init."
            );
        }
        if ("tmpl.verify.v0".equals(normalized)) {
            return List.of(
                "Do not widen verification scope without explicit evidence requirements.",
                "Prefer deterministic commands and capture concise failure reasons."
            );
        }
        return List.of(
            "Respect module boundaries and dependency direction (api -> application -> domain <- infrastructure).",
            "Keep edits minimal and directly tied to acceptance criteria.",
            "Update tests for changed transitions/invariants before marking delivery.",
            "Preserve exact endpoint paths, parameter names, and response formats when the requirement gives hard literals.",
            "Preserve user-provided sample values in tests and acceptance examples when they are part of the requirement, instead of silently substituting new names or payloads.",
            "When the requirement explicitly defines a Spring query parameter and default value, keep the literal annotation form such as @RequestParam(\"name\") with defaultValue instead of looser equivalent patterns.",
            "For Spring Boot plain-text endpoints, keep plain-text body semantics without over-constraining framework-added charset suffixes unless the requirement explicitly pins the exact raw Content-Type header.",
            "If a test autowires framework-provided helpers, use only supported test annotations/configuration that actually register those beans."
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
                return List.of("mvn -q test");
            }
            if (hasPython) {
                return List.of("python -m pytest -q");
            }
            return List.of("Run the project-defined verification command set.");
        }
        if ("tmpl.init.v0".equals(normalizedTemplate)) {
            if (hasMaven) {
                return List.of("mvn -q -DskipTests compile");
            }
            if (hasPython) {
                return List.of("python -m compileall src");
            }
            return List.of("Create only the minimal bootstrap scaffold required for later implementation tasks.");
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
        return buildPitfalls(runKind, null);
    }

    static List<String> buildPitfalls(String runKind, String taskTemplateId) {
        String normalizedTemplate = taskTemplateId == null ? "" : taskTemplateId.trim().toLowerCase(Locale.ROOT);
        if ("tmpl.init.v0".equals(normalizedTemplate)) {
            return List.of(
                "Do not implement feature-specific REST endpoints during init.",
                "Do not create feature tests that belong to downstream implementation/test tasks.",
                "Do not widen bootstrap scope just because the confirmed requirement contains later-phase acceptance criteria.",
                "Do not downgrade an explicitly required framework into a plain language skeleton.",
                "Do not raise clarification about adopting a framework that is already mandated by the confirmed requirement."
            );
        }
        if ("VERIFY".equalsIgnoreCase(runKind)) {
            return List.of(
                "Do not auto-fix code during verify-only tasks.",
                "Do not skip failing tests without explicit rationale."
            );
        }
        return List.of(
            "Do not modify out-of-scope modules/files without explicit dependency justification.",
            "Do not bypass failing tests by weakening assertions.",
            "Do not emit placeholder implementations that cannot run.",
            "For Spring Boot MockMvc tests of plain-text String responses, exact contentType(MediaType.TEXT_PLAIN_VALUE) assertions can fail because Spring often appends charset; use content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN) unless exact raw headers are explicitly required.",
            "Do not use a standalone contentType() matcher import from MockMvcResultMatchers for that assertion; the compatible matcher belongs on content().",
            "For Spring Boot tests, MockMvc requires @AutoConfigureMockMvc or an equivalent test slice; do not use @AutoConfigureWebMvc as a substitute."
        );
    }

    static List<String> buildExpectedOutputs(String runKind) {
        return buildExpectedOutputs(runKind, null);
    }

    static List<String> buildExpectedOutputs(String runKind, String taskTemplateId) {
        String normalizedTemplate = taskTemplateId == null ? "" : taskTemplateId.trim().toLowerCase(Locale.ROOT);
        if ("tmpl.init.v0".equals(normalizedTemplate)) {
            return List.of(
                "Bootstrap scaffold only, ready for downstream implementation tasks.",
                "Scaffold matches the confirmed framework/runtime/build stack declared in the requirement.",
                "Compile-ready project baseline or equivalent minimal runtime skeleton.",
                "No feature-specific business endpoints or acceptance tests added during init."
            );
        }
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
        return renderTaskSkillMarkdown(skill, null);
    }

    static String renderTaskSkillMarkdown(TaskSkill skill, RepoContextQueryPort.RepoContext repoContext) {
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
        appendRepoContextSection(sb, repoContext);
        return sb.toString();
    }

    private static void appendRepoContextSection(StringBuilder sb, RepoContextQueryPort.RepoContext repoContext) {
        sb.append("## Repo Context (Baseline)\n");
        if (repoContext == null) {
            sb.append("- <none>\n\n");
            return;
        }
        if (repoContext.indexKind() != null && !repoContext.indexKind().isBlank()) {
            sb.append("- index_kind: ").append(repoContext.indexKind().trim()).append('\n');
        }
        if (repoContext.repoHeadRef() != null && !repoContext.repoHeadRef().isBlank()) {
            sb.append("- repo_head_ref: ").append(repoContext.repoHeadRef().trim()).append('\n');
        }
        if (repoContext.queryTerms() != null && !repoContext.queryTerms().isEmpty()) {
            sb.append("- query_terms: ").append(String.join(", ", repoContext.queryTerms())).append('\n');
        }
        if (repoContext.topLevelEntries() != null && !repoContext.topLevelEntries().isEmpty()) {
            sb.append("- top_level: ").append(String.join(", ", repoContext.topLevelEntries())).append('\n');
        }
        sb.append('\n');

        sb.append("### Relevant Files\n");
        if (repoContext.relevantFiles() == null || repoContext.relevantFiles().isEmpty()) {
            sb.append("- <none>\n\n");
        } else {
            int limit = Math.min(18, repoContext.relevantFiles().size());
            for (int i = 0; i < limit; i++) {
                RepoContextQueryPort.ScoredPath file = repoContext.relevantFiles().get(i);
                if (file == null || file.path() == null || file.path().isBlank()) {
                    continue;
                }
                sb.append("- ").append(file.path().trim());
                if (file.score() > 0) {
                    sb.append(" (score=").append(file.score()).append(')');
                }
                if (file.reason() != null && !file.reason().isBlank()) {
                    sb.append(" reason=").append(file.reason().trim());
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        sb.append("### Relevant Excerpts\n");
        if (repoContext.excerpts() == null || repoContext.excerpts().isEmpty()) {
            sb.append("- <none>\n\n");
            return;
        }
        for (RepoContextQueryPort.FileExcerpt excerpt : repoContext.excerpts()) {
            if (excerpt == null || excerpt.path() == null || excerpt.path().isBlank()) {
                continue;
            }
            String text = excerpt.excerpt() == null ? "" : excerpt.excerpt().trim();
            if (text.isBlank()) {
                continue;
            }
            sb.append("[FILE ").append(excerpt.path().trim()).append("]\n");
            sb.append(text).append("\n\n");
        }
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
