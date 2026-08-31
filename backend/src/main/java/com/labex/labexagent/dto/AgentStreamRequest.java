package com.labex.labexagent.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.List;

public class AgentStreamRequest {
    private String sessionId;
    private String conversationId;
    private String mode;
    /** 新建对话可显式选择的 Labex 运行时 profile；恢复请求不依赖该字段。 */
    private String runtimeProfile;
    private String message;
    private String displayMessage;
    private String activePath;
    private Integer modelConfigId;
    /** 仅由 multipart 入口在服务器侧填充，不接受公开 JSON 请求直接传入。 */
    @JsonIgnore
    private List<String> attachmentIds = List.of();
    /**
     * 调度器/continuation 专用恢复标识，不能从公共 HTTP 请求体绑定。
     */
    @JsonIgnore
    private Long resumeTaskId;
    /**
     * 持久化运行时边界说明，只能由 scheduler/continuation 填充。
     */
    @JsonIgnore
    private String resumeInteractionId;
    /**
     * 仅限子代理派发链路：本次运行对应的 t_agent_subagent 行 ID；
     * 引擎在子任务创建后据此回填 child_task_id / parent_task_id 并登记终态回调。
     */
    @JsonIgnore
    private Long subagentRowId;
    /**
     * 仅限 scheduler/continuation 使用；不能进入 HTTP 请求协议，也不能进入持久化 transcript
     * （只允许在 Provider 调用边界的只读派生投影中附加）。
     */
    @JsonIgnore
    private String resumeNote;
    private boolean backgroundRun;
    private LocalDateTime submittedAt;

    public String getSessionId() {
        return this.sessionId;
    }

    public String getConversationId() {
        return this.conversationId;
    }

    public String getMode() {
        return this.mode;
    }

    public String getRuntimeProfile() {
        return this.runtimeProfile;
    }

    public String getMessage() {
        return this.message;
    }

    public String getDisplayMessage() {
        return this.displayMessage;
    }

    public String userVisibleMessage() {
        return this.displayMessage == null || this.displayMessage.isBlank()
                ? this.message
                : this.displayMessage;
    }

    public String getActivePath() {
        return this.activePath;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setRuntimeProfile(String runtimeProfile) {
        this.runtimeProfile = runtimeProfile;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setDisplayMessage(String displayMessage) {
        this.displayMessage = displayMessage;
    }

    public void setActivePath(String activePath) {
        this.activePath = activePath;
    }

    public Integer getModelConfigId() {
        return this.modelConfigId;
    }

    @JsonIgnore
    public List<String> getAttachmentIds() {
        return this.attachmentIds == null ? List.of() : List.copyOf(this.attachmentIds);
    }

    @JsonIgnore
    public Long getResumeTaskId() {
        return this.resumeTaskId;
    }

    @JsonIgnore
    public String getResumeInteractionId() {
        return this.resumeInteractionId;
    }

    @JsonIgnore
    public void setResumeInteractionId(String resumeInteractionId) {
        this.resumeInteractionId = resumeInteractionId;
    }

    @JsonIgnore
    public String getResumeNote() {
        return this.resumeNote;
    }

    @JsonIgnore
    public void setResumeNote(String resumeNote) {
        this.resumeNote = resumeNote;
    }

    @JsonIgnore
    public Long getSubagentRowId() {
        return this.subagentRowId;
    }

    @JsonIgnore
    public void setSubagentRowId(Long subagentRowId) {
        this.subagentRowId = subagentRowId;
    }

    public boolean isBackgroundRun() {
        return this.backgroundRun;
    }

    public LocalDateTime getSubmittedAt() {
        return this.submittedAt;
    }

    public void setModelConfigId(Integer modelConfigId) {
        this.modelConfigId = modelConfigId;
    }

    @JsonIgnore
    public void setAttachmentIds(List<String> attachmentIds) {
        this.attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }

    @JsonIgnore
    public void setResumeTaskId(Long resumeTaskId) {
        this.resumeTaskId = resumeTaskId;
    }

    public void setBackgroundRun(boolean backgroundRun) {
        this.backgroundRun = backgroundRun;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof AgentStreamRequest)) {
            return false;
        }
        AgentStreamRequest other = (AgentStreamRequest)o;
        if (!other.canEqual(this)) {
            return false;
        }
        String this$sessionId = this.getSessionId();
        String other$sessionId = other.getSessionId();
        if (this$sessionId == null ? other$sessionId != null : !this$sessionId.equals(other$sessionId)) {
            return false;
        }
        String this$conversationId = this.getConversationId();
        String other$conversationId = other.getConversationId();
        if (this$conversationId == null ? other$conversationId != null : !this$conversationId.equals(other$conversationId)) {
            return false;
        }
        String this$mode = this.getMode();
        String other$mode = other.getMode();
        if (this$mode == null ? other$mode != null : !this$mode.equals(other$mode)) {
            return false;
        }
        String this$runtimeProfile = this.getRuntimeProfile();
        String other$runtimeProfile = other.getRuntimeProfile();
        if (this$runtimeProfile == null ? other$runtimeProfile != null : !this$runtimeProfile.equals(other$runtimeProfile)) {
            return false;
        }
        String this$message = this.getMessage();
        String other$message = other.getMessage();
        if (this$message == null ? other$message != null : !this$message.equals(other$message)) {
            return false;
        }
        String this$activePath = this.getActivePath();
        String other$activePath = other.getActivePath();
        return !(this$activePath == null ? other$activePath != null : !this$activePath.equals(other$activePath));
    }

    protected boolean canEqual(Object other) {
        return other instanceof AgentStreamRequest;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        String $sessionId = this.getSessionId();
        result = result * 59 + ($sessionId == null ? 43 : $sessionId.hashCode());
        String $conversationId = this.getConversationId();
        result = result * 59 + ($conversationId == null ? 43 : $conversationId.hashCode());
        String $mode = this.getMode();
        result = result * 59 + ($mode == null ? 43 : $mode.hashCode());
        String $runtimeProfile = this.getRuntimeProfile();
        result = result * 59 + ($runtimeProfile == null ? 43 : $runtimeProfile.hashCode());
        String $message = this.getMessage();
        result = result * 59 + ($message == null ? 43 : $message.hashCode());
        String $activePath = this.getActivePath();
        result = result * 59 + ($activePath == null ? 43 : $activePath.hashCode());
        return result;
    }

    public String toString() {
        return "AgentStreamRequest(sessionId=" + this.getSessionId() + ", conversationId=" + this.getConversationId() + ", mode=" + this.getMode() + ", runtimeProfile=" + this.getRuntimeProfile() + ", message=" + this.getMessage() + ", activePath=" + this.getActivePath() + ", resumeTaskId=" + this.getResumeTaskId() + ", resumeInteractionId=" + this.getResumeInteractionId() + ")";
    }
}
