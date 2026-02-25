package com.agentx.agentxbackend.workforce.infrastructure.persistence;

import java.sql.Timestamp;

public class ToolpackRow {

    private String toolpackId;
    private String name;
    private String version;
    private String kind;
    private String description;
    private Timestamp createdAt;

    public String getToolpackId() {
        return toolpackId;
    }

    public void setToolpackId(String toolpackId) {
        this.toolpackId = toolpackId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}

