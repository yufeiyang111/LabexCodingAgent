package com.labex.labexagent.dto;

public class AgentStreamRequest {
    private String sessionId;
    private String conversationId;
    private String mode;
    private String message;
    private String activePath;
    private Integer modelConfigId;

    public String getSessionId() {
        return this.sessionId;
    }

    public String getConversationId() {
        return this.conversationId;
    }

    public String getMode() {
        return this.mode;
    }

    public String getMessage() {
        return this.message;
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

    public void setMessage(String message) {
        this.message = message;
    }

    public void setActivePath(String activePath) {
        this.activePath = activePath;
    }

    public Integer getModelConfigId() {
        return this.modelConfigId;
    }

    public void setModelConfigId(Integer modelConfigId) {
        this.modelConfigId = modelConfigId;
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
        String $message = this.getMessage();
        result = result * 59 + ($message == null ? 43 : $message.hashCode());
        String $activePath = this.getActivePath();
        result = result * 59 + ($activePath == null ? 43 : $activePath.hashCode());
        return result;
    }

    public String toString() {
        return "AgentStreamRequest(sessionId=" + this.getSessionId() + ", conversationId=" + this.getConversationId() + ", mode=" + this.getMode() + ", message=" + this.getMessage() + ", activePath=" + this.getActivePath() + ")";
    }
}

