package com.agentx.agentxbackend.mergegate.application.port.in;

public interface MergeGateCompletionUseCase {

    void completeVerifySuccess(String taskId, String verifyRunId, String mergeCandidateCommit);
}

