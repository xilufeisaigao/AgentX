package com.agentx.agentxbackend.contextpack.application.port.out;

import java.util.List;
import java.util.Optional;

public interface ContextFactsQueryPort {

    Optional<RequirementBaselineFact> findRequirementBaselineBySessionId(String sessionId);

    Optional<TaskPlanningFact> findTaskPlanningByTaskId(String taskId);

    List<TaskPlanningFact> listTaskPlanningBySessionId(String sessionId, int limit);

    List<TicketFact> listRecentArchitectureTickets(String sessionId, int limit);

    Optional<TicketSessionFact> findTicketSessionByTicketId(String ticketId);

    List<TicketEventFact> listRecentTicketEvents(String ticketId, int limit);

    List<RunFact> listRecentTaskRuns(String taskId, int limit);

    List<ToolpackFact> listToolpacksByIds(List<String> toolpackIds);

    record RequirementBaselineFact(
        String docId,
        Integer baselineVersion,
        String title,
        String status,
        String content
    ) {
    }

    record TaskPlanningFact(
        String taskId,
        String moduleId,
        String moduleName,
        String sessionId,
        String taskTitle,
        String taskTemplateId,
        String requiredToolpacksJson
    ) {
    }

    record TicketFact(
        String ticketId,
        String type,
        String status,
        String title,
        String requirementDocId,
        Integer requirementDocVer
    ) {
    }

    record TicketSessionFact(
        String ticketId,
        String sessionId,
        String type,
        String assigneeRole,
        String status
    ) {
    }

    record TicketEventFact(
        String eventId,
        String ticketId,
        String eventType,
        String actorRole,
        String body,
        String dataJson,
        String createdAt
    ) {
    }

    record RunFact(
        String runId,
        String status,
        String runKind,
        String contextSnapshotId,
        String taskSkillRef,
        String baseCommit,
        String createdAt
    ) {
    }

    record ToolpackFact(
        String toolpackId,
        String name,
        String version,
        String kind,
        String description
    ) {
    }
}
