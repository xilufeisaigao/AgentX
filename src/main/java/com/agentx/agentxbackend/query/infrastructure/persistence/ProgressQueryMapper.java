package com.agentx.agentxbackend.query.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProgressQueryMapper {

    @Select("""
        select
            wt.status as status,
            count(*) as status_count
        from work_tasks wt
        join work_modules wm on wm.module_id = wt.module_id
        where wm.session_id = #{sessionId}
        group by wt.status
        """)
    List<StatusCountRow> countTasksByStatus(@Param("sessionId") String sessionId);

    @Select("""
        select
            t.status as status,
            count(*) as status_count
        from tickets t
        where t.session_id = #{sessionId}
        group by t.status
        """)
    List<StatusCountRow> countTicketsByStatus(@Param("sessionId") String sessionId);

    @Select("""
        select
            tr.status as status,
            count(*) as status_count
        from task_runs tr
        join work_tasks wt on wt.task_id = tr.task_id
        join work_modules wm on wm.module_id = wt.module_id
        where wm.session_id = #{sessionId}
        group by tr.status
        """)
    List<StatusCountRow> countRunsByStatus(@Param("sessionId") String sessionId);

    @Select("""
        with session_runs as (
            select
                tr.run_id,
                tr.task_id,
                wt.title as task_title,
                wt.module_id,
                wm.name as module_name,
                tr.worker_id,
                tr.run_kind,
                tr.status,
                tr.started_at,
                tr.finished_at,
                tr.updated_at,
                row_number() over (order by tr.created_at desc, tr.run_id desc) as rn
            from task_runs tr
            join work_tasks wt on wt.task_id = tr.task_id
            join work_modules wm on wm.module_id = wt.module_id
            where wm.session_id = #{sessionId}
        ),
        latest_events as (
            select
                e.run_id,
                e.event_type,
                e.body as event_body,
                e.created_at as event_at,
                row_number() over (partition by e.run_id order by e.created_at desc, e.event_id desc) as rn
            from task_run_events e
            join session_runs sr on sr.run_id = e.run_id
        )
        select
            sr.run_id,
            sr.task_id,
            sr.task_title,
            sr.module_id,
            sr.module_name,
            sr.worker_id,
            sr.run_kind,
            sr.status,
            le.event_type,
            le.event_body,
            le.event_at,
            sr.started_at,
            sr.finished_at,
            sr.updated_at
        from session_runs sr
        left join latest_events le on le.run_id = sr.run_id and le.rn = 1
        where sr.rn = 1
        """)
    LatestRunRow findLatestRun(@Param("sessionId") String sessionId);

    @Select("""
        select
            coalesce(sum(case when wt.status = 'DELIVERED' then 1 else 0 end), 0) as delivered_task_count,
            coalesce(sum(case when wt.status = 'DONE' then 1 else 0 end), 0) as done_task_count
        from work_tasks wt
        join work_modules wm on wm.module_id = wt.module_id
        where wm.session_id = #{sessionId}
        """)
    DeliveryCountsRow findDeliveryCounts(@Param("sessionId") String sessionId);

    @Select("""
        with session_tasks as (
            select
                wt.task_id,
                wt.updated_at
            from work_tasks wt
            join work_modules wm on wm.module_id = wt.module_id
            where wm.session_id = #{sessionId}
              and wt.status in ('DELIVERED', 'DONE')
        ),
        latest_task as (
            select
                st.task_id
            from session_tasks st
            order by st.updated_at desc, st.task_id desc
            limit 1
        )
        select
            lt.task_id as latest_delivery_task_id,
            (
                select json_unquote(json_extract(e.data_json, '$.delivery_commit'))
                from task_runs tr
                join task_run_events e on e.run_id = tr.run_id and e.event_type = 'RUN_FINISHED'
                where tr.task_id = lt.task_id
                  and tr.run_kind = 'IMPL'
                order by tr.created_at desc, tr.run_id desc
                limit 1
            ) as latest_delivery_commit,
            (
                select tr.run_id
                from task_runs tr
                where tr.task_id = lt.task_id
                  and tr.run_kind = 'VERIFY'
                order by tr.created_at desc, tr.run_id desc
                limit 1
            ) as latest_verify_run_id,
            (
                select tr.status
                from task_runs tr
                where tr.task_id = lt.task_id
                  and tr.run_kind = 'VERIFY'
                order by tr.created_at desc, tr.run_id desc
                limit 1
            ) as latest_verify_status
        from latest_task lt
        """)
    LatestDeliveryRow findLatestDelivery(@Param("sessionId") String sessionId);

    @Select({
        "<script>",
        "with session_tickets as (",
        "    select",
        "        t.ticket_id,",
        "        t.type,",
        "        t.status,",
        "        t.title,",
        "        t.created_by_role,",
        "        t.assignee_role,",
        "        t.requirement_doc_id,",
        "        t.requirement_doc_ver,",
        "        t.payload_json,",
        "        t.claimed_by,",
        "        t.lease_until,",
        "        t.created_at,",
        "        t.updated_at",
        "    from tickets t",
        "    where t.session_id = #{sessionId}",
        "    <if test='status != null and status != \"\"'>",
        "      and t.status = #{status}",
        "    </if>",
        "), latest_event as (",
        "    select",
        "        e.ticket_id,",
        "        e.event_type,",
        "        e.body,",
        "        e.data_json,",
        "        e.created_at,",
        "        row_number() over (partition by e.ticket_id order by e.created_at desc, e.event_id desc) as rn",
        "    from ticket_events e",
        "    join session_tickets st on st.ticket_id = e.ticket_id",
        "), latest_request as (",
        "    select",
        "        e.ticket_id,",
        "        e.data_json,",
        "        row_number() over (partition by e.ticket_id order by e.created_at desc, e.event_id desc) as rn",
        "    from ticket_events e",
        "    join session_tickets st on st.ticket_id = e.ticket_id",
        "    where e.event_type = 'DECISION_REQUESTED'",
        ")",
        "select",
        "    st.ticket_id,",
        "    st.type,",
        "    st.status,",
        "    st.title,",
        "    st.created_by_role,",
        "    st.assignee_role,",
        "    st.requirement_doc_id,",
        "    st.requirement_doc_ver,",
        "    st.payload_json,",
        "    st.claimed_by,",
        "    st.lease_until,",
        "    st.created_at,",
        "    st.updated_at,",
        "    le.event_type as latest_event_type,",
        "    le.body as latest_event_body,",
        "    le.data_json as latest_event_data_json,",
        "    le.created_at as latest_event_at,",
        "    coalesce(json_unquote(json_extract(lr.data_json, '$.run_id')), json_unquote(json_extract(st.payload_json, '$.run_id'))) as source_run_id,",
        "    coalesce(json_unquote(json_extract(lr.data_json, '$.task_id')), json_unquote(json_extract(st.payload_json, '$.task_id'))) as source_task_id,",
        "    coalesce(",
        "      json_unquote(json_extract(lr.data_json, '$.request_kind')),",
        "      case when st.type = 'DECISION' then 'DECISION' when st.type = 'CLARIFICATION' then 'CLARIFICATION' else null end",
        "    ) as request_kind,",
        "    coalesce(",
        "      nullif(json_unquote(json_extract(lr.data_json, '$.question')), ''),",
        "      nullif(json_unquote(json_extract(st.payload_json, '$.summary')), ''),",
        "      st.title",
        "    ) as question",
        "from session_tickets st",
        "left join latest_event le on le.ticket_id = st.ticket_id and le.rn = 1",
        "left join latest_request lr on lr.ticket_id = st.ticket_id and lr.rn = 1",
        "order by",
        "    case st.status",
        "        when 'WAITING_USER' then 0",
        "        when 'IN_PROGRESS' then 1",
        "        when 'OPEN' then 2",
        "        else 3",
        "    end,",
        "    st.updated_at desc,",
        "    st.ticket_id desc",
        "</script>"
    })
    List<TicketInboxItemRow> findTicketInboxItems(
        @Param("sessionId") String sessionId,
        @Param("status") String status
    );

    @Select("""
        with session_tasks as (
            select
                wt.task_id,
                wt.module_id,
                wm.name as module_name,
                wm.description as module_description,
                wt.title,
                wt.task_template_id,
                wt.status,
                wt.active_run_id,
                wt.required_toolpacks_json
            from work_tasks wt
            join work_modules wm on wm.module_id = wt.module_id
            where wm.session_id = #{sessionId}
        ),
        latest_snapshot as (
            select
                tcs.task_id,
                tcs.snapshot_id,
                tcs.status,
                tcs.run_kind,
                tcs.compiled_at,
                row_number() over (partition by tcs.task_id order by tcs.created_at desc, tcs.snapshot_id desc) as rn
            from task_context_snapshots tcs
            join session_tasks st on st.task_id = tcs.task_id
        ),
        latest_run as (
            select
                tr.task_id,
                tr.run_id,
                tr.status,
                tr.run_kind,
                tr.updated_at,
                row_number() over (partition by tr.task_id order by tr.created_at desc, tr.run_id desc) as rn
            from task_runs tr
            join session_tasks st on st.task_id = tr.task_id
        ),
        latest_impl_finish as (
            select
                x.task_id,
                x.latest_delivery_commit
            from (
                select
                    tr.task_id,
                    json_unquote(json_extract(e.data_json, '$.delivery_commit')) as latest_delivery_commit,
                    row_number() over (partition by tr.task_id order by tr.created_at desc, tr.run_id desc) as rn
                from task_runs tr
                join session_tasks st on st.task_id = tr.task_id
                join task_run_events e on e.run_id = tr.run_id and e.event_type = 'RUN_FINISHED'
                where tr.run_kind = 'IMPL'
            ) x
            where x.rn = 1
        ),
        latest_verify as (
            select
                x.task_id,
                x.run_id,
                x.status
            from (
                select
                    tr.task_id,
                    tr.run_id,
                    tr.status,
                    row_number() over (partition by tr.task_id order by tr.created_at desc, tr.run_id desc) as rn
                from task_runs tr
                join session_tasks st on st.task_id = tr.task_id
                where tr.run_kind = 'VERIFY'
            ) x
            where x.rn = 1
        ),
        deps as (
            select
                d.task_id,
                group_concat(d.depends_on_task_id order by d.depends_on_task_id separator ',') as dependency_task_ids_csv
            from work_task_dependencies d
            join session_tasks st on st.task_id = d.task_id
            group by d.task_id
        )
        select
            st.module_id,
            st.module_name,
            st.module_description,
            st.task_id,
            st.title,
            st.task_template_id,
            st.status,
            st.active_run_id,
            st.required_toolpacks_json,
            deps.dependency_task_ids_csv,
            ls.snapshot_id as latest_context_snapshot_id,
            ls.status as latest_context_status,
            ls.run_kind as latest_context_run_kind,
            ls.compiled_at as latest_context_compiled_at,
            lr.run_id as last_run_id,
            lr.status as last_run_status,
            lr.run_kind as last_run_kind,
            lr.updated_at as last_run_updated_at,
            lif.latest_delivery_commit,
            lv.run_id as latest_verify_run_id,
            lv.status as latest_verify_status
        from session_tasks st
        left join deps on deps.task_id = st.task_id
        left join latest_snapshot ls on ls.task_id = st.task_id and ls.rn = 1
        left join latest_run lr on lr.task_id = st.task_id and lr.rn = 1
        left join latest_impl_finish lif on lif.task_id = st.task_id
        left join latest_verify lv on lv.task_id = st.task_id
        order by st.module_name asc, st.task_id asc
        """)
    List<TaskBoardItemRow> findTaskBoardItems(@Param("sessionId") String sessionId);

    @Select({
        "<script>",
        "with session_runs as (",
        "    select",
        "        tr.run_id,",
        "        tr.task_id,",
        "        wt.title as task_title,",
        "        wt.module_id,",
        "        wm.name as module_name,",
        "        tr.worker_id,",
        "        tr.run_kind,",
        "        tr.status as run_status,",
        "        tr.started_at,",
        "        tr.finished_at,",
        "        tr.branch_name",
        "    from task_runs tr",
        "    join work_tasks wt on wt.task_id = tr.task_id",
        "    join work_modules wm on wm.module_id = wt.module_id",
        "    where wm.session_id = #{sessionId}",
        ")",
        "select",
        "    sr.run_id,",
        "    sr.task_id,",
        "    sr.task_title,",
        "    sr.module_id,",
        "    sr.module_name,",
        "    sr.worker_id,",
        "    sr.run_kind,",
        "    sr.run_status,",
        "    e.event_type,",
        "    e.body as event_body,",
        "    e.data_json as event_data_json,",
        "    e.created_at as event_created_at,",
        "    sr.started_at,",
        "    sr.finished_at,",
        "    sr.branch_name",
        "from task_run_events e",
        "join session_runs sr on sr.run_id = e.run_id",
        "order by e.created_at desc, e.event_id desc",
        "limit #{limit}",
        "</script>"
    })
    List<RunTimelineItemRow> findRunTimelineItems(
        @Param("sessionId") String sessionId,
        @Param("limit") int limit
    );
}
