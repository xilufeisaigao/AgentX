package com.agentx.agentxbackend.contextpack.infrastructure.persistence;

public class ContextTicketRow {

    private String ticketId;
    private String type;
    private String status;
    private String title;
    private String requirementDocId;
    private Integer requirementDocVer;

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRequirementDocId() {
        return requirementDocId;
    }

    public void setRequirementDocId(String requirementDocId) {
        this.requirementDocId = requirementDocId;
    }

    public Integer getRequirementDocVer() {
        return requirementDocVer;
    }

    public void setRequirementDocVer(Integer requirementDocVer) {
        this.requirementDocVer = requirementDocVer;
    }
}
