package com.agentx.agentxbackend.mergegate.application.port.out;

import com.agentx.agentxbackend.mergegate.domain.model.MergeCandidate;

public interface GitClientPort {

    String readMainHead(String sessionId);

    MergeCandidate rebaseTaskBranch(String sessionId, String taskId, String mainHeadBefore);

    void fastForwardMain(String sessionId, String mergeCandidateCommit);

    void ensureDeliveryTagOnMain(String sessionId, String mergeCandidateCommit);

    boolean recoverRepositoryIfNeeded();
}
