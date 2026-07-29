package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentRunEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentRunEventMapper extends BaseMapper<AgentRunEvent> {
    @Select("SELECT COALESCE(MAX(sequence_number), 0) FROM t_agent_run_event WHERE task_id = #{taskId}")
    Long selectMaxSequenceByTaskId(@Param("taskId") Long taskId);
}
