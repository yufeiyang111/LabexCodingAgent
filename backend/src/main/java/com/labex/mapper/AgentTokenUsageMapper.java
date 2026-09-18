package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentTokenUsage;
import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * 只读：按会话聚合的用量摘要，供用量面板「会话明细统计」使用。
     *
     * <p>GROUP BY 在 SQL 侧下推，避免把该项目全部用量行拉回应用层聚合（对齐
     * {@link #selectWindowTotals} 的既有聚合模式）；标题不在此查询，由控制层用会话实体补齐。
     * 已按最近使用时间倒序。当前依赖 idx_token_student / idx_token_project；若某项目用量行数
     * 增长到影响该查询，可再评估 (student_id, project_id, conversation_id) 复合索引。
     */
    @Select("SELECT conversation_id AS conversationId, "
            + "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completionTokens, "
            + "COALESCE(SUM(total_tokens), 0) AS totalTokens, "
            + "COUNT(*) AS callCount, "
            + "MAX(create_time) AS lastUsedAt "
            + "FROM t_agent_token_usage "
            + "WHERE student_id = #{studentId} AND project_id = #{projectId} "
            + "GROUP BY conversation_id "
            + "ORDER BY MAX(create_time) DESC")
    List<Map<String, Object>> selectConversationSummaries(@Param("studentId") Integer studentId,
                                                          @Param("projectId") Integer projectId);
}
