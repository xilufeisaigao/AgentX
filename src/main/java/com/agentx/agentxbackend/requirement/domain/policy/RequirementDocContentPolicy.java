package com.agentx.agentxbackend.requirement.domain.policy;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class RequirementDocContentPolicy {

    public static final String SCHEMA_VERSION = "req_doc_v1";
    public static final String SCHEMA_VERSION_ZH = "req_doc_v1_zh";

    private static final Pattern SUMMARY_HEADING_EN =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?summary\\s*$");
    private static final Pattern GOALS_HEADING_EN =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?goals\\s*$");
    private static final Pattern NON_GOALS_HEADING_EN =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?non-goals\\s*$");
    private static final Pattern SCOPE_HEADING_EN =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?scope\\s*$");
    private static final Pattern ACCEPTANCE_HEADING_EN =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?acceptance\\s+criteria\\s*$");
    private static final Pattern CONSTRAINTS_HEADING_EN =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?value\\s+constraints\\s*$");
    private static final Pattern RISKS_HEADING_EN =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?risks\\s*&\\s*tradeoffs\\s*$");
    private static final Pattern QUESTIONS_HEADING_EN =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?open\\s+questions\\s*$");
    private static final Pattern REFERENCES_HEADING_EN =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?references\\s*$");
    private static final Pattern CHANGELOG_HEADING_EN =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?change\\s+log\\s*$");

    private static final Pattern SUMMARY_HEADING_ZH =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?(?:摘要|概述|简介)\\s*$");
    private static final Pattern GOALS_HEADING_ZH =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?(?:目标|目的)\\s*$");
    private static final Pattern NON_GOALS_HEADING_ZH =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?(?:非目标|不做范围|不包括)\\s*$");
    private static final Pattern SCOPE_HEADING_ZH =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?(?:范围|边界)\\s*$");
    private static final Pattern ACCEPTANCE_HEADING_ZH =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?(?:验收标准|验收条件|成功标准)\\s*$");
    private static final Pattern CONSTRAINTS_HEADING_ZH =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?(?:价值约束|约束条件|价值边界)\\s*$");
    private static final Pattern RISKS_HEADING_ZH =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?(?:风险\\s*(?:&|与|和)?\\s*权衡|风险与取舍|风险和权衡)\\s*$");
    private static final Pattern QUESTIONS_HEADING_ZH =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?(?:开放问题|待确认问题|未决问题)\\s*$");
    private static final Pattern REFERENCES_HEADING_ZH =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?(?:参考|参考资料|引用)\\s*$");
    private static final Pattern CHANGELOG_HEADING_ZH =
        Pattern.compile("(?im)^##\\s*(?:\\d+\\.?\\s*)?(?:变更记录|更新日志|修订记录)\\s*$");

    private static final Pattern GOAL_ITEM =
        Pattern.compile("(?m)^-\\s*\\[G-\\d+\\]\\s+.+$");
    private static final Pattern NON_GOAL_ITEM =
        Pattern.compile("(?m)^-\\s*\\[NG-\\d+\\]\\s+.+$");
    private static final Pattern ACCEPTANCE_ITEM =
        Pattern.compile("(?m)^-\\s*\\[AC-\\d+\\]\\s+.+$");
    private static final Pattern CONSTRAINT_ITEM =
        Pattern.compile("(?m)^-\\s*\\[VC-\\d+\\]\\s+.+$");
    private static final Pattern RISK_ITEM =
        Pattern.compile("(?m)^-\\s*\\[R-\\d+\\]\\s+.+$");
    private static final Pattern QUESTION_ITEM =
        Pattern.compile("(?m)^-\\s*\\[Q-\\d+\\]\\[(OPEN|CLOSED|待确认|已关闭)\\]\\s+.+$");

    private RequirementDocContentPolicy() {
    }

    public static void validateOrThrow(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }

        FrontMatter frontMatter = parseFrontMatter(markdown);
        String schemaVersion = frontMatter.schemaVersion();
        DocSchema schema = resolveSchemaVersion(schemaVersion);
        String body = frontMatter.body();
        if (schema == DocSchema.EN) {
            validateEnglishHeadings(body);
        } else {
            validateChineseHeadings(body);
        }

        requirePattern(body, GOAL_ITEM, "at least one Goals item with [G-#]");
        requirePattern(body, NON_GOAL_ITEM, "at least one Non-Goals item with [NG-#]");
        requirePattern(body, ACCEPTANCE_ITEM, "at least one Acceptance Criteria item with [AC-#]");
        requirePattern(body, CONSTRAINT_ITEM, "at least one Value Constraints item with [VC-#]");
        requirePattern(body, RISK_ITEM, "at least one Risks item with [R-#]");
        requirePattern(body, QUESTION_ITEM, "at least one Open Questions item with [Q-#][OPEN|CLOSED|待确认|已关闭]");
    }

    public static String buildTemplate(String title, String summary, String userInput, String changeLog) {
        String safeTitle = normalizeText(title, "Requirement Document");
        String safeSummary = normalizeText(summary, "TBD summary");
        String safeUserInput = normalizeText(userInput, "TBD user input");
        String safeChangeLog = normalizeText(changeLog, "Initial draft generated by requirement agent.");

        return String.join("\n", List.of(
            "---",
            "schema_version: " + SCHEMA_VERSION,
            "---",
            "",
            "# " + safeTitle,
            "",
            "## 1. Summary",
            safeSummary,
            "",
            "## 2. Goals",
            "- [G-1] Deliver measurable user value from: " + safeUserInput,
            "",
            "## 3. Non-Goals",
            "- [NG-1] Do not define implementation architecture or internal module design here.",
            "",
            "## 4. Scope",
            "### In",
            "- [S-IN-1] Core value-layer requirements directly requested by the user.",
            "### Out",
            "- [S-OUT-1] Architecture decisions, database schema expansion, and worker execution details.",
            "",
            "## 5. Acceptance Criteria",
            "- [AC-1] Given this document, stakeholders can verify completion using business outcomes.",
            "- [AC-2] Requirement updates are versioned and reviewable before confirmation.",
            "",
            "## 6. Value Constraints",
            "- [VC-1] Changes must preserve auditability and explicit user confirmation checkpoints.",
            "",
            "## 7. Risks & Tradeoffs",
            "- [R-1] Faster scope expansion may increase ambiguity and rework.",
            "",
            "## 8. Open Questions",
            "- [Q-1][OPEN] Any missing acceptance detail that blocks confirmation?",
            "",
            "## 9. References",
            "### Decisions",
            "- [DEC-TBD] None yet.",
            "### ADRs",
            "- [ADR-TBD] None yet.",
            "",
            "## 10. Change Log",
            "- v1: " + safeChangeLog,
            ""
        ));
    }

    public static String buildChineseTemplate(String title, String summary, String userInput, String changeLog) {
        String safeTitle = normalizeText(title, "需求文档");
        String safeSummary = normalizeText(summary, "待补充摘要");
        String safeUserInput = normalizeText(userInput, "待补充用户输入");
        String safeChangeLog = normalizeText(changeLog, "由需求 Agent 生成初稿。");

        return String.join("\n", List.of(
            "---",
            "schema_version: " + SCHEMA_VERSION_ZH,
            "---",
            "",
            "# " + safeTitle,
            "",
            "## 1. 摘要",
            safeSummary,
            "",
            "## 2. 目标",
            "- [G-1] 基于用户输入交付可衡量价值：" + safeUserInput,
            "",
            "## 3. 非目标",
            "- [NG-1] 不在需求文档中定义实现架构与模块设计细节。",
            "",
            "## 4. 范围",
            "### 包含",
            "- [S-IN-1] 用户明确提出的价值层需求。",
            "### 不包含",
            "- [S-OUT-1] 架构方案、数据库设计、Worker 执行细节。",
            "",
            "## 5. 验收标准",
            "- [AC-1] 利益相关方可基于业务结果判断需求是否完成。",
            "- [AC-2] 需求修改在确认前可版本化追踪。",
            "",
            "## 6. 价值约束",
            "- [VC-1] 必须保留审计可追溯与用户确认检查点。",
            "",
            "## 7. 风险与权衡",
            "- [R-1] 范围快速扩张会增加歧义和返工风险。",
            "",
            "## 8. 开放问题",
            "- [Q-1][待确认] 是否还有影响确认的验收细节缺失？",
            "",
            "## 9. 参考",
            "### 决策",
            "- [DEC-TBD] 暂无。",
            "### 架构决策记录",
            "- [ADR-TBD] 暂无。",
            "",
            "## 10. 变更记录",
            "- v1: " + safeChangeLog,
            ""
        ));
    }

    private static void validateEnglishHeadings(String body) {
        requireHeading(body, SUMMARY_HEADING_EN, "Summary");
        requireHeading(body, GOALS_HEADING_EN, "Goals");
        requireHeading(body, NON_GOALS_HEADING_EN, "Non-Goals");
        requireHeading(body, SCOPE_HEADING_EN, "Scope");
        requireHeading(body, ACCEPTANCE_HEADING_EN, "Acceptance Criteria");
        requireHeading(body, CONSTRAINTS_HEADING_EN, "Value Constraints");
        requireHeading(body, RISKS_HEADING_EN, "Risks & Tradeoffs");
        requireHeading(body, QUESTIONS_HEADING_EN, "Open Questions");
        requireHeading(body, REFERENCES_HEADING_EN, "References");
        requireHeading(body, CHANGELOG_HEADING_EN, "Change Log");
    }

    private static void validateChineseHeadings(String body) {
        requireHeading(body, SUMMARY_HEADING_ZH, "摘要");
        requireHeading(body, GOALS_HEADING_ZH, "目标");
        requireHeading(body, NON_GOALS_HEADING_ZH, "非目标");
        requireHeading(body, SCOPE_HEADING_ZH, "范围");
        requireHeading(body, ACCEPTANCE_HEADING_ZH, "验收标准");
        requireHeading(body, CONSTRAINTS_HEADING_ZH, "价值约束");
        requireHeading(body, RISKS_HEADING_ZH, "风险与权衡");
        requireHeading(body, QUESTIONS_HEADING_ZH, "开放问题");
        requireHeading(body, REFERENCES_HEADING_ZH, "参考");
        requireHeading(body, CHANGELOG_HEADING_ZH, "变更记录");
    }

    private static DocSchema resolveSchemaVersion(String schemaVersion) {
        if (SCHEMA_VERSION.equalsIgnoreCase(schemaVersion)) {
            return DocSchema.EN;
        }
        if (SCHEMA_VERSION_ZH.equalsIgnoreCase(schemaVersion)) {
            return DocSchema.ZH;
        }
        throw new IllegalArgumentException(
            "content schema_version must be " + SCHEMA_VERSION + " or " + SCHEMA_VERSION_ZH
                + ", but got: " + schemaVersion
        );
    }

    private static String normalizeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static void requireHeading(String content, Pattern pattern, String headingName) {
        if (!pattern.matcher(content).find()) {
            throw new IllegalArgumentException("content must include heading: " + headingName);
        }
    }

    private static void requirePattern(String content, Pattern pattern, String requirement) {
        if (!pattern.matcher(content).find()) {
            throw new IllegalArgumentException("content must include " + requirement);
        }
    }

    private static FrontMatter parseFrontMatter(String markdown) {
        String normalized = markdown.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        if (lines.length < 3 || !"---".equals(lines[0].trim())) {
            throw new IllegalArgumentException(
                "content must start with front matter and include schema_version"
            );
        }

        int endIdx = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                endIdx = i;
                break;
            }
        }
        if (endIdx < 0) {
            throw new IllegalArgumentException("content front matter is not closed");
        }

        String schemaVersion = "";
        for (int i = 1; i < endIdx; i++) {
            String line = lines[i].trim();
            if (line.toLowerCase(Locale.ROOT).startsWith("schema_version:")) {
                schemaVersion = line.substring("schema_version:".length()).trim();
                break;
            }
        }
        if (schemaVersion.isBlank()) {
            throw new IllegalArgumentException("content front matter must include schema_version");
        }

        StringBuilder body = new StringBuilder();
        for (int i = endIdx + 1; i < lines.length; i++) {
            body.append(lines[i]);
            if (i < lines.length - 1) {
                body.append('\n');
            }
        }
        return new FrontMatter(schemaVersion, body.toString());
    }

    private record FrontMatter(String schemaVersion, String body) {
    }

    private enum DocSchema {
        EN,
        ZH
    }
}
