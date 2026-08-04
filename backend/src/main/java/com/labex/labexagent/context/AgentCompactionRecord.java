package com.labex.labexagent.context;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 持久化的上下文压缩 epoch；用于重启恢复和失败审计。 */
@TableName("t_agent_compaction_record")
public class AgentCompactionRecord {
    @TableId(value = "compaction_id", type = IdType.AUTO)
    private Long compactionId;
    @TableField("task_id")
    private Long taskId;
    @TableField("scope")
    private String scope;
    @TableField("conversation_id")
    private String conversationId;
    @TableField("student_id")
    private Integer studentId;
    @TableField("project_id")
    private Integer projectId;
    @TableField("execution_epoch")
    private Long executionEpoch;
    @TableField("compaction_epoch")
    private Long compactionEpoch;
    @TableField("trigger_reason")
    private String triggerReason;
    @TableField("status")
    private String status;
    @TableField("previous_summary")
    private String previousSummary;
    @TableField("summary")
    private String summary;
    @TableField("compacted_head")
    private String compactedHead;
    @TableField("retained_tail")
    private String retainedTail;
    @TableField("tail_start_index")
    private Integer tailStartIndex;
    @TableField("retained_turns")
    private Integer retainedTurns;
    @TableField("source_max_sequence")
    private Long sourceMaxSequence;
    @TableField("source_max_task_id")
    private Long sourceMaxTaskId;
    @TableField("estimated_tokens_before")
    private Integer estimatedTokensBefore;
    @TableField("estimated_tokens_after")
    private Integer estimatedTokensAfter;
    @TableField("model_window_tokens")
    private Integer modelWindowTokens;
    @TableField("reserved_output_tokens")
    private Integer reservedOutputTokens;
    @TableField("failure_reason")
    private String failureReason;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public Long getCompactionId() { return compactionId; }
    public void setCompactionId(Long value) { compactionId = value; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { taskId = value; }
    public String getScope() { return scope; }
    public void setScope(String value) { scope = value; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String value) { conversationId = value; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer value) { studentId = value; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer value) { projectId = value; }
    public Long getExecutionEpoch() { return executionEpoch; }
    public void setExecutionEpoch(Long value) { executionEpoch = value; }
    public Long getCompactionEpoch() { return compactionEpoch; }
    public void setCompactionEpoch(Long value) { compactionEpoch = value; }
    public String getTriggerReason() { return triggerReason; }
    public void setTriggerReason(String value) { triggerReason = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getPreviousSummary() { return previousSummary; }
    public void setPreviousSummary(String value) { previousSummary = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
    public String getCompactedHead() { return compactedHead; }
    public void setCompactedHead(String value) { compactedHead = value; }
    public String getRetainedTail() { return retainedTail; }
    public void setRetainedTail(String value) { retainedTail = value; }
    public Integer getTailStartIndex() { return tailStartIndex; }
    public void setTailStartIndex(Integer value) { tailStartIndex = value; }
    public Integer getRetainedTurns() { return retainedTurns; }
    public void setRetainedTurns(Integer value) { retainedTurns = value; }
    public Long getSourceMaxSequence() { return sourceMaxSequence; }
    public void setSourceMaxSequence(Long value) { sourceMaxSequence = value; }
    public Long getSourceMaxTaskId() { return sourceMaxTaskId; }
    public void setSourceMaxTaskId(Long value) { sourceMaxTaskId = value; }
    public Integer getEstimatedTokensBefore() { return estimatedTokensBefore; }
    public void setEstimatedTokensBefore(Integer value) { estimatedTokensBefore = value; }
    public Integer getEstimatedTokensAfter() { return estimatedTokensAfter; }
    public void setEstimatedTokensAfter(Integer value) { estimatedTokensAfter = value; }
    public Integer getModelWindowTokens() { return modelWindowTokens; }
    public void setModelWindowTokens(Integer value) { modelWindowTokens = value; }
    public Integer getReservedOutputTokens() { return reservedOutputTokens; }
    public void setReservedOutputTokens(Integer value) { reservedOutputTokens = value; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { failureReason = value; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime value) { createTime = value; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime value) { updateTime = value; }
}
