package com.agentx.agentxbackend.planning.infrastructure.persistence;

import com.agentx.agentxbackend.planning.application.port.out.WorkTaskRepository;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisWorkTaskRepository implements WorkTaskRepository {

    private final WorkTaskMapper mapper;

    public MybatisWorkTaskRepository(WorkTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public WorkTask save(WorkTask task) {
        int inserted = mapper.insert(toRow(task));
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert work task: " + task.taskId());
        }
        return task;
    }

    @Override
    public Optional<WorkTask> findById(String taskId) {
        WorkTaskRow row = mapper.findById(taskId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public WorkTask update(WorkTask task) {
        int updated = mapper.update(toRow(task));
        if (updated != 1) {
            throw new IllegalStateException("Failed to update work task: " + task.taskId());
        }
        return task;
    }

    @Override
    public List<WorkTask> findByStatus(TaskStatus status, int limit) {
        List<WorkTaskRow> rows = mapper.findByStatus(status.name(), limit);
        List<WorkTask> tasks = new ArrayList<>(rows.size());
        for (WorkTaskRow row : rows) {
            tasks.add(toDomain(row));
        }
        return tasks;
    }

    @Override
    public List<WorkTask> findByStatus(TaskStatus status, int limit, int offset) {
        List<WorkTaskRow> rows = mapper.findByStatusPaged(status.name(), limit, Math.max(0, offset));
        List<WorkTask> tasks = new ArrayList<>(rows.size());
        for (WorkTaskRow row : rows) {
            tasks.add(toDomain(row));
        }
        return tasks;
    }

    @Override
    public boolean claimIfReady(String taskId, String runId, Instant updatedAt) {
        return mapper.claimIfReady(taskId, runId, Timestamp.from(updatedAt)) == 1;
    }

    @Override
    public int countNonDoneByTemplateId(String taskTemplateId) {
        if (taskTemplateId == null || taskTemplateId.isBlank()) {
            throw new IllegalArgumentException("taskTemplateId must not be blank");
        }
        return mapper.countNonDoneByTemplateId(taskTemplateId.trim());
    }

    @Override
    public int countNonDoneBySessionIdAndTemplateId(String sessionId, String taskTemplateId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (taskTemplateId == null || taskTemplateId.isBlank()) {
            throw new IllegalArgumentException("taskTemplateId must not be blank");
        }
        return mapper.countNonDoneBySessionIdAndTemplateId(sessionId.trim(), taskTemplateId.trim());
    }

    @Override
    public int countNonDoneBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return mapper.countNonDoneBySessionId(sessionId.trim());
    }

    private WorkTaskRow toRow(WorkTask task) {
        WorkTaskRow row = new WorkTaskRow();
        row.setTaskId(task.taskId());
        row.setModuleId(task.moduleId());
        row.setTitle(task.title());
        row.setTaskTemplateId(task.taskTemplateId().value());
        row.setStatus(task.status().name());
        row.setRequiredToolpacksJson(task.requiredToolpacksJson());
        row.setActiveRunId(task.activeRunId());
        row.setCreatedByRole(task.createdByRole());
        row.setCreatedAt(Timestamp.from(task.createdAt()));
        row.setUpdatedAt(Timestamp.from(task.updatedAt()));
        return row;
    }

    private WorkTask toDomain(WorkTaskRow row) {
        return new WorkTask(
            row.getTaskId(),
            row.getModuleId(),
            row.getTitle(),
            TaskTemplateId.fromValue(row.getTaskTemplateId()),
            TaskStatus.valueOf(row.getStatus()),
            row.getRequiredToolpacksJson(),
            row.getActiveRunId(),
            row.getCreatedByRole(),
            row.getCreatedAt().toInstant(),
            row.getUpdatedAt().toInstant()
        );
    }
}
