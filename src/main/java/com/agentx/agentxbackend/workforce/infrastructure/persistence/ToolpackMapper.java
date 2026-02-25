package com.agentx.agentxbackend.workforce.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ToolpackMapper {

    @Insert("""
        insert into toolpacks (
            toolpack_id,
            name,
            version,
            kind,
            description,
            created_at
        ) values (
            #{row.toolpackId},
            #{row.name},
            #{row.version},
            #{row.kind},
            #{row.description},
            #{row.createdAt}
        )
        """)
    int insert(@Param("row") ToolpackRow row);

    @Select("""
        select
            toolpack_id,
            name,
            version,
            kind,
            description,
            created_at
        from toolpacks
        where toolpack_id = #{toolpackId}
        """)
    ToolpackRow findById(@Param("toolpackId") String toolpackId);

    @Select("""
        select
            toolpack_id,
            name,
            version,
            kind,
            description,
            created_at
        from toolpacks
        where name = #{name}
          and version = #{version}
        limit 1
        """)
    ToolpackRow findByNameAndVersion(
        @Param("name") String name,
        @Param("version") String version
    );

    @Select("""
        select
            toolpack_id,
            name,
            version,
            kind,
            description,
            created_at
        from toolpacks
        order by created_at asc, toolpack_id asc
        """)
    List<ToolpackRow> findAll();

    @Select("""
        select
            t.toolpack_id,
            t.name,
            t.version,
            t.kind,
            t.description,
            t.created_at
        from toolpacks t
        join worker_toolpacks wt on wt.toolpack_id = t.toolpack_id
        where wt.worker_id = #{workerId}
        order by t.created_at asc, t.toolpack_id asc
        """)
    List<ToolpackRow> findByWorkerId(@Param("workerId") String workerId);
}
