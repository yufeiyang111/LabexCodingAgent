package com.labex.labexagent.tool;

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

    public static ToolResult approvalRequired(String content, String command) {
        ToolResult result = ToolResult.failed((String)content);
        result.setApprovalRequired(true);
        result.setApprovalCommand(command);
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
        return !(this$approvalCommand == null ? other$approvalCommand != null : !this$approvalCommand.equals(other$approvalCommand));
    }

    protected boolean canEqual(Object other) {
        return other instanceof ToolResult;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        result = result * 59 + (this.isSuccess() ? 79 : 97);
        result = result * 59 + (this.isApprovalRequired() ? 79 : 97);
        String $content = this.getContent();
        result = result * 59 + ($content == null ? 43 : $content.hashCode());
        String $diff = this.getDiff();
        result = result * 59 + ($diff == null ? 43 : $diff.hashCode());
        String $pendingChangeId = this.getPendingChangeId();
        result = result * 59 + ($pendingChangeId == null ? 43 : $pendingChangeId.hashCode());
        String $approvalCommand = this.getApprovalCommand();
        result = result * 59 + ($approvalCommand == null ? 43 : $approvalCommand.hashCode());
        return result;
    }

    public String toString() {
        return "ToolResult(success=" + this.isSuccess() + ", content=" + this.getContent() + ", diff=" + this.getDiff() + ", pendingChangeId=" + this.getPendingChangeId() + ", approvalRequired=" + this.isApprovalRequired() + ", approvalCommand=" + this.getApprovalCommand() + ")";
    }
}

