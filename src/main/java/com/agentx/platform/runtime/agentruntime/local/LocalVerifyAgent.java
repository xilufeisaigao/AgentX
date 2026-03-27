package com.agentx.platform.runtime.agentruntime.local;

import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.runtime.application.workflow.WorkflowScenario;
import org.springframework.stereotype.Component;

@Component
public class LocalVerifyAgent {

    public VerifyOutcome verify(WorkTask task, GitWorkspace workspace, WorkflowScenario scenario, int verificationAttempt) {
        if (scenario.verifyNeedsRework() && verificationAttempt == 1) {
            return new VerifyOutcome(false, "本地 fake verify 触发了一次返工。");
        }
        return new VerifyOutcome(true, "本地 fake verify 已接受交付候选。");
    }

    public record VerifyOutcome(
            boolean passed,
            String body
    ) {
    }
}
