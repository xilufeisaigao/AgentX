package com.agentx.agentxbackend.ticket.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TicketEventMapper {

    @Insert("""
        insert into ticket_events (
            event_id,
            ticket_id,
            event_type,
            actor_role,
            body,
            data_json,
            created_at
        ) values (
            #{row.eventId},
            #{row.ticketId},
            #{row.eventType},
            #{row.actorRole},
            #{row.body},
            #{row.dataJson},
            #{row.createdAt}
        )
        """)
    int insert(@Param("row") TicketEventRow row);

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
        order by created_at asc, event_id asc
        """)
    List<TicketEventRow> findByTicketId(@Param("ticketId") String ticketId);
}
