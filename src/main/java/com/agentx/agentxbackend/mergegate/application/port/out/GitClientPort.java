package com.agentx.agentxbackend.mergegate.application.port.out;

import com.agentx.agentxbackend.mergegate.domain.model.MergeCandidate;

public interface GitClientPort {

    String readMainHead();

    MergeCandidate rebaseTaskBranch(String taskId, String mainHeadBefore);

    void fastForwardMain(String mergeCandidateCommit);

    boolean recoverRepositoryIfNeeded();
}
