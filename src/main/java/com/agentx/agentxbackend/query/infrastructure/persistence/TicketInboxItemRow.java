package com.agentx.agentxbackend.query.infrastructure.persistence;

import java.sql.Timestamp;

public class TicketInboxItemRow {

    private String ticketId;
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
    private String latestEventType;
    private String latestEventBody;
    private String latestEventDataJson;
    private Timestamp latestEventAt;
    private String sourceRunId;
    private String sourceTaskId;
    private String requestKind;
    private String question;

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

    public String getLatestEventType() {
        return latestEventType;
    }

    public void setLatestEventType(String latestEventType) {
        this.latestEventType = latestEventType;
    }

    public String getLatestEventBody() {
        return latestEventBody;
    }

    public void setLatestEventBody(String latestEventBody) {
        this.latestEventBody = latestEventBody;
    }

    public String getLatestEventDataJson() {
        return latestEventDataJson;
    }

    public void setLatestEventDataJson(String latestEventDataJson) {
        this.latestEventDataJson = latestEventDataJson;
    }

    public Timestamp getLatestEventAt() {
        return latestEventAt;
    }

    public void setLatestEventAt(Timestamp latestEventAt) {
        this.latestEventAt = latestEventAt;
    }

    public String getSourceRunId() {
        return sourceRunId;
    }

    public void setSourceRunId(String sourceRunId) {
        this.sourceRunId = sourceRunId;
    }

    public String getSourceTaskId() {
        return sourceTaskId;
    }

    public void setSourceTaskId(String sourceTaskId) {
        this.sourceTaskId = sourceTaskId;
    }

    public String getRequestKind() {
        return requestKind;
    }

    public void setRequestKind(String requestKind) {
        this.requestKind = requestKind;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
