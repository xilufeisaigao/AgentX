package com.agentx.platform.domain.execution.policy;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.AgentPoolStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.TaskContextSnapshot;
import com.agentx.platform.domain.execution.model.TaskContextSnapshotStatus;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.shared.error.DomainRuleViolation;

public final class ExecutionPolicy {

    private ExecutionPolicy() {
    }

    public static void assertSnapshotReady(TaskContextSnapshot snapshot) {
        if (snapshot.status() != TaskContextSnapshotStatus.READY) {
            throw new DomainRuleViolation("task context snapshot must be READY");
        }
    }

    public static void assertAgentReady(AgentPoolInstance agentPoolInstance) {
        if (agentPoolInstance.status() != AgentPoolStatus.READY) {
            throw new DomainRuleViolation("agent pool instance must be READY");
        }
    }

    public static void assertRunStartable(TaskContextSnapshot snapshot, AgentPoolInstance agentPoolInstance) {
        assertSnapshotReady(snapshot);
        assertAgentReady(agentPoolInstance);
    }

    public static void assertWorkspaceOwnedByRun(GitWorkspace workspace, TaskRun run) {
        if (!workspace.runId().equals(run.runId())) {
            throw new DomainRuleViolation("workspace must belong to the owning task run");
        }
    }
}
