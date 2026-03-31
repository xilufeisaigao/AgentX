package com.agentx.platform.runtime.persistence.mybatis.repository;

import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.RequirementVersion;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketEvent;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.model.TicketType;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.runtime.persistence.mybatis.mapper.IntakeMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MybatisIntakeRepository implements IntakeStore {

    private final IntakeMapper intakeMapper;

    public MybatisIntakeRepository(IntakeMapper intakeMapper) {
        this.intakeMapper = intakeMapper;
    }

    @Override
    public Optional<RequirementDoc> findRequirement(String docId) {
        Map<String, Object> row = intakeMapper.findRequirementRow(docId);
        return requirement(row);
    }

    @Override
    public Optional<RequirementDoc> findRequirementByWorkflow(String workflowRunId) {
        Map<String, Object> row = intakeMapper.findRequirementByWorkflowRow(workflowRunId);
        return requirement(row);
    }

    @Override
    public List<RequirementVersion> listRequirementVersions(String docId) {
        return intakeMapper.listRequirementVersionRows(docId).stream()
                .map(row -> new RequirementVersion(
                        MybatisRowReader.string(row, "docId"),
                        MybatisRowReader.integer(row, "version"),
                        MybatisRowReader.string(row, "content"),
                        actor(row, "createdByActorType", "createdByActorId")
                ))
                .toList();
    }

    @Override
    public Optional<Ticket> findTicket(String ticketId) {
        Map<String, Object> row = intakeMapper.findTicketRow(ticketId);
        if (MybatisRowReader.isEmpty(row)) {
            return Optional.empty();
        }
        return Optional.of(ticket(row));
    }

    @Override
    public List<Ticket> listTicketsForWorkflow(String workflowRunId) {
        return intakeMapper.listTicketRows(workflowRunId).stream()
                .map(this::ticket)
                .toList();
    }

    @Override
    public List<TicketEvent> listTicketEvents(String ticketId) {
        return intakeMapper.listTicketEventRows(ticketId).stream()
                .map(row -> new TicketEvent(
                        MybatisRowReader.string(row, "eventId"),
                        MybatisRowReader.string(row, "ticketId"),
                        MybatisRowReader.string(row, "eventType"),
                        actor(row, "actorType", "actorId"),
                        MybatisRowReader.string(row, "body"),
                        MybatisRowReader.nullableJsonPayload(row, "dataJson")
                ))
                .toList();
    }

    @Override
    public List<Ticket> listOpenTickets(String workflowRunId) {
        return intakeMapper.listOpenTicketRows(workflowRunId).stream()
                .map(this::ticket)
                .toList();
    }

    @Override
    public boolean hasOpenTaskBlocker(String taskId) {
        return intakeMapper.hasOpenTaskBlocker(taskId);
    }

    @Override
    public void saveRequirement(RequirementDoc requirementDoc) {
        intakeMapper.upsertRequirement(requirementDoc);
    }

    @Override
    public void appendRequirementVersion(RequirementVersion version) {
        intakeMapper.insertRequirementVersion(
                version,
                version.createdBy().type().name(),
                version.createdBy().actorId()
        );
    }

    @Override
    public void saveTicket(Ticket ticket) {
        intakeMapper.upsertTicket(
                ticket,
                ticket.createdBy().type().name(),
                ticket.createdBy().actorId(),
                ticket.assignee().type().name(),
                ticket.assignee().actorId()
        );
    }

    @Override
    public void appendTicketEvent(TicketEvent event) {
        intakeMapper.insertTicketEvent(
                event,
                event.actor().type().name(),
                event.actor().actorId(),
                event.dataJson() == null ? null : event.dataJson().json()
        );
    }

    private Optional<RequirementDoc> requirement(Map<String, Object> row) {
        if (MybatisRowReader.isEmpty(row)) {
            return Optional.empty();
        }
        return Optional.of(new RequirementDoc(
                MybatisRowReader.string(row, "docId"),
                MybatisRowReader.string(row, "workflowRunId"),
                MybatisRowReader.integer(row, "currentVersion"),
                MybatisRowReader.nullableInteger(row, "confirmedVersion"),
                MybatisRowReader.enumValue(row, "status", RequirementStatus.class),
                MybatisRowReader.string(row, "title")
        ));
    }

    private Ticket ticket(Map<String, Object> row) {
        return new Ticket(
                MybatisRowReader.string(row, "ticketId"),
                MybatisRowReader.string(row, "workflowRunId"),
                MybatisRowReader.enumValue(row, "type", TicketType.class),
                MybatisRowReader.enumValue(row, "blockingScope", TicketBlockingScope.class),
                MybatisRowReader.enumValue(row, "status", TicketStatus.class),
                MybatisRowReader.string(row, "title"),
                actor(row, "createdByActorType", "createdByActorId"),
                actor(row, "assigneeActorType", "assigneeActorId"),
                MybatisRowReader.nullableString(row, "originNodeId"),
                MybatisRowReader.nullableString(row, "requirementDocId"),
                MybatisRowReader.nullableInteger(row, "requirementDocVersion"),
                MybatisRowReader.nullableString(row, "taskId"),
                MybatisRowReader.jsonPayload(row, "payloadJson")
        );
    }

    private ActorRef actor(Map<String, Object> row, String typeKey, String actorIdKey) {
        return new ActorRef(
                MybatisRowReader.enumValue(row, typeKey, ActorType.class),
                MybatisRowReader.string(row, actorIdKey)
        );
    }
}
