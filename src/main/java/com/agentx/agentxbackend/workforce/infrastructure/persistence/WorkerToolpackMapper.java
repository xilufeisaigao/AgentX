package com.agentx.agentxbackend.workforce.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkerToolpackMapper {

    @Insert("""
        insert into worker_toolpacks (
            worker_id,
            toolpack_id
        )
        select #{workerId}, #{toolpackId}
        where not exists (
            select 1
            from worker_toolpacks
            where worker_id = #{workerId}
              and toolpack_id = #{toolpackId}
        )
        """)
    int insertIgnore(
        @Param("workerId") String workerId,
        @Param("toolpackId") String toolpackId
    );

    @Select("""
        select toolpack_id
        from worker_toolpacks
        where worker_id = #{workerId}
        order by toolpack_id asc
        """)
    List<String> findToolpackIdsByWorkerId(@Param("workerId") String workerId);

    @Select({
        "<script>",
        "select case when exists(",
        "  select wt.worker_id",
        "  from worker_toolpacks wt",
        "  join workers w on w.worker_id = wt.worker_id",
        "  where w.status = 'READY'",
        "    and wt.toolpack_id in",
        "    <foreach item='toolpackId' collection='toolpackIds' open='(' separator=',' close=')'>",
        "      #{toolpackId}",
        "    </foreach>",
        "  group by wt.worker_id",
        "  having count(distinct wt.toolpack_id) = #{requiredCount}",
        ") then 1 else 0 end",
        "</script>"
    })
    int existsReadyWorkerCoveringAll(
        @Param("toolpackIds") List<String> toolpackIds,
        @Param("requiredCount") int requiredCount
    );
}
