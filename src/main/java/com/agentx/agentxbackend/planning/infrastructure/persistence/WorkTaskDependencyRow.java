package com.agentx.agentxbackend.planning.infrastructure.persistence;

import java.sql.Timestamp;

public class WorkTaskDependencyRow {

    private String taskId;
    private String dependsOnTaskId;
    private String requiredUpstreamStatus;
    private Timestamp createdAt;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getDependsOnTaskId() {
        return dependsOnTaskId;
    }

    public void setDependsOnTaskId(String dependsOnTaskId) {
        this.dependsOnTaskId = dependsOnTaskId;
    }

    public String getRequiredUpstreamStatus() {
        return requiredUpstreamStatus;
    }

    public void setRequiredUpstreamStatus(String requiredUpstreamStatus) {
        this.requiredUpstreamStatus = requiredUpstreamStatus;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
