package com.agentx.agentxbackend.query.domain.model;

public record TaskBoardView(
    String taskId,
    String moduleId,
    String title,
    String status,
    String activeRunId
) {
}
