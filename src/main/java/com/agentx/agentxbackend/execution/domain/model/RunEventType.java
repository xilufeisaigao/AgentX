package com.agentx.agentxbackend.execution.domain.model;

public enum RunEventType {
    RUN_STARTED,
    HEARTBEAT,
    PROGRESS,
    NEED_CLARIFICATION,
    NEED_DECISION,
    ARTIFACT_LINKED,
    RUN_FINISHED
}
