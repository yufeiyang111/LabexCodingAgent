package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentRunInteraction;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentRunInteractionMapper extends BaseMapper<AgentRunInteraction> {
    /** 只返回仍在等待的任务对应的最新已解决交互，避免旧回答恢复新的等待轮次。 */
    @Select("""
            SELECT candidate.*
            FROM t_agent_run_interaction candidate
            INNER JOIN t_agent_task task ON task.task_id = candidate.task_id
            WHERE (
                (task.status = 'waiting_user'
                    AND candidate.interaction_type = 'question'
                    AND candidate.status IN ('answered', 'cancelled'))
                OR
                (task.status = 'waiting_approval'
                    AND candidate.interaction_type IN ('permission', 'network')
                    AND candidate.status IN ('approved', 'rejected'))
            )
            AND NOT EXISTS (
                SELECT 1
                FROM t_agent_run_interaction newer
                WHERE newer.task_id = candidate.task_id
                  AND (
                    COALESCE(newer.create_time, '1970-01-01 00:00:00') > COALESCE(candidate.create_time, '1970-01-01 00:00:00')
                    OR (COALESCE(newer.create_time, '1970-01-01 00:00:00') = COALESCE(candidate.create_time, '1970-01-01 00:00:00')
                        AND newer.interaction_id > candidate.interaction_id)
                  )
            )
            ORDER BY candidate.update_time ASC, candidate.interaction_id ASC
            LIMIT #{limit}
            """)
    List<AgentRunInteraction> selectResolvedAwaitingResume(@Param("limit") int limit);
}