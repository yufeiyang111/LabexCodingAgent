package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("t_agent_run_artifact")
public class AgentRunArtifact {
    @TableId(value = "artifact_id", type = IdType.AUTO) private Long artifactId;
    @TableField("task_id") private Long taskId;
    @TableField("artifact_type") private String artifactType;
    @TableField("artifact_path") private String artifactPath;
    @TableField("content") private String content;
    @TableField("sha256") private String sha256;
    @TableField("create_time") private LocalDateTime createTime;
    public Long getArtifactId() { return artifactId; } public void setArtifactId(Long value) { artifactId = value; }
    public Long getTaskId() { return taskId; } public void setTaskId(Long value) { taskId = value; }
    public String getArtifactType() { return artifactType; } public void setArtifactType(String value) { artifactType = value; }
    public String getArtifactPath() { return artifactPath; } public void setArtifactPath(String value) { artifactPath = value; }
    public String getContent() { return content; } public void setContent(String value) { content = value; }
    public String getSha256() { return sha256; } public void setSha256(String value) { sha256 = value; }
    public LocalDateTime getCreateTime() { return createTime; } public void setCreateTime(LocalDateTime value) { createTime = value; }
}
