package com.agentx.platform.domain.planning.model;

import java.util.Objects;

public record TaskCapabilityRequirement(
        String taskId,
        String capabilityPackId,
        boolean required,
        String roleInTask
) {

    public TaskCapabilityRequirement {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(capabilityPackId, "capabilityPackId must not be null");
        Objects.requireNonNull(roleInTask, "roleInTask must not be null");
    }
}
