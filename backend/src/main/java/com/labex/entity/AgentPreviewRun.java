package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Agent 启动的常驻预览进程的非敏感持久化状态；命令原文仅保留在受控 workspace artifact。 */
@TableName("t_agent_preview_run")
public class AgentPreviewRun {
    @TableId(value = "preview_id", type = IdType.INPUT) private String previewId;
    @TableField("student_id") private Integer studentId;
    @TableField("project_id") private Integer projectId;
    @TableField("task_id") private Long taskId;
    @TableField("status") private String status;
    @TableField("workdir") private String workdir;
    @TableField("port") private Integer port;
    @TableField("public_url") private String publicUrl;
    @TableField("process_id") private Long processId;
    @TableField("process_host_id") private String processHostId;
    @TableField("owner_instance") private String ownerInstance;
    @TableField("worker_runtime") private String workerRuntime;
    @TableField("output_path") private String outputPath;
    @TableField("last_http_status") private Integer lastHttpStatus;
    @TableField("failure_code") private String failureCode;
    @TableField("started_at") private LocalDateTime startedAt;
    @TableField("ready_at") private LocalDateTime readyAt;
    @TableField("stopped_at") private LocalDateTime stoppedAt;
    @TableField("create_time") private LocalDateTime createTime;
    @TableField("update_time") private LocalDateTime updateTime;

    public String getPreviewId() { return previewId; } public void setPreviewId(String value) { previewId = value; }
    public Integer getStudentId() { return studentId; } public void setStudentId(Integer value) { studentId = value; }
    public Integer getProjectId() { return projectId; } public void setProjectId(Integer value) { projectId = value; }
    public Long getTaskId() { return taskId; } public void setTaskId(Long value) { taskId = value; }
    public String getStatus() { return status; } public void setStatus(String value) { status = value; }
    public String getWorkdir() { return workdir; } public void setWorkdir(String value) { workdir = value; }
    public Integer getPort() { return port; } public void setPort(Integer value) { port = value; }
    public String getPublicUrl() { return publicUrl; } public void setPublicUrl(String value) { publicUrl = value; }
    public Long getProcessId() { return processId; } public void setProcessId(Long value) { processId = value; }
    public String getProcessHostId() { return processHostId; } public void setProcessHostId(String value) { processHostId = value; }
    public String getOwnerInstance() { return ownerInstance; } public void setOwnerInstance(String value) { ownerInstance = value; }
    public String getWorkerRuntime() { return workerRuntime; } public void setWorkerRuntime(String value) { workerRuntime = value; }
    public String getOutputPath() { return outputPath; } public void setOutputPath(String value) { outputPath = value; }
    public Integer getLastHttpStatus() { return lastHttpStatus; } public void setLastHttpStatus(Integer value) { lastHttpStatus = value; }
    public String getFailureCode() { return failureCode; } public void setFailureCode(String value) { failureCode = value; }
    public LocalDateTime getStartedAt() { return startedAt; } public void setStartedAt(LocalDateTime value) { startedAt = value; }
    public LocalDateTime getReadyAt() { return readyAt; } public void setReadyAt(LocalDateTime value) { readyAt = value; }
    public LocalDateTime getStoppedAt() { return stoppedAt; } public void setStoppedAt(LocalDateTime value) { stoppedAt = value; }
    public LocalDateTime getCreateTime() { return createTime; } public void setCreateTime(LocalDateTime value) { createTime = value; }
    public LocalDateTime getUpdateTime() { return updateTime; } public void setUpdateTime(LocalDateTime value) { updateTime = value; }
}