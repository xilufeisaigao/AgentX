package com.agentx.agentxbackend.execution.infrastructure.persistence;

import com.agentx.agentxbackend.execution.application.port.out.TaskRunEventRepository;
import com.agentx.agentxbackend.execution.domain.model.TaskRunEvent;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public class MybatisTaskRunEventRepository implements TaskRunEventRepository {

    private final TaskRunEventMapper mapper;

    public MybatisTaskRunEventRepository(TaskRunEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TaskRunEvent save(TaskRunEvent event) {
        TaskRunEventRow row = new TaskRunEventRow();
        row.setEventId(event.eventId());
        row.setRunId(event.runId());
        row.setEventType(event.eventType().name());
        row.setBody(event.body());
        row.setDataJson(event.dataJson());
        row.setCreatedAt(Timestamp.from(event.createdAt()));
        int inserted = mapper.insert(row);
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert task run event: " + event.eventId());
        }
        return event;
    }
}
