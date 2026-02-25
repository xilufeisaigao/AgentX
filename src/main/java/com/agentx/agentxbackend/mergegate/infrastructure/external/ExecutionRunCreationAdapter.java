package com.agentx.agentxbackend.mergegate.infrastructure.external;

import com.agentx.agentxbackend.execution.application.port.in.RunInternalUseCase;
import com.agentx.agentxbackend.mergegate.application.port.out.RunCreationPort;
import org.springframework.stereotype.Component;

@Component
public class ExecutionRunCreationAdapter implements RunCreationPort {

    private final RunInternalUseCase runInternalUseCase;

    public ExecutionRunCreationAdapter(RunInternalUseCase runInternalUseCase) {
        this.runInternalUseCase = runInternalUseCase;
    }

    @Override
    public String createVerifyRun(String taskId, String mergeCandidateCommit) {
        return runInternalUseCase.createVerifyRun(taskId, mergeCandidateCommit).runId();
    }
}

