package com.agentx.agentxbackend.query.infrastructure.persistence;

public class StatusCountRow {

    private String status;
    private Integer statusCount;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getStatusCount() {
        return statusCount;
    }

    public void setStatusCount(Integer statusCount) {
        this.statusCount = statusCount;
    }
}
