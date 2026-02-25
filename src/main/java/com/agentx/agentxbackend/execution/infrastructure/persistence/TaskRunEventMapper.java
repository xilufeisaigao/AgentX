package com.agentx.agentxbackend.execution.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskRunEventMapper {

    @Insert("""
        insert into task_run_events (
            event_id,
            run_id,
            event_type,
            body,
            data_json,
            created_at
        ) values (
            #{row.eventId},
            #{row.runId},
            #{row.eventType},
            #{row.body},
            #{row.dataJson},
            #{row.createdAt}
        )
        """)
    int insert(@Param("row") TaskRunEventRow row);
}
