package com.agentx.agentxbackend.query.infrastructure.persistence;

public class LatestDeliveryRow {

    private String latestDeliveryTaskId;
    private String latestDeliveryCommit;
    private String latestVerifyRunId;
    private String latestVerifyStatus;

    public String getLatestDeliveryTaskId() {
        return latestDeliveryTaskId;
    }

    public void setLatestDeliveryTaskId(String latestDeliveryTaskId) {
        this.latestDeliveryTaskId = latestDeliveryTaskId;
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
