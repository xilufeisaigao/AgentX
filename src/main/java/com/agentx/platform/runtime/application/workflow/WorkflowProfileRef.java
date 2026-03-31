package com.agentx.platform.runtime.application.workflow;

import java.util.Objects;

public record WorkflowProfileRef(
        String profileId,
        String displayName,
        String version,
        String digest
) {

    public WorkflowProfileRef {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(digest, "digest must not be null");
    }
}
