package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value="t_agent_conversation")
public class AgentConversation {
    @TableId(value="conversation_id", type=IdType.INPUT)
    private String conversationId;
    @TableField(value="student_id")
    private Integer studentId;
    @TableField(value="project_id")
    private Integer projectId;
    @TableField(value="title")
    private String title;
    @TableField(value="mode")
    private String mode;
    @TableField(value="provider")
    private String provider;
    @TableField(value="model")
    private String model;
    @TableField(value="summary")
    private String summary;
    @TableField(value="parent_conversation_id")
    private String parentConversationId;
    @TableField(value="forked_from_message_id")
    private Long forkedFromMessageId;
    @TableField(value="compacted_at")
    private LocalDateTime compactedAt;
    @TableField(value="status")
    private Integer status;
    @TableField(value="create_time")
    private LocalDateTime createTime;
    @TableField(value="update_time")
    private LocalDateTime updateTime;

    public String getConversationId() {
        return this.conversationId;
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

    public String getProvider() {
        return this.provider;
    }

    public String getModel() {
        return this.model;
    }

    public String getSummary() {
        return this.summary;
    }

    public String getParentConversationId() {
        return this.parentConversationId;
    }

    public Long getForkedFromMessageId() {
        return this.forkedFromMessageId;
    }

    public LocalDateTime getCompactedAt() {
        return this.compactedAt;
    }

    public Integer getStatus() {
        return this.status;
    }

    public LocalDateTime getCreateTime() {
        return this.createTime;
    }

    public LocalDateTime getUpdateTime() {
        return this.updateTime;
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

    public void setTitle(String title) {
        this.title = title;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setParentConversationId(String parentConversationId) {
        this.parentConversationId = parentConversationId;
    }

    public void setForkedFromMessageId(Long forkedFromMessageId) {
        this.forkedFromMessageId = forkedFromMessageId;
    }

    public void setCompactedAt(LocalDateTime compactedAt) {
        this.compactedAt = compactedAt;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
        if (!(o instanceof AgentConversation)) {
            return false;
        }
        AgentConversation other = (AgentConversation)o;
        if (!other.canEqual(this)) {
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
        Integer this$status = this.getStatus();
        Integer other$status = other.getStatus();
        if (this$status == null ? other$status != null : !(this$status).equals(other$status)) {
            return false;
        }
        String this$conversationId = this.getConversationId();
        String other$conversationId = other.getConversationId();
        if (this$conversationId == null ? other$conversationId != null : !this$conversationId.equals(other$conversationId)) {
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
        String this$provider = this.getProvider();
        String other$provider = other.getProvider();
        if (this$provider == null ? other$provider != null : !this$provider.equals(other$provider)) {
            return false;
        }
        String this$model = this.getModel();
        String other$model = other.getModel();
        if (this$model == null ? other$model != null : !this$model.equals(other$model)) {
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
        return other instanceof AgentConversation;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        Integer $studentId = this.getStudentId();
        result = result * 59 + ($studentId == null ? 43 : ($studentId).hashCode());
        Integer $projectId = this.getProjectId();
        result = result * 59 + ($projectId == null ? 43 : ($projectId).hashCode());
        Integer $status = this.getStatus();
        result = result * 59 + ($status == null ? 43 : ($status).hashCode());
        String $conversationId = this.getConversationId();
        result = result * 59 + ($conversationId == null ? 43 : $conversationId.hashCode());
        String $title = this.getTitle();
        result = result * 59 + ($title == null ? 43 : $title.hashCode());
        String $mode = this.getMode();
        result = result * 59 + ($mode == null ? 43 : $mode.hashCode());
        String $provider = this.getProvider();
        result = result * 59 + ($provider == null ? 43 : $provider.hashCode());
        String $model = this.getModel();
        result = result * 59 + ($model == null ? 43 : $model.hashCode());
        String $summary = this.getSummary();
        result = result * 59 + ($summary == null ? 43 : $summary.hashCode());
        LocalDateTime $createTime = this.getCreateTime();
        result = result * 59 + ($createTime == null ? 43 : ($createTime).hashCode());
        LocalDateTime $updateTime = this.getUpdateTime();
        result = result * 59 + ($updateTime == null ? 43 : ($updateTime).hashCode());
        return result;
    }

    public String toString() {
        return "AgentConversation(conversationId=" + this.getConversationId() + ", studentId=" + this.getStudentId() + ", projectId=" + this.getProjectId() + ", title=" + this.getTitle() + ", mode=" + this.getMode() + ", provider=" + this.getProvider() + ", model=" + this.getModel() + ", summary=" + this.getSummary() + ", status=" + this.getStatus() + ", createTime=" + String.valueOf(this.getCreateTime()) + ", updateTime=" + String.valueOf(this.getUpdateTime()) + ")";
    }
}
