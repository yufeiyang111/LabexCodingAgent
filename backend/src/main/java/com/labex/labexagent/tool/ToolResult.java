package com.labex.labexagent.tool;

import com.labex.labexagent.commandsecurity.CommandRedactor;
import com.labex.labexagent.execution.ProcessExecutionResult;

/*
 * Exception performing whole class analysis ignored.
 */
public class ToolResult {
    private boolean success;
    private String content;
    private String diff;
    private String pendingChangeId;
    private boolean approvalRequired;
    private String approvalCommand;
    private String approvalId;
    private String approvalDisplayCommand;
    private String approvalRiskLevel;
    private String approvalReasonCode;
    private String approvalExpiresTime;
    private boolean interactionRequired;
    private String interactionRequestId;
    private String interactionType;

    public ToolResult() {
    }

    public ToolResult(boolean success, String content) {
        this.success = success;
        this.content = content;
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, content);
    }

    public static ToolResult failed(String content) {
        return new ToolResult(false, content);
    }

    public static ToolResult fromProcessExit(int exitCode, String output) {
        String content = "exit=" + exitCode + "\n" + (output == null ? "" : output);
        return exitCode == 0 ? ToolResult.ok(content) : ToolResult.failed(content);
    }

    public static ToolResult fromProcessExecution(ProcessExecutionResult execution) {
        StringBuilder content = new StringBuilder();
        content.append("exit=").append(execution.exitCode() == null ? "none" : execution.exitCode()).append('\n');
        content.append("status=").append(execution.status().name().toLowerCase(java.util.Locale.ROOT)).append('\n');
        content.append("duration_ms=").append(execution.durationMs()).append('\n');
        content.append("truncated=").append(execution.truncated());
        if (!execution.output().isBlank()) {
            content.append('\n').append(execution.output());
        }
        return execution.succeeded() ? ToolResult.ok(content.toString()) : ToolResult.failed(content.toString());
    }

    public static ToolResult approvalRequired(String content, String command) {
        ToolResult result = ToolResult.failed((String)content);
        result.setApprovalRequired(true);
        result.setApprovalCommand(command);
        return result;
    }

    public static ToolResult commandApprovalRequired(String content, String approvalId, String displayCommand,
                                                     String riskLevel, String reasonCode, String expiresTime) {
        ToolResult result = ToolResult.failed(content);
        result.setApprovalRequired(true);
        result.setApprovalId(approvalId);
        result.setApprovalDisplayCommand(displayCommand);
        result.setApprovalRiskLevel(riskLevel);
        result.setApprovalReasonCode(reasonCode);
        result.setApprovalExpiresTime(expiresTime);
        return result;
    }

    public static ToolResult interactionRequired(String content, String requestId, String interactionType) {
        ToolResult result = ToolResult.failed(content);
        result.setInteractionRequired(true);
        result.setInteractionRequestId(requestId);
        result.setInteractionType(interactionType);
        return result;
    }

    public ToolResult withDiff(String diff) {
        this.diff = diff;
        return this;
    }

    public ToolResult withPendingChangeId(String id) {
        this.pendingChangeId = id;
        return this;
    }

    public boolean isSuccess() {
        return this.success;
    }

    public String getContent() {
        return this.content;
    }

    public String getDiff() {
        return this.diff;
    }

    public String getPendingChangeId() {
        return this.pendingChangeId;
    }

    public boolean isApprovalRequired() {
        return this.approvalRequired;
    }

    public String getApprovalCommand() {
        return this.approvalCommand;
    }

    public String getApprovalId() {
        return this.approvalId;
    }

    public String getApprovalDisplayCommand() {
        return this.approvalDisplayCommand;
    }

    public String getApprovalRiskLevel() {
        return this.approvalRiskLevel;
    }

    public String getApprovalReasonCode() {
        return this.approvalReasonCode;
    }

    public String getApprovalExpiresTime() {
        return this.approvalExpiresTime;
    }

    public boolean isInteractionRequired() {
        return this.interactionRequired;
    }

    public String getInteractionRequestId() {
        return this.interactionRequestId;
    }

    public String getInteractionType() {
        return this.interactionType;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setDiff(String diff) {
        this.diff = diff;
    }

    public void setPendingChangeId(String pendingChangeId) {
        this.pendingChangeId = pendingChangeId;
    }

    public void setApprovalRequired(boolean approvalRequired) {
        this.approvalRequired = approvalRequired;
    }

    public void setApprovalCommand(String approvalCommand) {
        this.approvalCommand = approvalCommand;
    }

    public void setApprovalId(String approvalId) {
        this.approvalId = approvalId;
    }

    public void setApprovalDisplayCommand(String approvalDisplayCommand) {
        this.approvalDisplayCommand = approvalDisplayCommand;
    }

    public void setApprovalRiskLevel(String approvalRiskLevel) {
        this.approvalRiskLevel = approvalRiskLevel;
    }

    public void setApprovalReasonCode(String approvalReasonCode) {
        this.approvalReasonCode = approvalReasonCode;
    }

    public void setApprovalExpiresTime(String approvalExpiresTime) {
        this.approvalExpiresTime = approvalExpiresTime;
    }

    public void setInteractionRequired(boolean interactionRequired) {
        this.interactionRequired = interactionRequired;
    }

    public void setInteractionRequestId(String interactionRequestId) {
        this.interactionRequestId = interactionRequestId;
    }

    public void setInteractionType(String interactionType) {
        this.interactionType = interactionType;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof ToolResult)) {
            return false;
        }
        ToolResult other = (ToolResult)o;
        if (!other.canEqual(this)) {
            return false;
        }
        if (this.isSuccess() != other.isSuccess()) {
            return false;
        }
        if (this.isApprovalRequired() != other.isApprovalRequired()) {
            return false;
        }
        if (this.isInteractionRequired() != other.isInteractionRequired()) {
            return false;
        }
        String this$content = this.getContent();
        String other$content = other.getContent();
        if (this$content == null ? other$content != null : !this$content.equals(other$content)) {
            return false;
        }
        String this$diff = this.getDiff();
        String other$diff = other.getDiff();
        if (this$diff == null ? other$diff != null : !this$diff.equals(other$diff)) {
            return false;
        }
        String this$pendingChangeId = this.getPendingChangeId();
        String other$pendingChangeId = other.getPendingChangeId();
        if (this$pendingChangeId == null ? other$pendingChangeId != null : !this$pendingChangeId.equals(other$pendingChangeId)) {
            return false;
        }
        String this$approvalCommand = this.getApprovalCommand();
        String other$approvalCommand = other.getApprovalCommand();
        if (this$approvalCommand == null ? other$approvalCommand != null : !this$approvalCommand.equals(other$approvalCommand)) {
            return false;
        }
        String this$approvalId = this.getApprovalId();
        String other$approvalId = other.getApprovalId();
        if (this$approvalId == null ? other$approvalId != null : !this$approvalId.equals(other$approvalId)) {
            return false;
        }
        String this$approvalDisplayCommand = this.getApprovalDisplayCommand();
        String other$approvalDisplayCommand = other.getApprovalDisplayCommand();
        if (this$approvalDisplayCommand == null ? other$approvalDisplayCommand != null
                : !this$approvalDisplayCommand.equals(other$approvalDisplayCommand)) {
            return false;
        }
        String this$approvalRiskLevel = this.getApprovalRiskLevel();
        String other$approvalRiskLevel = other.getApprovalRiskLevel();
        if (this$approvalRiskLevel == null ? other$approvalRiskLevel != null
                : !this$approvalRiskLevel.equals(other$approvalRiskLevel)) {
            return false;
        }
        String this$approvalReasonCode = this.getApprovalReasonCode();
        String other$approvalReasonCode = other.getApprovalReasonCode();
        if (this$approvalReasonCode == null ? other$approvalReasonCode != null
                : !this$approvalReasonCode.equals(other$approvalReasonCode)) {
            return false;
        }
        String this$approvalExpiresTime = this.getApprovalExpiresTime();
        String other$approvalExpiresTime = other.getApprovalExpiresTime();
        if (this$approvalExpiresTime == null ? other$approvalExpiresTime != null
                : !this$approvalExpiresTime.equals(other$approvalExpiresTime)) {
            return false;
        }
        String this$interactionRequestId = this.getInteractionRequestId();
        String other$interactionRequestId = other.getInteractionRequestId();
        if (this$interactionRequestId == null ? other$interactionRequestId != null : !this$interactionRequestId.equals(other$interactionRequestId)) {
            return false;
        }
        String this$interactionType = this.getInteractionType();
        String other$interactionType = other.getInteractionType();
        return this$interactionType == null ? other$interactionType == null : this$interactionType.equals(other$interactionType);
    }

    protected boolean canEqual(Object other) {
        return other instanceof ToolResult;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        result = result * 59 + (this.isSuccess() ? 79 : 97);
        result = result * 59 + (this.isApprovalRequired() ? 79 : 97);
        result = result * 59 + (this.isInteractionRequired() ? 79 : 97);
        String $content = this.getContent();
        result = result * 59 + ($content == null ? 43 : $content.hashCode());
        String $diff = this.getDiff();
        result = result * 59 + ($diff == null ? 43 : $diff.hashCode());
        String $pendingChangeId = this.getPendingChangeId();
        result = result * 59 + ($pendingChangeId == null ? 43 : $pendingChangeId.hashCode());
        String $approvalCommand = this.getApprovalCommand();
        result = result * 59 + ($approvalCommand == null ? 43 : $approvalCommand.hashCode());
        String $approvalId = this.getApprovalId();
        result = result * 59 + ($approvalId == null ? 43 : $approvalId.hashCode());
        String $approvalDisplayCommand = this.getApprovalDisplayCommand();
        result = result * 59 + ($approvalDisplayCommand == null ? 43 : $approvalDisplayCommand.hashCode());
        String $approvalRiskLevel = this.getApprovalRiskLevel();
        result = result * 59 + ($approvalRiskLevel == null ? 43 : $approvalRiskLevel.hashCode());
        String $approvalReasonCode = this.getApprovalReasonCode();
        result = result * 59 + ($approvalReasonCode == null ? 43 : $approvalReasonCode.hashCode());
        String $approvalExpiresTime = this.getApprovalExpiresTime();
        result = result * 59 + ($approvalExpiresTime == null ? 43 : $approvalExpiresTime.hashCode());
        String $interactionRequestId = this.getInteractionRequestId();
        result = result * 59 + ($interactionRequestId == null ? 43 : $interactionRequestId.hashCode());
        String $interactionType = this.getInteractionType();
        result = result * 59 + ($interactionType == null ? 43 : $interactionType.hashCode());
        return result;
    }

    public String toString() {
        return "ToolResult(success=" + this.isSuccess() + ", content=" + this.getContent()
                + ", diff=" + this.getDiff() + ", pendingChangeId=" + this.getPendingChangeId()
                + ", approvalRequired=" + this.isApprovalRequired()
                + ", approvalCommand=" + (this.getApprovalCommand() == null ? null : "<redacted>")
                + ", approvalId=" + this.getApprovalId()
                + ", approvalDisplayCommand=" + CommandRedactor.redact(this.getApprovalDisplayCommand())
                + ", approvalRiskLevel=" + this.getApprovalRiskLevel()
                + ", approvalReasonCode=" + this.getApprovalReasonCode()
                + ", approvalExpiresTime=" + this.getApprovalExpiresTime()
                + ", interactionRequired=" + this.isInteractionRequired()
                + ", interactionRequestId=" + this.getInteractionRequestId()
                + ", interactionType=" + this.getInteractionType() + ")";
    }
}
