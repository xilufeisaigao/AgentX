package com.agentx.agentxbackend.workforce.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WorkerMapper {

    @Insert("""
        insert into workers (
            worker_id,
            status,
            created_at,
            updated_at
        ) values (
            #{row.workerId},
            #{row.status},
            #{row.createdAt},
            #{row.updatedAt}
        )
        """)
    int insert(@Param("row") WorkerRow row);

    @Select("""
        select
            worker_id,
            status,
            created_at,
            updated_at
        from workers
        where worker_id = #{workerId}
        """)
    WorkerRow findById(@Param("workerId") String workerId);

    @Update("""
        update workers
        set
            status = #{status},
            updated_at = #{updatedAt}
        where worker_id = #{workerId}
        """)
    int updateStatus(
        @Param("workerId") String workerId,
        @Param("status") String status,
        @Param("updatedAt") java.sql.Timestamp updatedAt
    );

    @Select("""
        select count(1)
        from workers
        where status = #{status}
        """)
    int countByStatus(@Param("status") String status);

    @Select("""
        select count(1)
        from workers
        """)
    int countAll();

    @Select("""
        select
            worker_id,
            status,
            created_at,
            updated_at
        from workers
        where status = #{status}
        order by updated_at asc, worker_id asc
        limit #{limit}
        """)
    List<WorkerRow> findByStatus(
        @Param("status") String status,
        @Param("limit") int limit
    );
}
