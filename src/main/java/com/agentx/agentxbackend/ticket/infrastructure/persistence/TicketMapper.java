package com.agentx.agentxbackend.ticket.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TicketMapper {

    @Insert("""
        insert into tickets (
            ticket_id,
            session_id,
            type,
            status,
            title,
            created_by_role,
            assignee_role,
            requirement_doc_id,
            requirement_doc_ver,
            payload_json,
            claimed_by,
            lease_until,
            created_at,
            updated_at
        ) values (
            #{row.ticketId},
            #{row.sessionId},
            #{row.type},
            #{row.status},
            #{row.title},
            #{row.createdByRole},
            #{row.assigneeRole},
            #{row.requirementDocId},
            #{row.requirementDocVer},
            #{row.payloadJson},
            #{row.claimedBy},
            #{row.leaseUntil},
            #{row.createdAt},
            #{row.updatedAt}
        )
        """)
    int insert(@Param("row") TicketRow row);

    @Select("""
        select
            ticket_id,
            session_id,
            type,
            status,
            title,
            created_by_role,
            assignee_role,
            requirement_doc_id,
            requirement_doc_ver,
            payload_json,
            claimed_by,
            lease_until,
            created_at,
            updated_at
        from tickets
        where ticket_id = #{ticketId}
        """)
    TicketRow findById(@Param("ticketId") String ticketId);

    @Update("""
        update tickets
        set
            status = #{row.status},
            title = #{row.title},
            payload_json = #{row.payloadJson},
            claimed_by = #{row.claimedBy},
            lease_until = #{row.leaseUntil},
            updated_at = #{row.updatedAt}
        where ticket_id = #{row.ticketId}
        """)
    int update(@Param("row") TicketRow row);

    @Update("""
        update tickets
        set
            status = 'IN_PROGRESS',
            claimed_by = #{claimedBy},
            lease_until = #{leaseUntil},
            updated_at = #{updatedAt}
        where ticket_id = #{ticketId}
          and status = 'OPEN'
        """)
    int claimIfOpen(
        @Param("ticketId") String ticketId,
        @Param("claimedBy") String claimedBy,
        @Param("leaseUntil") java.sql.Timestamp leaseUntil,
        @Param("updatedAt") java.sql.Timestamp updatedAt
    );

    @Update("""
        update tickets
        set
            claimed_by = #{nextClaimedBy},
            lease_until = #{leaseUntil},
            updated_at = #{updatedAt}
        where ticket_id = #{ticketId}
          and status = 'IN_PROGRESS'
          and claimed_by = #{expectedClaimedBy}
        """)
    int movePlanningLeaseIfInProgressClaimed(
        @Param("ticketId") String ticketId,
        @Param("expectedClaimedBy") String expectedClaimedBy,
        @Param("nextClaimedBy") String nextClaimedBy,
        @Param("leaseUntil") java.sql.Timestamp leaseUntil,
        @Param("updatedAt") java.sql.Timestamp updatedAt
    );

    @Select({
        "<script>",
        "select",
        "    ticket_id,",
        "    session_id,",
        "    type,",
        "    status,",
        "    title,",
        "    created_by_role,",
        "    assignee_role,",
        "    requirement_doc_id,",
        "    requirement_doc_ver,",
        "    payload_json,",
        "    claimed_by,",
        "    lease_until,",
        "    created_at,",
        "    updated_at",
        "from tickets",
        "where session_id = #{sessionId}",
        "<if test='status != null and status != \"\"'>",
        "  and status = #{status}",
        "</if>",
        "<if test='assigneeRole != null and assigneeRole != \"\"'>",
        "  and assignee_role = #{assigneeRole}",
        "</if>",
        "<if test='type != null and type != \"\"'>",
        "  and type = #{type}",
        "</if>",
        "order by created_at asc",
        "</script>"
    })
    List<TicketRow> findBySessionAndFilters(
        @Param("sessionId") String sessionId,
        @Param("status") String status,
        @Param("assigneeRole") String assigneeRole,
        @Param("type") String type
    );
}
