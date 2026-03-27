package com.agentx.platform.domain.planning.policy;

import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.domain.planning.model.TaskDependency;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.shared.error.DomainRuleViolation;

import java.util.Collection;
import java.util.Map;

public final class PlanningPolicy {

    private PlanningPolicy() {
    }

    public static void assertTaskHasCapabilities(Collection<TaskCapabilityRequirement> requirements) {
        if (requirements.isEmpty()) {
            throw new DomainRuleViolation("task must declare at least one capability requirement");
        }
    }

    public static boolean canDispatch(
            WorkTask task,
            Collection<TaskDependency> dependencies,
            Map<String, WorkTaskStatus> upstreamStatuses
    ) {
        if (task.status() != WorkTaskStatus.PLANNED && task.status() != WorkTaskStatus.READY) {
            return false;
        }

        return dependencies.stream().allMatch(dependency ->
                upstreamStatuses.get(dependency.dependsOnTaskId()) == dependency.requiredUpstreamStatus());
    }
}
