package com.agentx.agentxbackend.requirement.domain.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementDocContentPolicyTest {

    @Test
    void validateOrThrowShouldAcceptStandardTemplate() {
        String markdown = RequirementDocContentPolicy.buildTemplate(
            "Payment Requirement",
            "Support secure payment workflows.",
            "User needs checkout with auditability.",
            "Initial draft"
        );

        assertDoesNotThrow(() -> RequirementDocContentPolicy.validateOrThrow(markdown));
    }

    @Test
    void validateOrThrowShouldAcceptChineseTemplate() {
        String markdown = RequirementDocContentPolicy.buildChineseTemplate(
            "支付需求文档",
            "支持安全支付流程。",
            "用户需要结算流程并可审计。",
            "初始草稿"
        );

        assertDoesNotThrow(() -> RequirementDocContentPolicy.validateOrThrow(markdown));
    }

    @Test
    void validateOrThrowShouldRejectWhenSchemaVersionMissing() {
        String markdown = """
            # Missing front matter
            ## 1. Summary
            x
            """;

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> RequirementDocContentPolicy.validateOrThrow(markdown)
        );
        assertTrue(ex.getMessage().contains("schema_version"));
    }

    @Test
    void validateOrThrowShouldRejectWhenMandatorySectionMissing() {
        String markdown = RequirementDocContentPolicy.buildTemplate(
            "X",
            "Y",
            "Z",
            "init"
        ).replace("## 5. Acceptance Criteria", "## 5. Completion Checks");

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> RequirementDocContentPolicy.validateOrThrow(markdown)
        );
        assertTrue(ex.getMessage().contains("Acceptance Criteria"));
    }

    @Test
    void validateOrThrowShouldRejectWhenChineseMandatorySectionMissing() {
        String markdown = RequirementDocContentPolicy.buildChineseTemplate(
            "支付需求文档",
            "支持安全支付流程。",
            "用户需要结算流程并可审计。",
            "初始草稿"
        ).replace("## 5. 验收标准", "## 5. 完成检查");

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> RequirementDocContentPolicy.validateOrThrow(markdown)
        );
        assertTrue(ex.getMessage().contains("验收标准"));
    }

    @Test
    void validateOrThrowShouldRejectWhenMandatoryTaggedItemMissing() {
        String markdown = RequirementDocContentPolicy.buildTemplate(
            "X",
            "Y",
            "Z",
            "init"
        ).replace("- [VC-1]", "- [RULE-1]");

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> RequirementDocContentPolicy.validateOrThrow(markdown)
        );
        assertTrue(ex.getMessage().contains("Value Constraints"));
    }
}
