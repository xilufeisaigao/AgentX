package com.agentx.agentxbackend.ticket.infrastructure.persistence;

import com.agentx.agentxbackend.ticket.application.port.out.TicketEventRepository;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Repository
public class MybatisTicketEventRepository implements TicketEventRepository {

    private final TicketEventMapper mapper;

    public MybatisTicketEventRepository(TicketEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TicketEvent save(TicketEvent event) {
        TicketEventRow row = new TicketEventRow();
        row.setEventId(event.eventId());
        row.setTicketId(event.ticketId());
        row.setEventType(event.eventType().name());
        row.setActorRole(event.actorRole());
        row.setBody(event.body());
        row.setDataJson(event.dataJson());
        row.setCreatedAt(Timestamp.from(event.createdAt()));

        int inserted = mapper.insert(row);
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert ticket event: " + event.eventId());
        }
        return event;
    }

    @Override
    public List<TicketEvent> findByTicketId(String ticketId) {
        List<TicketEventRow> rows = mapper.findByTicketId(ticketId);
        List<TicketEvent> events = new ArrayList<>(rows.size());
        for (TicketEventRow row : rows) {
            events.add(toDomain(row));
        }
        return events;
    }

    static TicketEvent toDomain(TicketEventRow row) {
        return new TicketEvent(
            row.getEventId(),
            row.getTicketId(),
            TicketEventType.valueOf(row.getEventType()),
            row.getActorRole(),
            row.getBody(),
            row.getDataJson(),
            row.getCreatedAt().toInstant()
        );
    }
}
