package com.labex.labexagent.permission;

public class PermissionRule {
    private String permission;
    private String pattern;
    private PermissionAction action;

    public PermissionRule() {}

    public PermissionRule(String permission, String pattern, PermissionAction action) {
        this.permission = permission;
        this.pattern = pattern;
        this.action = action;
    }

    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public PermissionAction getAction() { return action; }
    public void setAction(PermissionAction action) { this.action = action; }
}
