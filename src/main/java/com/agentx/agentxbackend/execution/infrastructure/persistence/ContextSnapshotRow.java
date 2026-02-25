package com.agentx.agentxbackend.execution.infrastructure.persistence;

public class ContextSnapshotRow {

    private String snapshotId;
    private String taskContextRef;
    private String taskSkillRef;

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public String getTaskSkillRef() {
        return taskSkillRef;
    }

    public void setTaskSkillRef(String taskSkillRef) {
        this.taskSkillRef = taskSkillRef;
    }

    public String getTaskContextRef() {
        return taskContextRef;
    }

    public void setTaskContextRef(String taskContextRef) {
        this.taskContextRef = taskContextRef;
    }
}
