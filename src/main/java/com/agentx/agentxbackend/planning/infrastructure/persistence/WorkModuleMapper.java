package com.agentx.agentxbackend.planning.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WorkModuleMapper {

    @Insert("""
        insert into work_modules (
            module_id,
            session_id,
            name,
            description,
            created_at,
            updated_at
        ) values (
            #{row.moduleId},
            #{row.sessionId},
            #{row.name},
            #{row.description},
            #{row.createdAt},
            #{row.updatedAt}
        )
        """)
    int insert(@Param("row") WorkModuleRow row);

    @Select("""
        select
            module_id,
            session_id,
            name,
            description,
            created_at,
            updated_at
        from work_modules
        where module_id = #{moduleId}
        """)
    WorkModuleRow findById(@Param("moduleId") String moduleId);
}
