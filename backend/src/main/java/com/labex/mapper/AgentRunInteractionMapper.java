package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentRunInteraction;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentRunInteractionMapper extends BaseMapper<AgentRunInteraction> {
    /**
     * 只返回仍在等待的任务对应的最新已解决交互，避免旧回答恢复新的等待轮次。
     * 与 claim 谓词一致地排除已过期但尚未 timed_out 的交互，防止死行无限占用扫描批次。
     */
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
                    AND candidate.status IN ('approved', 'rejected', 'timed_out'))
            )
            AND (candidate.expires_time IS NULL OR candidate.status = 'timed_out'
                 OR candidate.expires_time > NOW())
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

    /**
     * 在事务内锁定并校验唯一可恢复交互：所属学生/项目/任务、与任务等待态兼容的类型和已解决状态、
     * 未过期（timed_out 是过期交互的持久化解法）、无未消费 claim，且必须是该任务的最新交互。
     * 并发调用中只有一个事务能取得该行；先提交者赢得 claim，后提交者因谓词不再匹配返回 null。
     */
    @Select("""
            <script>
            SELECT candidate.*
            FROM t_agent_run_interaction candidate
            WHERE candidate.interaction_id = #{interactionId}
              AND candidate.student_id = #{studentId}
              AND candidate.project_id = #{projectId}
              AND candidate.task_id = #{taskId}
              AND candidate.interaction_type IN
              <foreach collection="interactionTypes" item="type" open="(" separator="," close=")">#{type}</foreach>
              AND candidate.status IN
              <foreach collection="resolvedStatuses" item="status" open="(" separator="," close=")">#{status}</foreach>
              AND (candidate.expires_time IS NULL OR candidate.status = 'timed_out'
                   OR candidate.expires_time &gt; #{now})
              AND (candidate.resume_claim_id IS NULL OR candidate.resume_consumed_at IS NOT NULL)
              AND NOT EXISTS (
                  SELECT 1
                  FROM t_agent_run_interaction newer
                  WHERE newer.task_id = candidate.task_id
                    AND (
                      COALESCE(newer.create_time, '1970-01-01 00:00:00') &gt; COALESCE(candidate.create_time, '1970-01-01 00:00:00')
                      OR (COALESCE(newer.create_time, '1970-01-01 00:00:00') = COALESCE(candidate.create_time, '1970-01-01 00:00:00')
                          AND newer.interaction_id &gt; candidate.interaction_id)
                    )
              )
            ORDER BY candidate.create_time DESC, candidate.interaction_id DESC
            LIMIT 1
            FOR UPDATE
            </script>
            """)
    AgentRunInteraction selectResolvedInteractionForUpdate(@Param("studentId") Integer studentId,
                                                           @Param("projectId") Integer projectId,
                                                           @Param("taskId") Long taskId,
                                                           @Param("interactionId") String interactionId,
                                                           @Param("interactionTypes") List<String> interactionTypes,
                                                           @Param("resolvedStatuses") List<String> resolvedStatuses,
                                                           @Param("now") LocalDateTime now);
}