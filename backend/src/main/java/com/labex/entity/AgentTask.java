package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value="t_agent_task")
public class AgentTask {
    @TableId(value="task_id", type=IdType.AUTO)
    private Long taskId;
    @TableField(value="conversation_id")
    private String conversationId;
    @TableField(value="session_id")
    private String sessionId;
    @TableField(value="student_id")
    private Integer studentId;
    @TableField(value="project_id")
    private Integer projectId;
    @TableField(value="title")
    private String title;
    @TableField(value="mode")
    private String mode;
    @TableField(value="status")
    private String status;
    @TableField(value="current_step")
    private String currentStep;
    @TableField(value="summary")
    private String summary;
    @TableField(value="create_time")
    private LocalDateTime createTime;
    @TableField(value="update_time")
    private LocalDateTime updateTime;

    public Long getTaskId() {
        return this.taskId;
    }

    public String getConversationId() {
        return this.conversationId;
    }

    public String getSessionId() {
        return this.sessionId;
    }

    public Integer getStudentId() {
        return this.studentId;
    }

    public Integer getProjectId() {
        return this.projectId;
    }

    public String getTitle() {
        return this.title;
    }

    public String getMode() {
        return this.mode;
    }

    public String getStatus() {
        return this.status;
    }

    public String getCurrentStep() {
        return this.currentStep;
    }

    public String getSummary() {
        return this.summary;
    }

    public LocalDateTime getCreateTime() {
        return this.createTime;
    }

    public LocalDateTime getUpdateTime() {
        return this.updateTime;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setStudentId(Integer studentId) {
        this.studentId = studentId;
    }

    public void setProjectId(Integer projectId) {
        this.projectId = projectId;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setCurrentStep(String currentStep) {
        this.currentStep = currentStep;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof AgentTask)) {
            return false;
        }
        AgentTask other = (AgentTask)o;
        if (!other.canEqual(this)) {
            return false;
        }
        Long this$taskId = this.getTaskId();
        Long other$taskId = other.getTaskId();
        if (this$taskId == null ? other$taskId != null : !(this$taskId).equals(other$taskId)) {
            return false;
        }
        Integer this$studentId = this.getStudentId();
        Integer other$studentId = other.getStudentId();
        if (this$studentId == null ? other$studentId != null : !(this$studentId).equals(other$studentId)) {
            return false;
        }
        Integer this$projectId = this.getProjectId();
        Integer other$projectId = other.getProjectId();
        if (this$projectId == null ? other$projectId != null : !(this$projectId).equals(other$projectId)) {
            return false;
        }
        String this$conversationId = this.getConversationId();
        String other$conversationId = other.getConversationId();
        if (this$conversationId == null ? other$conversationId != null : !this$conversationId.equals(other$conversationId)) {
            return false;
        }
        String this$sessionId = this.getSessionId();
        String other$sessionId = other.getSessionId();
        if (this$sessionId == null ? other$sessionId != null : !this$sessionId.equals(other$sessionId)) {
            return false;
        }
        String this$title = this.getTitle();
        String other$title = other.getTitle();
        if (this$title == null ? other$title != null : !this$title.equals(other$title)) {
            return false;
        }
        String this$mode = this.getMode();
        String other$mode = other.getMode();
        if (this$mode == null ? other$mode != null : !this$mode.equals(other$mode)) {
            return false;
        }
        String this$status = this.getStatus();
        String other$status = other.getStatus();
        if (this$status == null ? other$status != null : !this$status.equals(other$status)) {
            return false;
        }
        String this$currentStep = this.getCurrentStep();
        String other$currentStep = other.getCurrentStep();
        if (this$currentStep == null ? other$currentStep != null : !this$currentStep.equals(other$currentStep)) {
            return false;
        }
        String this$summary = this.getSummary();
        String other$summary = other.getSummary();
        if (this$summary == null ? other$summary != null : !this$summary.equals(other$summary)) {
            return false;
        }
        LocalDateTime this$createTime = this.getCreateTime();
        LocalDateTime other$createTime = other.getCreateTime();
        if (this$createTime == null ? other$createTime != null : !(this$createTime).equals(other$createTime)) {
            return false;
        }
        LocalDateTime this$updateTime = this.getUpdateTime();
        LocalDateTime other$updateTime = other.getUpdateTime();
        return !(this$updateTime == null ? other$updateTime != null : !(this$updateTime).equals(other$updateTime));
    }

    protected boolean canEqual(Object other) {
        return other instanceof AgentTask;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        Long $taskId = this.getTaskId();
        result = result * 59 + ($taskId == null ? 43 : ($taskId).hashCode());
        Integer $studentId = this.getStudentId();
        result = result * 59 + ($studentId == null ? 43 : ($studentId).hashCode());
        Integer $projectId = this.getProjectId();
        result = result * 59 + ($projectId == null ? 43 : ($projectId).hashCode());
        String $conversationId = this.getConversationId();
        result = result * 59 + ($conversationId == null ? 43 : $conversationId.hashCode());
        String $sessionId = this.getSessionId();
        result = result * 59 + ($sessionId == null ? 43 : $sessionId.hashCode());
        String $title = this.getTitle();
        result = result * 59 + ($title == null ? 43 : $title.hashCode());
        String $mode = this.getMode();
        result = result * 59 + ($mode == null ? 43 : $mode.hashCode());
        String $status = this.getStatus();
        result = result * 59 + ($status == null ? 43 : $status.hashCode());
        String $currentStep = this.getCurrentStep();
        result = result * 59 + ($currentStep == null ? 43 : $currentStep.hashCode());
        String $summary = this.getSummary();
        result = result * 59 + ($summary == null ? 43 : $summary.hashCode());
        LocalDateTime $createTime = this.getCreateTime();
        result = result * 59 + ($createTime == null ? 43 : ($createTime).hashCode());
        LocalDateTime $updateTime = this.getUpdateTime();
        result = result * 59 + ($updateTime == null ? 43 : ($updateTime).hashCode());
        return result;
    }

    public String toString() {
        return "AgentTask(taskId=" + this.getTaskId() + ", conversationId=" + this.getConversationId() + ", sessionId=" + this.getSessionId() + ", studentId=" + this.getStudentId() + ", projectId=" + this.getProjectId() + ", title=" + this.getTitle() + ", mode=" + this.getMode() + ", status=" + this.getStatus() + ", currentStep=" + this.getCurrentStep() + ", summary=" + this.getSummary() + ", createTime=" + String.valueOf(this.getCreateTime()) + ", updateTime=" + String.valueOf(this.getUpdateTime()) + ")";
    }
}

