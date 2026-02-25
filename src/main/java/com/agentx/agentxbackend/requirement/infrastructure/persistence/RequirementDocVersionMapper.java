package com.agentx.agentxbackend.requirement.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RequirementDocVersionMapper {

    @Insert("""
        insert into requirement_doc_versions (
            doc_id,
            version,
            content,
            created_by_role,
            created_at
        ) values (
            #{row.docId},
            #{row.version},
            #{row.content},
            #{row.createdByRole},
            #{row.createdAt}
        )
        """)
    int insert(@Param("row") RequirementDocVersionRow row);

    @Select("""
        select
            doc_id,
            version,
            content,
            created_by_role,
            created_at
        from requirement_doc_versions
        where doc_id = #{docId}
          and version = #{version}
        """)
    RequirementDocVersionRow findByDocIdAndVersion(
        @Param("docId") String docId,
        @Param("version") int version
    );
}
