package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.CommandApproval;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CommandApprovalMapper extends BaseMapper<CommandApproval> {
    @Select("""
            SELECT approval.*
            FROM t_command_approval approval
            INNER JOIN t_agent_task task ON task.task_id = approval.task_id
            WHERE approval.source = 'agent_shell'
              AND approval.status IN ('consumed', 'rejected', 'expired')
              AND task.status = 'waiting_approval'
            ORDER BY approval.update_time DESC
            LIMIT #{limit}
            """)
    List<CommandApproval> selectResolvedAgentApprovalsAwaitingResume(@Param("limit") int limit);
}
