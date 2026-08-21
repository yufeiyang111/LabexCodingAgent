package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentRunEvent;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentRunEventMapper extends BaseMapper<AgentRunEvent> {
    @Select("SELECT COALESCE(MAX(sequence_number), 0) FROM t_agent_run_event WHERE task_id = #{taskId}")
    Long selectMaxSequenceByTaskId(@Param("taskId") Long taskId);

    @Select("SELECT DISTINCT task_id FROM t_agent_run_event WHERE event_type = #{eventType} ORDER BY task_id")
    List<Long> selectDistinctTaskIdsByEventType(@Param("eventType") String eventType);

    /** 只读查询任务最新一条事件（运维运行态展示用）。 */
    @Select("SELECT * FROM t_agent_run_event WHERE task_id = #{taskId} ORDER BY sequence_number DESC LIMIT 1")
    AgentRunEvent selectLatestByTaskId(@Param("taskId") Long taskId);
}
