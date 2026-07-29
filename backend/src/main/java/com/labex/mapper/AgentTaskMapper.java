package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTask> {
    @Select("SELECT * FROM t_agent_task WHERE task_id = #{taskId} FOR UPDATE")
    AgentTask selectByTaskIdForUpdate(@Param("taskId") Long taskId);
}
