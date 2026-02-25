package com.agentx.agentxbackend.mergegate.application.port.out;

public interface RunCreationPort {

    String createVerifyRun(String taskId, String mergeCandidateCommit);
}
