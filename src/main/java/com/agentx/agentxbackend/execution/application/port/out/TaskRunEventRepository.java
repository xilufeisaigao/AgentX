package com.agentx.agentxbackend.execution.application.port.out;

import com.agentx.agentxbackend.execution.domain.model.TaskRunEvent;

public interface TaskRunEventRepository {

    TaskRunEvent save(TaskRunEvent event);
}
