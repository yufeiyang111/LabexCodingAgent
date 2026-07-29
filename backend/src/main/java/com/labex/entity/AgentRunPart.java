package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** OpenCode 风格的运行消息分片；一个工具调用在生命周期内对应一条可更新的 Part。 */
@TableName("t_agent_run_part")
public class AgentRunPart {
    @TableId(value = "part_id", type = IdType.AUTO)
    private Long partId;
    @TableField("task_id")
    private Long taskId;
    @TableField("conversation_id")
    private String conversationId;
    @TableField("message_id")
    private Long messageId;
    @TableField("student_id")
    private Integer studentId;
    @TableField("project_id")
    private Integer projectId;
    @TableField("part_key")
    private String partKey;
    @TableField("sequence_number")
    private Long sequenceNumber;
    @TableField("part_type")
    private String partType;
    @TableField("status")
    private String status;
    @TableField("tool_call_id")
    private String toolCallId;
    @TableField("tool_name")
    private String toolName;
    @TableField("input_json")
    private String inputJson;
    @TableField("output_text")
    private String outputText;
    @TableField("metadata")
    private String metadata;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public Long getPartId() { return partId; }
    public void setPartId(Long value) { partId = value; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { taskId = value; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String value) { conversationId = value; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long value) { messageId = value; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer value) { studentId = value; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer value) { projectId = value; }
    public String getPartKey() { return partKey; }
    public void setPartKey(String value) { partKey = value; }
    public Long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(Long value) { sequenceNumber = value; }
    public String getPartType() { return partType; }
    public void setPartType(String value) { partType = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String value) { toolCallId = value; }
    public String getToolName() { return toolName; }
    public void setToolName(String value) { toolName = value; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String value) { inputJson = value; }
    public String getOutputText() { return outputText; }
    public void setOutputText(String value) { outputText = value; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String value) { metadata = value; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime value) { createTime = value; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime value) { updateTime = value; }
}