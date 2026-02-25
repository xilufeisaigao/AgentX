package com.agentx.agentxbackend.contextpack.infrastructure.persistence;

public class ContextRequirementBaselineRow {

    private String docId;
    private Integer baselineVersion;
    private String title;
    private String status;
    private String content;

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public Integer getBaselineVersion() {
        return baselineVersion;
    }

    public void setBaselineVersion(Integer baselineVersion) {
        this.baselineVersion = baselineVersion;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
