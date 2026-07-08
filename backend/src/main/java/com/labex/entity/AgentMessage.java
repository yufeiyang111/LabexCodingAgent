package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value="t_agent_message")
public class AgentMessage {
    @TableId(value="message_id", type=IdType.AUTO)
    private Long messageId;
    @TableField(value="conversation_id")
    private String conversationId;
    @TableField(value="student_id")
    private Integer studentId;
    @TableField(value="project_id")
    private Integer projectId;
    @TableField(value="event_type")
    private String eventType;
    @TableField(value="role")
    private String role;
    @TableField(value="content")
    private String content;
    @TableField(value="event_data")
    private String eventData;
    @TableField(value="create_time")
    private LocalDateTime createTime;

    public Long getMessageId() {
        return this.messageId;
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

    public String getEventType() {
        return this.eventType;
    }

    public String getRole() {
        return this.role;
    }

    public String getContent() {
        return this.content;
    }

    public String getEventData() {
        return this.eventData;
    }

    public LocalDateTime getCreateTime() {
        return this.createTime;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
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

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setEventData(String eventData) {
        this.eventData = eventData;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof AgentMessage)) {
            return false;
        }
        AgentMessage other = (AgentMessage)o;
        if (!other.canEqual(this)) {
            return false;
        }
        Long this$messageId = this.getMessageId();
        Long other$messageId = other.getMessageId();
        if (this$messageId == null ? other$messageId != null : !(this$messageId).equals(other$messageId)) {
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
        String this$eventType = this.getEventType();
        String other$eventType = other.getEventType();
        if (this$eventType == null ? other$eventType != null : !this$eventType.equals(other$eventType)) {
            return false;
        }
        String this$role = this.getRole();
        String other$role = other.getRole();
        if (this$role == null ? other$role != null : !this$role.equals(other$role)) {
            return false;
        }
        String this$content = this.getContent();
        String other$content = other.getContent();
        if (this$content == null ? other$content != null : !this$content.equals(other$content)) {
            return false;
        }
        String this$eventData = this.getEventData();
        String other$eventData = other.getEventData();
        if (this$eventData == null ? other$eventData != null : !this$eventData.equals(other$eventData)) {
            return false;
        }
        LocalDateTime this$createTime = this.getCreateTime();
        LocalDateTime other$createTime = other.getCreateTime();
        return !(this$createTime == null ? other$createTime != null : !(this$createTime).equals(other$createTime));
    }

    protected boolean canEqual(Object other) {
        return other instanceof AgentMessage;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        Long $messageId = this.getMessageId();
        result = result * 59 + ($messageId == null ? 43 : ($messageId).hashCode());
        Integer $studentId = this.getStudentId();
        result = result * 59 + ($studentId == null ? 43 : ($studentId).hashCode());
        Integer $projectId = this.getProjectId();
        result = result * 59 + ($projectId == null ? 43 : ($projectId).hashCode());
        String $conversationId = this.getConversationId();
        result = result * 59 + ($conversationId == null ? 43 : $conversationId.hashCode());
        String $eventType = this.getEventType();
        result = result * 59 + ($eventType == null ? 43 : $eventType.hashCode());
        String $role = this.getRole();
        result = result * 59 + ($role == null ? 43 : $role.hashCode());
        String $content = this.getContent();
        result = result * 59 + ($content == null ? 43 : $content.hashCode());
        String $eventData = this.getEventData();
        result = result * 59 + ($eventData == null ? 43 : $eventData.hashCode());
        LocalDateTime $createTime = this.getCreateTime();
        result = result * 59 + ($createTime == null ? 43 : ($createTime).hashCode());
        return result;
    }

    public String toString() {
        return "AgentMessage(messageId=" + this.getMessageId() + ", conversationId=" + this.getConversationId() + ", studentId=" + this.getStudentId() + ", projectId=" + this.getProjectId() + ", eventType=" + this.getEventType() + ", role=" + this.getRole() + ", content=" + this.getContent() + ", eventData=" + this.getEventData() + ", createTime=" + String.valueOf(this.getCreateTime()) + ")";
    }
}

