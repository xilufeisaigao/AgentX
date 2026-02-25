package com.agentx.agentxbackend.contextpack.infrastructure.persistence;

import java.sql.Timestamp;

public class TaskContextSnapshotRow {

    private String snapshotId;
    private String taskId;
    private String runKind;
    private String status;
    private String triggerType;
    private String sourceFingerprint;
    private String taskContextRef;
    private String taskSkillRef;
    private String errorCode;
    private String errorMessage;
    private Timestamp compiledAt;
    private Timestamp retainedUntil;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getRunKind() {
        return runKind;
    }

    public void setRunKind(String runKind) {
        this.runKind = runKind;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getSourceFingerprint() {
        return sourceFingerprint;
    }

    public void setSourceFingerprint(String sourceFingerprint) {
        this.sourceFingerprint = sourceFingerprint;
    }

    public String getTaskContextRef() {
        return taskContextRef;
    }

    public void setTaskContextRef(String taskContextRef) {
        this.taskContextRef = taskContextRef;
    }

    public String getTaskSkillRef() {
        return taskSkillRef;
    }

    public void setTaskSkillRef(String taskSkillRef) {
        this.taskSkillRef = taskSkillRef;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Timestamp getCompiledAt() {
        return compiledAt;
    }

    public void setCompiledAt(Timestamp compiledAt) {
        this.compiledAt = compiledAt;
    }

    public Timestamp getRetainedUntil() {
        return retainedUntil;
    }

    public void setRetainedUntil(Timestamp retainedUntil) {
        this.retainedUntil = retainedUntil;
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
