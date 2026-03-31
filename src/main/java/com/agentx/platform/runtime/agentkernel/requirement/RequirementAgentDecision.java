package com.agentx.platform.runtime.agentkernel.requirement;

import java.util.List;
import java.util.Objects;

public record RequirementAgentDecision(
        RequirementDecisionType decision,
        List<String> gaps,
        List<String> questions,
        String draftTitle,
        String draftContent,
        String summary
) {

    public RequirementAgentDecision {
        Objects.requireNonNull(decision, "decision must not be null");
        gaps = List.copyOf(gaps == null ? List.of() : gaps);
        questions = List.copyOf(questions == null ? List.of() : questions);
        summary = summary == null ? "" : summary.trim();
        if (decision == RequirementDecisionType.NEED_INPUT && gaps.isEmpty() && questions.isEmpty()) {
            throw new IllegalArgumentException("need-input decisions must include at least one gap or question");
        }
        if (decision == RequirementDecisionType.DRAFT_READY) {
            if (draftTitle == null || draftTitle.isBlank()) {
                throw new IllegalArgumentException("draft-ready decisions must include draftTitle");
            }
            if (draftContent == null || draftContent.isBlank()) {
                throw new IllegalArgumentException("draft-ready decisions must include draftContent");
            }
        }
    }
}
