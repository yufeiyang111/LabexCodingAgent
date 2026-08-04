package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentRunPlanItem;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentRunPlanItemMapper extends BaseMapper<AgentRunPlanItem> {
    @Insert("""
            INSERT INTO t_agent_run_plan_item
                (task_id, execution_epoch, plan_revision, position, title, description, status, create_time, update_time)
            VALUES
                (#{taskId}, #{executionEpoch}, #{planRevision}, #{position}, #{title}, #{description}, #{status},
                 #{createTime}, #{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "planItemId", keyColumn = "plan_item_id")
    int insertPlanItem(AgentRunPlanItem item);

    @Select("SELECT * FROM t_agent_run_plan_item WHERE task_id = #{taskId} ORDER BY position ASC")
    List<AgentRunPlanItem> selectByTaskIdOrderByPosition(@Param("taskId") Long taskId);

    @Delete("DELETE FROM t_agent_run_plan_item WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") Long taskId);
}
