package com.agentx.platform.runtime.persistence.mybatis.mapper;

import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementVersion;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface IntakeMapper {

    @Select("""
            select
              doc_id as docId,
              workflow_run_id as workflowRunId,
              current_version as currentVersion,
              confirmed_version as confirmedVersion,
              status,
              title
            from requirement_docs
            where doc_id = #{docId}
            """)
    Map<String, Object> findRequirementRow(@Param("docId") String docId);

    @Select("""
            select
              doc_id as docId,
              version,
              content,
              created_by_actor_type as createdByActorType,
              created_by_actor_id as createdByActorId
            from requirement_doc_versions
            where doc_id = #{docId}
            order by version
            """)
    List<Map<String, Object>> listRequirementVersionRows(@Param("docId") String docId);

    @Select("""
            select
              ticket_id as ticketId,
              workflow_run_id as workflowRunId,
              type,
              blocking_scope as blockingScope,
              status,
              title,
              created_by_actor_type as createdByActorType,
              created_by_actor_id as createdByActorId,
              assignee_actor_type as assigneeActorType,
              assignee_actor_id as assigneeActorId,
              origin_node_id as originNodeId,
              requirement_doc_id as requirementDocId,
              requirement_doc_ver as requirementDocVersion,
              payload_json as payloadJson
            from tickets
            where workflow_run_id = #{workflowRunId}
            order by created_at, ticket_id
            """)
    List<Map<String, Object>> listTicketRows(@Param("workflowRunId") String workflowRunId);

    @Select("""
            select
              ticket_id as ticketId,
              workflow_run_id as workflowRunId,
              type,
              blocking_scope as blockingScope,
              status,
              title,
              created_by_actor_type as createdByActorType,
              created_by_actor_id as createdByActorId,
              assignee_actor_type as assigneeActorType,
              assignee_actor_id as assigneeActorId,
              origin_node_id as originNodeId,
              requirement_doc_id as requirementDocId,
              requirement_doc_ver as requirementDocVersion,
              payload_json as payloadJson
            from tickets
            where workflow_run_id = #{workflowRunId}
              and status not in ('RESOLVED', 'CANCELED')
            order by created_at, ticket_id
            """)
    List<Map<String, Object>> listOpenTicketRows(@Param("workflowRunId") String workflowRunId);

    @Insert("""
            insert into requirement_docs (
              doc_id,
              workflow_run_id,
              current_version,
              confirmed_version,
              status,
              title
            ) values (
              #{requirement.docId},
              #{requirement.workflowRunId},
              #{requirement.currentVersion},
              #{requirement.confirmedVersion},
              #{requirement.status},
              #{requirement.title}
            )
            on duplicate key update
              workflow_run_id = values(workflow_run_id),
              current_version = values(current_version),
              confirmed_version = values(confirmed_version),
              status = values(status),
              title = values(title)
            """)
    void upsertRequirement(@Param("requirement") RequirementDoc requirement);

    @Insert("""
            insert into requirement_doc_versions (
              doc_id,
              version,
              content,
              created_by_actor_type,
              created_by_actor_id
            ) values (
              #{version.docId},
              #{version.version},
              #{version.content},
              #{createdByActorType},
              #{createdByActorId}
            )
            """)
    void insertRequirementVersion(
            @Param("version") RequirementVersion version,
            @Param("createdByActorType") String createdByActorType,
            @Param("createdByActorId") String createdByActorId
    );

    @Insert("""
            insert into tickets (
              ticket_id,
              workflow_run_id,
              type,
              blocking_scope,
              status,
              title,
              created_by_actor_type,
              created_by_actor_id,
              assignee_actor_type,
              assignee_actor_id,
              origin_node_id,
              requirement_doc_id,
              requirement_doc_ver,
              payload_json
            ) values (
              #{ticket.ticketId},
              #{ticket.workflowRunId},
              #{ticket.type},
              #{ticket.blockingScope},
              #{ticket.status},
              #{ticket.title},
              #{createdByActorType},
              #{createdByActorId},
              #{assigneeActorType},
              #{assigneeActorId},
              #{ticket.originNodeId},
              #{ticket.requirementDocId},
              #{ticket.requirementDocVersion},
              cast(#{ticket.payloadJson.json} as json)
            )
            on duplicate key update
              type = values(type),
              blocking_scope = values(blocking_scope),
              status = values(status),
              title = values(title),
              assignee_actor_type = values(assignee_actor_type),
              assignee_actor_id = values(assignee_actor_id),
              origin_node_id = values(origin_node_id),
              requirement_doc_id = values(requirement_doc_id),
              requirement_doc_ver = values(requirement_doc_ver),
              payload_json = values(payload_json)
            """)
    void upsertTicket(
            @Param("ticket") Ticket ticket,
            @Param("createdByActorType") String createdByActorType,
            @Param("createdByActorId") String createdByActorId,
            @Param("assigneeActorType") String assigneeActorType,
            @Param("assigneeActorId") String assigneeActorId
    );

    @Insert("""
            insert into ticket_events (
              event_id,
              ticket_id,
              event_type,
              actor_type,
              actor_id,
              body
            ) values (
              #{event.eventId},
              #{event.ticketId},
              #{event.eventType},
              #{actorType},
              #{actorId},
              #{event.body}
            )
            """)
    void insertTicketEvent(
            @Param("event") TicketEvent event,
            @Param("actorType") String actorType,
            @Param("actorId") String actorId
    );
}
