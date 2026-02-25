package com.agentx.agentxbackend.contextpack.infrastructure.persistence;

import java.sql.Timestamp;

public class ContextRunRow {

    private String runId;
    private String status;
    private String runKind;
    private String contextSnapshotId;
    private String taskSkillRef;
    private String baseCommit;
    private Timestamp createdAt;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRunKind() {
        return runKind;
    }

    public void setRunKind(String runKind) {
        this.runKind = runKind;
    }

    public String getContextSnapshotId() {
        return contextSnapshotId;
    }

    public void setContextSnapshotId(String contextSnapshotId) {
        this.contextSnapshotId = contextSnapshotId;
    }

    public String getTaskSkillRef() {
        return taskSkillRef;
    }

    public void setTaskSkillRef(String taskSkillRef) {
        this.taskSkillRef = taskSkillRef;
    }

    public String getBaseCommit() {
        return baseCommit;
    }

    public void setBaseCommit(String baseCommit) {
        this.baseCommit = baseCommit;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
