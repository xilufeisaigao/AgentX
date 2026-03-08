package com.agentx.agentxbackend.mergegate.domain.model;

public record MergeCandidate(
    String taskId,
    String mainHeadBefore,
    String mergeCandidateCommit,
    String evidenceRef
) {
}
