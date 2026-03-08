package com.agentx.agentxbackend.contextpack.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ContextFactsMapper {

    @Select("""
        select
            rd.doc_id,
            coalesce(rd.confirmed_version, nullif(rd.current_version, 0)) as baseline_version,
            rd.title,
            rd.status,
            rdv.content
        from requirement_docs rd
        left join requirement_doc_versions rdv
               on rdv.doc_id = rd.doc_id
              and rdv.version = coalesce(rd.confirmed_version, nullif(rd.current_version, 0))
        where rd.session_id = #{sessionId}
        order by rd.updated_at desc
        limit 1
        """)
    ContextRequirementBaselineRow findRequirementBaselineBySessionId(@Param("sessionId") String sessionId);

    @Select("""
        select
            wt.task_id,
            wt.module_id,
            wm.name as module_name,
            wm.session_id,
            wt.title as task_title,
            wt.task_template_id,
            wt.required_toolpacks_json
        from work_tasks wt
        join work_modules wm on wm.module_id = wt.module_id
        where wt.task_id = #{taskId}
        limit 1
        """)
    ContextTaskPlanningRow findTaskPlanningByTaskId(@Param("taskId") String taskId);

    @Select("""
        select
            wt.task_id,
            wt.module_id,
            wm.name as module_name,
            wm.session_id,
            wt.title as task_title,
            wt.task_template_id,
            wt.required_toolpacks_json
        from work_tasks wt
        join work_modules wm on wm.module_id = wt.module_id
        where wm.session_id = #{sessionId}
        order by wt.created_at asc
        limit #{limit}
        """)
    List<ContextTaskPlanningRow> listTaskPlanningBySessionId(
        @Param("sessionId") String sessionId,
        @Param("limit") int limit
    );

    @Select("""
        select
            ticket_id,
            type,
            status,
            title,
            requirement_doc_id,
            requirement_doc_ver
        from tickets
        where session_id = #{sessionId}
          and assignee_role = 'architect_agent'
          and type in ('ARCH_REVIEW', 'HANDOFF', 'DECISION', 'CLARIFICATION')
        order by updated_at desc, created_at desc
        limit #{limit}
        """)
    List<ContextTicketRow> listRecentArchitectureTickets(
        @Param("sessionId") String sessionId,
        @Param("limit") int limit
    );

    @Select("""
        select
            ticket_id,
            session_id,
            type,
            assignee_role,
            status
        from tickets
        where ticket_id = #{ticketId}
        limit 1
        """)
    ContextTicketSessionRow findTicketSessionByTicketId(@Param("ticketId") String ticketId);

    @Select("""
        select
            event_id,
            ticket_id,
            event_type,
            actor_role,
            body,
            data_json,
            created_at
        from ticket_events
        where ticket_id = #{ticketId}
        order by created_at desc
        limit #{limit}
        """)
    List<ContextTicketEventRow> listRecentTicketEvents(
        @Param("ticketId") String ticketId,
        @Param("limit") int limit
    );

    @Select("""
        select
            tr.run_id,
            tr.status,
            tr.run_kind,
            tr.context_snapshot_id,
            tr.task_skill_ref,
            tr.base_commit,
            tr.created_at,
            latest_event.event_type as latest_event_type,
            latest_event.body as latest_event_body,
            latest_event.data_json as latest_event_data_json
        from task_runs tr
        left join task_run_events latest_event
          on latest_event.event_id = (
              select tre.event_id
              from task_run_events tre
              where tre.run_id = tr.run_id
              order by tre.created_at desc, tre.event_id desc
              limit 1
          )
        where tr.task_id = #{taskId}
        order by tr.created_at desc
        limit #{limit}
        """)
    List<ContextRunRow> listRecentTaskRuns(
        @Param("taskId") String taskId,
        @Param("limit") int limit
    );

    @Select({
        "<script>",
        "select",
        "  toolpack_id,",
        "  name,",
        "  version,",
        "  kind,",
        "  description",
        "from toolpacks",
        "where toolpack_id in",
        "<foreach item='id' collection='toolpackIds' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "order by created_at asc, toolpack_id asc",
        "</script>"
    })
    List<ContextToolpackRow> listToolpacksByIds(@Param("toolpackIds") List<String> toolpackIds);
}
