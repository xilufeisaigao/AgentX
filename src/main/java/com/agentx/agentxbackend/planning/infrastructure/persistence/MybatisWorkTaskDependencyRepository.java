package com.agentx.agentxbackend.planning.infrastructure.persistence;

import com.agentx.agentxbackend.planning.application.port.out.WorkTaskDependencyRepository;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.WorkTaskDependency;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Repository
public class MybatisWorkTaskDependencyRepository implements WorkTaskDependencyRepository {

    private final WorkTaskDependencyMapper mapper;

    public MybatisWorkTaskDependencyRepository(WorkTaskDependencyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public WorkTaskDependency save(WorkTaskDependency dependency) {
        int inserted = mapper.insertIgnore(toRow(dependency));
        if (inserted == 0 && !exists(dependency.taskId(), dependency.dependsOnTaskId())) {
            throw new IllegalStateException(
                "Failed to insert work task dependency: " + dependency.taskId() + " -> " + dependency.dependsOnTaskId()
            );
        }
        return dependency;
    }

    @Override
    public boolean exists(String taskId, String dependsOnTaskId) {
        return mapper.countByTaskAndDependency(taskId, dependsOnTaskId) > 0;
    }

    @Override
    public List<WorkTaskDependency> findByTaskId(String taskId) {
        List<WorkTaskDependencyRow> rows = mapper.findByTaskId(taskId);
        List<WorkTaskDependency> dependencies = new ArrayList<>(rows.size());
        for (WorkTaskDependencyRow row : rows) {
            dependencies.add(toDomain(row));
        }
        return dependencies;
    }

    @Override
    public List<WorkTaskDependency> findByDependsOnTaskId(String dependsOnTaskId) {
        List<WorkTaskDependencyRow> rows = mapper.findByDependsOnTaskId(dependsOnTaskId);
        List<WorkTaskDependency> dependencies = new ArrayList<>(rows.size());
        for (WorkTaskDependencyRow row : rows) {
            dependencies.add(toDomain(row));
        }
        return dependencies;
    }

    private static WorkTaskDependencyRow toRow(WorkTaskDependency dependency) {
        WorkTaskDependencyRow row = new WorkTaskDependencyRow();
        row.setTaskId(dependency.taskId());
        row.setDependsOnTaskId(dependency.dependsOnTaskId());
        row.setRequiredUpstreamStatus(dependency.requiredUpstreamStatus().name());
        row.setCreatedAt(Timestamp.from(dependency.createdAt()));
        return row;
    }

    private static WorkTaskDependency toDomain(WorkTaskDependencyRow row) {
        return new WorkTaskDependency(
            row.getTaskId(),
            row.getDependsOnTaskId(),
            TaskStatus.valueOf(row.getRequiredUpstreamStatus()),
            row.getCreatedAt().toInstant()
        );
    }
}
