package com.agentx.agentxbackend.execution.domain.model;

public enum RunStatus {
    RUNNING,
    WAITING_FOREMAN,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
