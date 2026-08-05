package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {
    @Select("SELECT COUNT(*) FROM t_agent_message")
    Long countRetainedLegacyRows();
}
