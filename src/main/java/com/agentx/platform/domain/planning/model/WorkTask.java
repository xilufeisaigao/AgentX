package com.agentx.platform.domain.planning.model;

import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.AggregateRoot;
import com.agentx.platform.domain.shared.model.WriteScope;

import java.util.List;
import java.util.Objects;

public record WorkTask(
        String taskId,
        String moduleId,
        String title,
        String objective,
        String taskTemplateId,
        WorkTaskStatus status,
        List<WriteScope> writeScopes,
        String originTicketId,
        ActorRef createdBy
) implements AggregateRoot<String> {

    public WorkTask {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(objective, "objective must not be null");
        Objects.requireNonNull(taskTemplateId, "taskTemplateId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        writeScopes = List.copyOf(Objects.requireNonNull(writeScopes, "writeScopes must not be null"));
        Objects.requireNonNull(createdBy, "createdBy must not be null");
    }

    @Override
    public String aggregateId() {
        return taskId;
    }
}
