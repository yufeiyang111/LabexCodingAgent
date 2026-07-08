package com.labex.labexagent.permission;

import java.util.List;

public class PermissionDeniedException extends RuntimeException {
    private final String toolName;
    private final String input;
    private final List<PermissionRule> matchedRules;

    public PermissionDeniedException(String toolName, String input, List<PermissionRule> matchedRules) {
        super("Permission denied for tool '" + toolName + "': matched " + matchedRules.size() + " DENY rule(s)");
        this.toolName = toolName;
        this.input = input;
        this.matchedRules = matchedRules;
    }

    public String getToolName() { return toolName; }
    public String getInput() { return input; }
    public List<PermissionRule> getMatchedRules() { return matchedRules; }
}
