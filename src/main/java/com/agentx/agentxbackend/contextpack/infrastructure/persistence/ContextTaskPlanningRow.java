package com.agentx.agentxbackend.contextpack.infrastructure.persistence;

public class ContextTaskPlanningRow {

    private String taskId;
    private String moduleId;
    private String moduleName;
    private String sessionId;
    private String taskTitle;
    private String taskTemplateId;
    private String requiredToolpacksJson;

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

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTaskTitle() {
        return taskTitle;
    }

    public void setTaskTitle(String taskTitle) {
        this.taskTitle = taskTitle;
    }

    public String getTaskTemplateId() {
        return taskTemplateId;
    }

    public void setTaskTemplateId(String taskTemplateId) {
        this.taskTemplateId = taskTemplateId;
    }

    public String getRequiredToolpacksJson() {
        return requiredToolpacksJson;
    }

    public void setRequiredToolpacksJson(String requiredToolpacksJson) {
        this.requiredToolpacksJson = requiredToolpacksJson;
    }
}
