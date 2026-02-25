package com.agentx.agentxbackend.planning.infrastructure.persistence;

import java.sql.Timestamp;

public class WorkTaskRow {

    private String taskId;
    private String moduleId;
    private String title;
    private String taskTemplateId;
    private String status;
    private String requiredToolpacksJson;
    private String activeRunId;
    private String createdByRole;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getModuleId() {
        return moduleId;
    }

    public void setModuleId(String moduleId) {
        this.moduleId = moduleId;
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

    public String getRequiredToolpacksJson() {
        return requiredToolpacksJson;
    }

    public void setRequiredToolpacksJson(String requiredToolpacksJson) {
        this.requiredToolpacksJson = requiredToolpacksJson;
    }

    public String getActiveRunId() {
        return activeRunId;
    }

    public void setActiveRunId(String activeRunId) {
        this.activeRunId = activeRunId;
    }

    public String getCreatedByRole() {
        return createdByRole;
    }

    public void setCreatedByRole(String createdByRole) {
        this.createdByRole = createdByRole;
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
