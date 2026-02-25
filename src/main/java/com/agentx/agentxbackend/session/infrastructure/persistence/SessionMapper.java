package com.agentx.agentxbackend.session.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SessionMapper {

    @Insert("""
        insert into sessions (
            session_id,
            title,
            status,
            created_at,
            updated_at
        ) values (
            #{row.sessionId},
            #{row.title},
            #{row.status},
            #{row.createdAt},
            #{row.updatedAt}
        )
        """)
    int insert(@Param("row") SessionRow row);

    @Select("""
        select
            session_id,
            title,
            status,
            created_at,
            updated_at
        from sessions
        where session_id = #{sessionId}
        """)
    SessionRow findById(@Param("sessionId") String sessionId);

    @Select("""
        select
            session_id,
            title,
            status,
            created_at,
            updated_at
        from sessions
        order by updated_at desc, created_at desc
        """)
    List<SessionRow> findAllOrderByUpdatedAtDesc();

    @Update("""
        update sessions
        set
            status = #{status},
            updated_at = #{updatedAt}
        where session_id = #{sessionId}
        """)
    int updateStatus(
        @Param("sessionId") String sessionId,
        @Param("status") String status,
        @Param("updatedAt") java.sql.Timestamp updatedAt
    );
}
