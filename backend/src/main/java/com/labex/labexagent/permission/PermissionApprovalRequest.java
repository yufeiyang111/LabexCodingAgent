package com.labex.labexagent.permission;

public class PermissionApprovalRequest {
    private String requestId;
    private Integer projectId;
    private String sessionId;
    private String toolName;
    private String input;
    private String summary;
    private String matchedRulePermission;
    private String matchedRulePattern;
    private long createdAt;

    public PermissionApprovalRequest() {}

    public PermissionApprovalRequest(String requestId, String sessionId, String toolName, String input, String summary, String matchedRulePermission, String matchedRulePattern) {
        this(null, requestId, sessionId, toolName, input, summary, matchedRulePermission, matchedRulePattern);
    }

    public PermissionApprovalRequest(Integer projectId, String requestId, String sessionId, String toolName, String input, String summary, String matchedRulePermission, String matchedRulePattern) {
        this.requestId = requestId;
        this.projectId = projectId;
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.input = input;
        this.summary = summary;
        this.matchedRulePermission = matchedRulePermission;
        this.matchedRulePattern = matchedRulePattern;
        this.createdAt = System.currentTimeMillis();
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getMatchedRulePermission() { return matchedRulePermission; }
    public void setMatchedRulePermission(String matchedRulePermission) { this.matchedRulePermission = matchedRulePermission; }
    public String getMatchedRulePattern() { return matchedRulePattern; }
    public void setMatchedRulePattern(String matchedRulePattern) { this.matchedRulePattern = matchedRulePattern; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
