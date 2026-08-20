package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** OpenCode 风格的运行消息容器；多个 Part 可以归属于同一轮模型消息。 */
@TableName("t_agent_run_message")
public class AgentRunMessage {
    @TableId(value = "run_message_id", type = IdType.AUTO)
    private Long runMessageId;
    @TableField("task_id")
    private Long taskId;
    @TableField("conversation_id")
    private String conversationId;
    @TableField("student_id")
    private Integer studentId;
    @TableField("project_id")
    private Integer projectId;
    @TableField("message_key")
    private String messageKey;
    @TableField("sequence_number")
    private Long sequenceNumber;
    @TableField("parent_message_id")
    private Long parentMessageId;
    @TableField("conversation_sequence")
    private Long conversationSequence;
    @TableField("role")
    private String role;
    @TableField("status")
    private String status;
    @TableField("content")
    private String content;
    @TableField("metadata")
    private String metadata;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public Long getRunMessageId() { return runMessageId; }
    public void setRunMessageId(Long value) { runMessageId = value; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { taskId = value; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String value) { conversationId = value; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer value) { studentId = value; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer value) { projectId = value; }
    public String getMessageKey() { return messageKey; }
    public void setMessageKey(String value) { messageKey = value; }
    public Long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(Long value) { sequenceNumber = value; }
    public Long getParentMessageId() { return parentMessageId; }
    public void setParentMessageId(Long value) { parentMessageId = value; }
    public Long getConversationSequence() { return conversationSequence; }
    public void setConversationSequence(Long value) { conversationSequence = value; }
    public String getRole() { return role; }
    public void setRole(String value) { role = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String value) { metadata = value; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime value) { createTime = value; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime value) { updateTime = value; }
}