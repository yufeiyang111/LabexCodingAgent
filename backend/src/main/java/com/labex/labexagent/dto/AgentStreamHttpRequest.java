package com.labex.labexagent.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import java.time.LocalDateTime;

/**
 * 公共 /agent/stream 请求体。只承载普通用户输入字段，不承载内部恢复指令；
 * {@code resumeTaskId}、{@code resumeInteractionId}、{@code resumeNote} 只能由
 * scheduler/continuation 在内部 {@link AgentStreamRequest} 上填充。
 * <p>
 * 即使应用级 ObjectMapper 全局忽略未知字段，本 DTO 也会通过 {@link #rejectUnknownField}
 * 拒绝任何未知字段（包括恢复标识），保证公共入口无法注入内部恢复指令。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class AgentStreamHttpRequest {
    private String sessionId;
    private String conversationId;
    private String mode;
    private String runtimeProfile;
    private String message;
    private String displayMessage;
    private String activePath;
    private Integer modelConfigId;
    private boolean backgroundRun;
    private LocalDateTime submittedAt;

    /**
     * 未知字段统一拒绝（包括 {@code resumeTaskId}、{@code resumeInteractionId}、{@code resumeNote}）。
     * 抛出 {@link IllegalArgumentException}，由 Jackson 包装为反序列化失败，最终以 HTTP 4xx 返回。
     */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException(
                "Unknown field '" + name + "' is not accepted by the public agent stream contract");
    }

    public AgentStreamRequest toInternalRequest() {
        AgentStreamRequest internal = new AgentStreamRequest();
        internal.setSessionId(this.sessionId);
        internal.setConversationId(this.conversationId);
        internal.setMode(this.mode);
        internal.setRuntimeProfile(this.runtimeProfile);
        internal.setMessage(this.message);
        internal.setDisplayMessage(this.displayMessage);
        internal.setActivePath(this.activePath);
        internal.setModelConfigId(this.modelConfigId);
        internal.setBackgroundRun(this.backgroundRun);
        internal.setSubmittedAt(this.submittedAt);
        return internal;
    }

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

    public String getActivePath() {
        return this.activePath;
    }

    public Integer getModelConfigId() {
        return this.modelConfigId;
    }

    public boolean isBackgroundRun() {
        return this.backgroundRun;
    }

    public LocalDateTime getSubmittedAt() {
        return this.submittedAt;
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
        this.runtimeProfile = runtimeProfile == null || runtimeProfile.isBlank()
                ? null
                : AgentRuntimeProfile.requireKnown(runtimeProfile).persistedValue();
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

    public void setModelConfigId(Integer modelConfigId) {
        this.modelConfigId = modelConfigId;
    }

    public void setBackgroundRun(boolean backgroundRun) {
        this.backgroundRun = backgroundRun;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }
}
