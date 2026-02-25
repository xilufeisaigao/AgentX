package com.agentx.agentxbackend.contextpack.infrastructure.persistence;

import com.agentx.agentxbackend.contextpack.application.port.out.ContextFactsQueryPort;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisContextFactsQueryAdapter implements ContextFactsQueryPort {

    private final ContextFactsMapper mapper;

    public MybatisContextFactsQueryAdapter(ContextFactsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<RequirementBaselineFact> findRequirementBaselineBySessionId(String sessionId) {
        ContextRequirementBaselineRow row = mapper.findRequirementBaselineBySessionId(sessionId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(
            new RequirementBaselineFact(
                row.getDocId(),
                row.getBaselineVersion(),
                row.getTitle(),
                row.getStatus(),
                row.getContent()
            )
        );
    }

    @Override
    public Optional<TaskPlanningFact> findTaskPlanningByTaskId(String taskId) {
        ContextTaskPlanningRow row = mapper.findTaskPlanningByTaskId(taskId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(
            new TaskPlanningFact(
                row.getTaskId(),
                row.getModuleId(),
                row.getModuleName(),
                row.getSessionId(),
                row.getTaskTitle(),
                row.getTaskTemplateId(),
                row.getRequiredToolpacksJson()
            )
        );
    }

    @Override
    public List<TaskPlanningFact> listTaskPlanningBySessionId(String sessionId, int limit) {
        int cappedLimit = capLimit(limit, 1, 1000);
        List<ContextTaskPlanningRow> rows = mapper.listTaskPlanningBySessionId(sessionId, cappedLimit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<TaskPlanningFact> result = new ArrayList<>(rows.size());
        for (ContextTaskPlanningRow row : rows) {
            result.add(
                new TaskPlanningFact(
                    row.getTaskId(),
                    row.getModuleId(),
                    row.getModuleName(),
                    row.getSessionId(),
                    row.getTaskTitle(),
                    row.getTaskTemplateId(),
                    row.getRequiredToolpacksJson()
                )
            );
        }
        return result;
    }

    @Override
    public List<TicketFact> listRecentArchitectureTickets(String sessionId, int limit) {
        int cappedLimit = capLimit(limit, 1, 100);
        List<ContextTicketRow> rows = mapper.listRecentArchitectureTickets(sessionId, cappedLimit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<TicketFact> result = new ArrayList<>(rows.size());
        for (ContextTicketRow row : rows) {
            result.add(
                new TicketFact(
                    row.getTicketId(),
                    row.getType(),
                    row.getStatus(),
                    row.getTitle(),
                    row.getRequirementDocId(),
                    row.getRequirementDocVer()
                )
            );
        }
        return result;
    }

    @Override
    public Optional<TicketSessionFact> findTicketSessionByTicketId(String ticketId) {
        ContextTicketSessionRow row = mapper.findTicketSessionByTicketId(ticketId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(
            new TicketSessionFact(
                row.getTicketId(),
                row.getSessionId(),
                row.getType(),
                row.getAssigneeRole(),
                row.getStatus()
            )
        );
    }

    @Override
    public List<TicketEventFact> listRecentTicketEvents(String ticketId, int limit) {
        int cappedLimit = capLimit(limit, 1, 200);
        List<ContextTicketEventRow> rows = mapper.listRecentTicketEvents(ticketId, cappedLimit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Collections.reverse(rows);
        List<TicketEventFact> result = new ArrayList<>(rows.size());
        for (ContextTicketEventRow row : rows) {
            result.add(
                new TicketEventFact(
                    row.getEventId(),
                    row.getTicketId(),
                    row.getEventType(),
                    row.getActorRole(),
                    row.getBody(),
                    row.getDataJson(),
                    row.getCreatedAt() == null ? "" : row.getCreatedAt().toInstant().toString()
                )
            );
        }
        return result;
    }

    @Override
    public List<RunFact> listRecentTaskRuns(String taskId, int limit) {
        int cappedLimit = capLimit(limit, 1, 100);
        List<ContextRunRow> rows = mapper.listRecentTaskRuns(taskId, cappedLimit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<RunFact> result = new ArrayList<>(rows.size());
        for (ContextRunRow row : rows) {
            result.add(
                new RunFact(
                    row.getRunId(),
                    row.getStatus(),
                    row.getRunKind(),
                    row.getContextSnapshotId(),
                    row.getTaskSkillRef(),
                    row.getBaseCommit(),
                    row.getCreatedAt() == null ? "" : row.getCreatedAt().toInstant().toString()
                )
            );
        }
        return result;
    }

    @Override
    public List<ToolpackFact> listToolpacksByIds(List<String> toolpackIds) {
        if (toolpackIds == null || toolpackIds.isEmpty()) {
            return List.of();
        }
        List<String> normalizedIds = toolpackIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (normalizedIds.isEmpty()) {
            return List.of();
        }
        List<ContextToolpackRow> rows = mapper.listToolpacksByIds(normalizedIds);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ToolpackFact> result = new ArrayList<>(rows.size());
        for (ContextToolpackRow row : rows) {
            result.add(
                new ToolpackFact(
                    row.getToolpackId(),
                    row.getName(),
                    row.getVersion(),
                    row.getKind(),
                    row.getDescription()
                )
            );
        }
        return result;
    }

    private static int capLimit(int raw, int min, int max) {
        return Math.max(min, Math.min(max, raw));
    }
}
