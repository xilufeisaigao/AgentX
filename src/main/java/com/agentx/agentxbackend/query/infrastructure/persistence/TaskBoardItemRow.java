package com.agentx.agentxbackend.query.infrastructure.persistence;

import java.sql.Timestamp;

public class TaskBoardItemRow {

    private String moduleId;
    private String moduleName;
    private String moduleDescription;
    private String taskId;
    private String title;
    private String taskTemplateId;
    private String status;
    private String activeRunId;
    private String requiredToolpacksJson;
    private String dependencyTaskIdsCsv;
    private String latestContextSnapshotId;
    private String latestContextStatus;
    private String latestContextRunKind;
    private Timestamp latestContextCompiledAt;
    private String lastRunId;
    private String lastRunStatus;
    private String lastRunKind;
    private Timestamp lastRunUpdatedAt;
    private String latestDeliveryCommit;
    private String latestVerifyRunId;
    private String latestVerifyStatus;

    public String getModuleId() {
        return moduleId;
    }

    public void setModuleId(String moduleId) {
        this.moduleId = moduleId;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getModuleDescription() {
        return moduleDescription;
    }

    public void setModuleDescription(String moduleDescription) {
        this.moduleDescription = moduleDescription;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTaskTemplateId() {
        return taskTemplateId;
    }

    public void setTaskTemplateId(String taskTemplateId) {
        this.taskTemplateId = taskTemplateId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getActiveRunId() {
        return activeRunId;
    }

    public void setActiveRunId(String activeRunId) {
        this.activeRunId = activeRunId;
    }

    public String getRequiredToolpacksJson() {
        return requiredToolpacksJson;
    }

    public void setRequiredToolpacksJson(String requiredToolpacksJson) {
        this.requiredToolpacksJson = requiredToolpacksJson;
    }

    public String getDependencyTaskIdsCsv() {
        return dependencyTaskIdsCsv;
    }

    public void setDependencyTaskIdsCsv(String dependencyTaskIdsCsv) {
        this.dependencyTaskIdsCsv = dependencyTaskIdsCsv;
    }

    public String getLatestContextSnapshotId() {
        return latestContextSnapshotId;
    }

    public void setLatestContextSnapshotId(String latestContextSnapshotId) {
        this.latestContextSnapshotId = latestContextSnapshotId;
    }

    public String getLatestContextStatus() {
        return latestContextStatus;
    }

    public void setLatestContextStatus(String latestContextStatus) {
        this.latestContextStatus = latestContextStatus;
    }

    public String getLatestContextRunKind() {
        return latestContextRunKind;
    }

    public void setLatestContextRunKind(String latestContextRunKind) {
        this.latestContextRunKind = latestContextRunKind;
    }

    public Timestamp getLatestContextCompiledAt() {
        return latestContextCompiledAt;
    }

    public void setLatestContextCompiledAt(Timestamp latestContextCompiledAt) {
        this.latestContextCompiledAt = latestContextCompiledAt;
    }

    public String getLastRunId() {
        return lastRunId;
    }

    public void setLastRunId(String lastRunId) {
        this.lastRunId = lastRunId;
    }

    public String getLastRunStatus() {
        return lastRunStatus;
    }

    public void setLastRunStatus(String lastRunStatus) {
        this.lastRunStatus = lastRunStatus;
    }

    public String getLastRunKind() {
        return lastRunKind;
    }

    public void setLastRunKind(String lastRunKind) {
        this.lastRunKind = lastRunKind;
    }

    public Timestamp getLastRunUpdatedAt() {
        return lastRunUpdatedAt;
    }

    public void setLastRunUpdatedAt(Timestamp lastRunUpdatedAt) {
        this.lastRunUpdatedAt = lastRunUpdatedAt;
    }

    public String getLatestDeliveryCommit() {
        return latestDeliveryCommit;
    }

    public void setLatestDeliveryCommit(String latestDeliveryCommit) {
        this.latestDeliveryCommit = latestDeliveryCommit;
    }

    public String getLatestVerifyRunId() {
        return latestVerifyRunId;
    }

    public void setLatestVerifyRunId(String latestVerifyRunId) {
        this.latestVerifyRunId = latestVerifyRunId;
    }

    public String getLatestVerifyStatus() {
        return latestVerifyStatus;
    }

    public void setLatestVerifyStatus(String latestVerifyStatus) {
        this.latestVerifyStatus = latestVerifyStatus;
    }
}
