package com.agentx.agentxbackend.query.infrastructure.persistence;

public class DeliveryCountsRow {

    private Integer deliveredTaskCount;
    private Integer doneTaskCount;

    public Integer getDeliveredTaskCount() {
        return deliveredTaskCount;
    }

    public void setDeliveredTaskCount(Integer deliveredTaskCount) {
        this.deliveredTaskCount = deliveredTaskCount;
    }

    public Integer getDoneTaskCount() {
        return doneTaskCount;
    }

    public void setDoneTaskCount(Integer doneTaskCount) {
        this.doneTaskCount = doneTaskCount;
    }
}
