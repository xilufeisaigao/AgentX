package com.agentx.agentxbackend.ticket.infrastructure.persistence;

import java.sql.Timestamp;

public class TicketRow {

    private String ticketId;
    private String sessionId;
    private String type;
    private String status;
    private String title;
    private String createdByRole;
    private String assigneeRole;
    private String requirementDocId;
    private Integer requirementDocVer;
    private String payloadJson;
    private String claimedBy;
    private Timestamp leaseUntil;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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

    public String getCreatedByRole() {
        return createdByRole;
    }

    public void setCreatedByRole(String createdByRole) {
        this.createdByRole = createdByRole;
    }

    public String getAssigneeRole() {
        return assigneeRole;
    }

    public void setAssigneeRole(String assigneeRole) {
        this.assigneeRole = assigneeRole;
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

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public Timestamp getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Timestamp leaseUntil) {
        this.leaseUntil = leaseUntil;
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
