package com.agentx.platform.domain.flow.model;

public enum WorkflowNodeRunStatus {
    PENDING,
    RUNNING,
    WAITING_ON_HUMAN,
    SUCCEEDED,
    FAILED,
    CANCELED
}
