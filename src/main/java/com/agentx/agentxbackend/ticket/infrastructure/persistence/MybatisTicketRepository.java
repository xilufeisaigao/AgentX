package com.agentx.agentxbackend.ticket.infrastructure.persistence;

import com.agentx.agentxbackend.ticket.application.port.out.TicketRepository;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisTicketRepository implements TicketRepository {

    private final TicketMapper mapper;

    public MybatisTicketRepository(TicketMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Ticket save(Ticket ticket) {
        int inserted = mapper.insert(toRow(ticket));
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert ticket: " + ticket.ticketId());
        }
        return ticket;
    }

    @Override
    public Optional<Ticket> findById(String ticketId) {
        TicketRow row = mapper.findById(ticketId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public Ticket update(Ticket ticket) {
        int updated = mapper.update(toRow(ticket));
        if (updated != 1) {
            throw new IllegalStateException("Failed to update ticket: " + ticket.ticketId());
        }
        return ticket;
    }

    @Override
    public boolean claimIfOpen(String ticketId, String claimedBy, Instant leaseUntil, Instant updatedAt) {
        int updated = mapper.claimIfOpen(
            ticketId,
            claimedBy,
            Timestamp.from(leaseUntil),
            Timestamp.from(updatedAt)
        );
        return updated == 1;
    }

    @Override
    public boolean movePlanningLeaseIfInProgressClaimed(
        String ticketId,
        String expectedClaimedBy,
        String nextClaimedBy,
        Instant leaseUntil,
        Instant updatedAt
    ) {
        int updated = mapper.movePlanningLeaseIfInProgressClaimed(
            ticketId,
            expectedClaimedBy,
            nextClaimedBy,
            Timestamp.from(leaseUntil),
            Timestamp.from(updatedAt)
        );
        return updated == 1;
    }

    @Override
    public List<Ticket> findBySessionAndFilters(
        String sessionId,
        String status,
        String assigneeRole,
        String type
    ) {
        List<TicketRow> rows = mapper.findBySessionAndFilters(sessionId, status, assigneeRole, type);
        List<Ticket> tickets = new ArrayList<>(rows.size());
        for (TicketRow row : rows) {
            tickets.add(toDomain(row));
        }
        return tickets;
    }

    private TicketRow toRow(Ticket ticket) {
        TicketRow row = new TicketRow();
        row.setTicketId(ticket.ticketId());
        row.setSessionId(ticket.sessionId());
        row.setType(ticket.type().name());
        row.setStatus(ticket.status().name());
        row.setTitle(ticket.title());
        row.setCreatedByRole(ticket.createdByRole());
        row.setAssigneeRole(ticket.assigneeRole());
        row.setRequirementDocId(ticket.requirementDocId());
        row.setRequirementDocVer(ticket.requirementDocVer());
        row.setPayloadJson(ticket.payloadJson());
        row.setClaimedBy(ticket.claimedBy());
        row.setLeaseUntil(ticket.leaseUntil() == null ? null : Timestamp.from(ticket.leaseUntil()));
        row.setCreatedAt(Timestamp.from(ticket.createdAt()));
        row.setUpdatedAt(Timestamp.from(ticket.updatedAt()));
        return row;
    }

    private Ticket toDomain(TicketRow row) {
        return new Ticket(
            row.getTicketId(),
            row.getSessionId(),
            TicketType.valueOf(row.getType()),
            TicketStatus.valueOf(row.getStatus()),
            row.getTitle(),
            row.getCreatedByRole(),
            row.getAssigneeRole(),
            row.getRequirementDocId(),
            row.getRequirementDocVer(),
            row.getPayloadJson(),
            row.getClaimedBy(),
            row.getLeaseUntil() == null ? null : row.getLeaseUntil().toInstant(),
            row.getCreatedAt().toInstant(),
            row.getUpdatedAt().toInstant()
        );
    }
}
