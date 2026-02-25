package com.agentx.agentxbackend.execution.infrastructure.persistence;

import java.sql.Timestamp;

public class TaskRunWorkerStatsRow {

    private String workerId;
    private Long totalRuns;
    private Timestamp lastActivityAt;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Long getTotalRuns() {
        return totalRuns;
    }

    public void setTotalRuns(Long totalRuns) {
        this.totalRuns = totalRuns;
    }

    public Timestamp getLastActivityAt() {
        return lastActivityAt;
    }

    public void setLastActivityAt(Timestamp lastActivityAt) {
        this.lastActivityAt = lastActivityAt;
    }
}
