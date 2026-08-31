package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("t_agent_token_usage")
public class AgentTokenUsage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String conversationId;
    private String sessionId;
    private Long taskId;
    private Long executionEpoch;
    private Integer studentId;
    private Integer projectId;
    private String provider;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer cachedTokens;
    private Integer cacheWriteTokens;
    private Integer cacheHitTokens;
    private Integer cacheMissTokens;
    private String cacheStatus;
    private Integer iteration;
    private String toolName;
    private LocalDateTime createTime;

    public AgentTokenUsage() {}

    public AgentTokenUsage(String conversationId, String sessionId, Integer studentId, Integer projectId,
                           String provider, String model, int promptTokens, int completionTokens, int totalTokens,
                           int iteration, String toolName) {
        this.conversationId = conversationId;
        this.sessionId = sessionId;
        this.studentId = studentId;
        this.projectId = projectId;
        this.provider = provider;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.cachedTokens = 0;
        this.cacheWriteTokens = 0;
        this.cacheStatus = "not_reported";
        this.iteration = iteration;
        this.toolName = toolName;
        this.createTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getExecutionEpoch() { return executionEpoch; }
    public void setExecutionEpoch(Long executionEpoch) { this.executionEpoch = executionEpoch; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public Integer getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(Integer cachedTokens) { this.cachedTokens = cachedTokens; }
    public Integer getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(Integer cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }
    public Integer getCacheHitTokens() { return cacheHitTokens; }
    public void setCacheHitTokens(Integer cacheHitTokens) { this.cacheHitTokens = cacheHitTokens; }
    public Integer getCacheMissTokens() { return cacheMissTokens; }
    public void setCacheMissTokens(Integer cacheMissTokens) { this.cacheMissTokens = cacheMissTokens; }
    public String getCacheStatus() { return cacheStatus; }
    public void setCacheStatus(String cacheStatus) { this.cacheStatus = cacheStatus; }
    public Integer getIteration() { return iteration; }
    public void setIteration(Integer iteration) { this.iteration = iteration; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
