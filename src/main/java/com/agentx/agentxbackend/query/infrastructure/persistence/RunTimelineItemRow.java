package com.agentx.agentxbackend.query.infrastructure.persistence;

import java.sql.Timestamp;

public class RunTimelineItemRow {

    private String runId;
    private String taskId;
    private String taskTitle;
    private String moduleId;
    private String moduleName;
    private String workerId;
    private String runKind;
    private String runStatus;
    private String eventType;
    private String eventBody;
    private String eventDataJson;
    private Timestamp eventCreatedAt;
    private Timestamp startedAt;
    private Timestamp finishedAt;
    private String branchName;

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

    public String getTaskTitle() {
        return taskTitle;
    }

    public void setTaskTitle(String taskTitle) {
        this.taskTitle = taskTitle;
    }

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

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getRunKind() {
        return runKind;
    }

    public void setRunKind(String runKind) {
        this.runKind = runKind;
    }

    public String getRunStatus() {
        return runStatus;
    }

    public void setRunStatus(String runStatus) {
        this.runStatus = runStatus;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventBody() {
        return eventBody;
    }

    public void setEventBody(String eventBody) {
        this.eventBody = eventBody;
    }

    public String getEventDataJson() {
        return eventDataJson;
    }

    public void setEventDataJson(String eventDataJson) {
        this.eventDataJson = eventDataJson;
    }

    public Timestamp getEventCreatedAt() {
        return eventCreatedAt;
    }

    public void setEventCreatedAt(Timestamp eventCreatedAt) {
        this.eventCreatedAt = eventCreatedAt;
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

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }
}
