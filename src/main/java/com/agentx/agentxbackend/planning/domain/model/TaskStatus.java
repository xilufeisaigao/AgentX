package com.agentx.agentxbackend.planning.domain.model;

public enum TaskStatus {
    PLANNED,
    WAITING_DEPENDENCY,
    WAITING_WORKER,
    READY_FOR_ASSIGN,
    ASSIGNED,
    DELIVERED,
    DONE
}
