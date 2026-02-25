package com.agentx.agentxbackend.requirement.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RequirementDocMapper {

    @Insert("""
        insert into requirement_docs (
            doc_id,
            session_id,
            current_version,
            confirmed_version,
            status,
            title,
            created_at,
            updated_at
        ) values (
            #{row.docId},
            #{row.sessionId},
            #{row.currentVersion},
            #{row.confirmedVersion},
            #{row.status},
            #{row.title},
            #{row.createdAt},
            #{row.updatedAt}
        )
        """)
    int insert(@Param("row") RequirementDocRow row);

    @Select("""
        select
            doc_id,
            session_id,
            current_version,
            confirmed_version,
            status,
            title,
            created_at,
            updated_at
        from requirement_docs
        where doc_id = #{docId}
        """)
    RequirementDocRow findById(@Param("docId") String docId);

    @Select("""
        select
            doc_id,
            session_id,
            current_version,
            confirmed_version,
            status,
            title,
            created_at,
            updated_at
        from requirement_docs
        where session_id = #{sessionId}
        order by updated_at desc, created_at desc
        limit 1
        """)
    RequirementDocRow findLatestBySessionId(@Param("sessionId") String sessionId);

    @Update("""
        update requirement_docs
        set
            current_version = #{row.currentVersion},
            confirmed_version = #{row.confirmedVersion},
            status = #{row.status},
            title = #{row.title},
            updated_at = #{row.updatedAt}
        where doc_id = #{row.docId}
        """)
    int update(@Param("row") RequirementDocRow row);

    @Update("""
        update requirement_docs
        set
            current_version = #{row.currentVersion},
            confirmed_version = #{row.confirmedVersion},
            status = #{row.status},
            title = #{row.title},
            updated_at = #{row.updatedAt}
        where doc_id = #{row.docId}
          and current_version = #{expectedCurrentVersion}
        """)
    int updateAfterVersionAppend(
        @Param("row") RequirementDocRow row,
        @Param("expectedCurrentVersion") int expectedCurrentVersion
    );
}
