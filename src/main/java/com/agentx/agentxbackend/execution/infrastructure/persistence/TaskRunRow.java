package com.agentx.agentxbackend.execution.infrastructure.persistence;

import java.sql.Timestamp;

public class TaskRunRow {

    private String runId;
    private String taskId;
    private String workerId;
    private String status;
    private String runKind;
    private String contextSnapshotId;
    private Timestamp leaseUntil;
    private Timestamp lastHeartbeatAt;
    private Timestamp startedAt;
    private Timestamp finishedAt;
    private String taskSkillRef;
    private String toolpacksSnapshotJson;
    private String baseCommit;
    private String branchName;
    private String worktreePath;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
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

    public Timestamp getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Timestamp leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Timestamp getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Timestamp lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Timestamp getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Timestamp startedAt) {
        this.startedAt = startedAt;
    }

    public Timestamp getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Timestamp finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getTaskSkillRef() {
        return taskSkillRef;
    }

    public void setTaskSkillRef(String taskSkillRef) {
        this.taskSkillRef = taskSkillRef;
    }

    public String getToolpacksSnapshotJson() {
        return toolpacksSnapshotJson;
    }

    public void setToolpacksSnapshotJson(String toolpacksSnapshotJson) {
        this.toolpacksSnapshotJson = toolpacksSnapshotJson;
    }

    public String getBaseCommit() {
        return baseCommit;
    }

    public void setBaseCommit(String baseCommit) {
        this.baseCommit = baseCommit;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getWorktreePath() {
        return worktreePath;
    }

    public void setWorktreePath(String worktreePath) {
        this.worktreePath = worktreePath;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
