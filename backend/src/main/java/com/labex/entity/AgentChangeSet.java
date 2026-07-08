package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value="t_agent_change_set")
public class AgentChangeSet {
    @TableId(value="change_set_id", type=IdType.AUTO)
    private Long changeSetId;
    @TableField(value="task_id")
    private Long taskId;
    @TableField(value="conversation_id")
    private String conversationId;
    @TableField(value="student_id")
    private Integer studentId;
    @TableField(value="project_id")
    private Integer projectId;
    @TableField(value="status")
    private String status;
    @TableField(value="change_count")
    private Integer changeCount;
    @TableField(value="summary")
    private String summary;
    @TableField(value="create_time")
    private LocalDateTime createTime;
    @TableField(value="update_time")
    private LocalDateTime updateTime;

    public Long getChangeSetId() {
        return this.changeSetId;
    }

    public Long getTaskId() {
        return this.taskId;
    }

    public String getConversationId() {
        return this.conversationId;
    }

    public Integer getStudentId() {
        return this.studentId;
    }

    public Integer getProjectId() {
        return this.projectId;
    }

    public String getStatus() {
        return this.status;
    }

    public Integer getChangeCount() {
        return this.changeCount;
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

    public void setChangeSetId(Long changeSetId) {
        this.changeSetId = changeSetId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public void setStudentId(Integer studentId) {
        this.studentId = studentId;
    }

    public void setProjectId(Integer projectId) {
        this.projectId = projectId;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setChangeCount(Integer changeCount) {
        this.changeCount = changeCount;
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
        if (!(o instanceof AgentChangeSet)) {
            return false;
        }
        AgentChangeSet other = (AgentChangeSet)o;
        if (!other.canEqual(this)) {
            return false;
        }
        Long this$changeSetId = this.getChangeSetId();
        Long other$changeSetId = other.getChangeSetId();
        if (this$changeSetId == null ? other$changeSetId != null : !(this$changeSetId).equals(other$changeSetId)) {
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
        Integer this$changeCount = this.getChangeCount();
        Integer other$changeCount = other.getChangeCount();
        if (this$changeCount == null ? other$changeCount != null : !(this$changeCount).equals(other$changeCount)) {
            return false;
        }
        String this$conversationId = this.getConversationId();
        String other$conversationId = other.getConversationId();
        if (this$conversationId == null ? other$conversationId != null : !this$conversationId.equals(other$conversationId)) {
            return false;
        }
        String this$status = this.getStatus();
        String other$status = other.getStatus();
        if (this$status == null ? other$status != null : !this$status.equals(other$status)) {
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
        return other instanceof AgentChangeSet;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        Long $changeSetId = this.getChangeSetId();
        result = result * 59 + ($changeSetId == null ? 43 : ($changeSetId).hashCode());
        Long $taskId = this.getTaskId();
        result = result * 59 + ($taskId == null ? 43 : ($taskId).hashCode());
        Integer $studentId = this.getStudentId();
        result = result * 59 + ($studentId == null ? 43 : ($studentId).hashCode());
        Integer $projectId = this.getProjectId();
        result = result * 59 + ($projectId == null ? 43 : ($projectId).hashCode());
        Integer $changeCount = this.getChangeCount();
        result = result * 59 + ($changeCount == null ? 43 : ($changeCount).hashCode());
        String $conversationId = this.getConversationId();
        result = result * 59 + ($conversationId == null ? 43 : $conversationId.hashCode());
        String $status = this.getStatus();
        result = result * 59 + ($status == null ? 43 : $status.hashCode());
        String $summary = this.getSummary();
        result = result * 59 + ($summary == null ? 43 : $summary.hashCode());
        LocalDateTime $createTime = this.getCreateTime();
        result = result * 59 + ($createTime == null ? 43 : ($createTime).hashCode());
        LocalDateTime $updateTime = this.getUpdateTime();
        result = result * 59 + ($updateTime == null ? 43 : ($updateTime).hashCode());
        return result;
    }

    public String toString() {
        return "AgentChangeSet(changeSetId=" + this.getChangeSetId() + ", taskId=" + this.getTaskId() + ", conversationId=" + this.getConversationId() + ", studentId=" + this.getStudentId() + ", projectId=" + this.getProjectId() + ", status=" + this.getStatus() + ", changeCount=" + this.getChangeCount() + ", summary=" + this.getSummary() + ", createTime=" + String.valueOf(this.getCreateTime()) + ", updateTime=" + String.valueOf(this.getUpdateTime()) + ")";
    }
}

