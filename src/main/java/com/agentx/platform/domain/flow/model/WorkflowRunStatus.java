package com.agentx.platform.domain.flow.model;

public enum WorkflowRunStatus {
    DRAFT,
    ACTIVE,
    WAITING_ON_HUMAN,
    EXECUTING_TASKS,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELED
}
