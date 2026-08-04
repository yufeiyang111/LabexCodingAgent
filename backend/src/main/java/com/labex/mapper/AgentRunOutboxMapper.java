package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentRunOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentRunOutboxMapper extends BaseMapper<AgentRunOutbox> {
    @Select("""
            SELECT COUNT(*)
            FROM t_agent_run_outbox outbox
            INNER JOIN t_agent_run_event run_event ON run_event.event_id = outbox.event_id
            WHERE run_event.task_id = #{taskId}
              AND outbox.task_id = #{taskId}
              AND run_event.sequence_number < #{sequenceNumber}
              AND outbox.status <> 'published'
            """)
    long countUnpublishedBeforeSequence(@Param("taskId") Long taskId,
                                        @Param("sequenceNumber") Long sequenceNumber);
}