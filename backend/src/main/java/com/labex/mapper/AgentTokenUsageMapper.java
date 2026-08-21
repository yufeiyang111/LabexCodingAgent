package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentTokenUsage;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentTokenUsageMapper extends BaseMapper<AgentTokenUsage> {

    /** 只读：指定时间以来的 token 累计（运维指标低频采样用）。 */
    @Select("SELECT COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, "
            + "COALESCE(SUM(total_tokens), 0) AS total_tokens "
            + "FROM t_agent_token_usage WHERE create_time >= #{since}")
    Map<String, Object> selectWindowTotals(@Param("since") LocalDateTime since);
}
