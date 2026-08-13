package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentTask;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTask> {
    @Select("SELECT * FROM t_agent_task WHERE task_id = #{taskId} FOR UPDATE")
    AgentTask selectByTaskIdForUpdate(@Param("taskId") Long taskId);

    /**
     * 周期 reconciler 的候选集：只挑选非终态、且当前没有任何活动 execution lease 的任务。
     * 带租约的等待态（waiting_workspace/waiting_environment）由各自的专属恢复调度器续期，
     * 这里仅负责发现；waiting_approval/waiting_user 的交互任务不在候选集内。
     *
     * <p>谓词必须与 claim CAS（AgentRunLifecycleService.claimRecovery/claimDispatch）对齐：
     * CAS 只在 owner 为 NULL 或租约已过期时接受 claim，空字符串 owner 被视为真实 owner，
     * 因此候选集也不能把 execution_owner = '' 当作无租约，只能等其租约实际过期。
     */
    @Select("SELECT * FROM t_agent_task "
            + "WHERE status IN ('queued', 'preparing', 'running', 'recovering', "
            + "'waiting_workspace', 'waiting_environment') "
            + "AND (execution_owner IS NULL OR execution_lease_expires_at IS NULL "
            + "OR execution_lease_expires_at <= #{now}) "
            + "ORDER BY task_id ASC LIMIT #{batchSize}")
    List<AgentTask> selectExpiredLeaseCandidates(@Param("now") LocalDateTime now,
                                                 @Param("batchSize") int batchSize);
}
