package com.agentx.platform.runtime.agentkernel.coding;

import com.agentx.platform.runtime.tooling.ToolCall;

import java.util.Objects;

public record CodingAgentDecision(
        CodingDecisionType decisionType,
        ToolCall toolCall,
        String blockerTitle,
        String blockerBody,
        String summary
) {

    public CodingAgentDecision {
        Objects.requireNonNull(decisionType, "decisionType must not be null");
        if (decisionType == CodingDecisionType.TOOL_CALL && toolCall == null) {
            throw new IllegalArgumentException("toolCall must be present for TOOL_CALL decisions");
        }
        if (decisionType != CodingDecisionType.TOOL_CALL && toolCall != null) {
            throw new IllegalArgumentException("toolCall must be null unless decisionType is TOOL_CALL");
        }
        if (decisionType == CodingDecisionType.ASK_BLOCKER
                && (blockerTitle == null || blockerTitle.isBlank())
                && (blockerBody == null || blockerBody.isBlank())) {
            throw new IllegalArgumentException("ASK_BLOCKER requires blocker details");
        }
        summary = summary == null || summary.isBlank()
                ? defaultSummary(decisionType, toolCall, blockerTitle, blockerBody)
                : summary;
    }

    private static String defaultSummary(
            CodingDecisionType decisionType,
            ToolCall toolCall,
            String blockerTitle,
            String blockerBody
    ) {
        return switch (decisionType) {
            case TOOL_CALL -> toolCall == null ? "tool call" : toolCall.summary();
            case ASK_BLOCKER -> blockerTitle != null && !blockerTitle.isBlank()
                    ? blockerTitle
                    : blockerBody == null || blockerBody.isBlank() ? "ask blocker" : blockerBody;
            case DELIVER -> "deliver task candidate";
        };
    }
}
