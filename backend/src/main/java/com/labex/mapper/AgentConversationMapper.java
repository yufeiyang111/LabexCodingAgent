package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {
    @Select("SELECT * FROM t_agent_conversation "
            + "WHERE student_id = #{studentId} AND project_id = #{projectId} "
            + "AND conversation_id = #{conversationId} AND status = 1 FOR UPDATE")
    AgentConversation selectOwnedForUpdate(@Param("studentId") Integer studentId,
                                           @Param("projectId") Integer projectId,
                                           @Param("conversationId") String conversationId);
}
