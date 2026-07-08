package com.labex.labexagent.permission;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, List<PermissionRule>> approvedRules = new ConcurrentHashMap<>();
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();

    public PermissionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PermissionEvaluation evaluate(String toolName, String input, List<PermissionRule> rules) {
        return evaluate(toolName, input, rules, null, null);
    }

    public PermissionEvaluation evaluate(String toolName, String input, List<PermissionRule> rules, String sessionId) {
        return evaluate(toolName, input, rules, sessionId, null);
    }

    public PermissionEvaluation evaluate(String toolName, String input, List<PermissionRule> rules, String sessionId, Integer projectId) {
        List<PermissionRule> matched = new ArrayList<>();
        List<PermissionRule> effectiveRules = new ArrayList<>();
        if (rules != null) {
            effectiveRules.addAll(rules);
        }
        if (projectId != null) {
            effectiveRules.addAll(loadProjectApprovals(projectId));
        }
        if (sessionId != null) {
            effectiveRules.addAll(approvedRules.getOrDefault(sessionId, List.of()));
        }

        PermissionRule lastMatched = null;
        for (PermissionRule rule : effectiveRules) {
            if (wildcardMatch(rule.getPermission(), toolName) && wildcardMatch(rule.getPattern(), input)) {
                matched.add(rule);
                lastMatched = rule;
            }
        }

        if (lastMatched != null) {
            return new PermissionEvaluation(lastMatched.getAction(), lastMatched, matched);
        }
        return new PermissionEvaluation(PermissionAction.ASK, null, matched);
    }

    public PermissionApprovalRequest beginApproval(Integer projectId, String sessionId, String toolName, String input, String summary,
                                                   String matchedRulePermission, String matchedRulePattern) {
        String requestId = UUID.randomUUID().toString();
        PermissionApprovalRequest request = new PermissionApprovalRequest(
                projectId,
                requestId,
                sessionId,
                toolName,
                input,
                summary,
                matchedRulePermission,
                matchedRulePattern
        );
        pendingApprovals.put(requestId, new PendingApproval(request));
        return request;
    }

    public PermissionApprovalResult awaitApproval(String requestId) throws InterruptedException, TimeoutException {
        PendingApproval pending = pendingApprovals.get(requestId);
        if (pending == null) {
            return new PermissionApprovalResult(false, false, "Permission request not found");
        }
        try {
            return pending.future.get(10, TimeUnit.MINUTES);
        } catch (java.util.concurrent.ExecutionException e) {
            String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return new PermissionApprovalResult(false, false, message);
        } finally {
            pendingApprovals.remove(requestId);
        }
    }

    public PermissionApprovalResult reply(String requestId, String action, String feedback) {
        return reply(null, requestId, action, feedback);
    }

    public PermissionApprovalResult reply(Integer projectId, String requestId, String action, String feedback) {
        PendingApproval pending = pendingApprovals.get(requestId);
        if (pending == null) {
            return new PermissionApprovalResult(false, false, "Permission request expired or not found");
        }
        if (projectId != null && pending.request.getProjectId() != null && !projectId.equals(pending.request.getProjectId())) {
            return new PermissionApprovalResult(false, false, "Permission request does not belong to this project");
        }
        PermissionApprovalResult result = handleAskResult(pending.request, action, feedback);
        if (result.isRemember()) {
            String pattern = approvalPattern(pending.request);
            approvedRules.computeIfAbsent(pending.request.getSessionId(), key -> new ArrayList<>())
                    .add(new PermissionRule(pending.request.getToolName(), pattern, PermissionAction.ALLOW));
            persistProjectApproval(pending.request);
        }
        pending.future.complete(result);
        return result;
    }

    public PermissionApprovalResult handleAskResult(
            PermissionApprovalRequest request,
            String action,
            String feedback) {

        if (action == null) {
            return new PermissionApprovalResult(false, false, "Unknown response");
        }
        switch (action.toLowerCase()) {
            case "allow_once":
            case "once":
                return new PermissionApprovalResult(true, false, null);
            case "allow_always":
            case "always":
                return new PermissionApprovalResult(true, true, null);
            case "reject":
                return new PermissionApprovalResult(false, false, feedback);
            default:
                return new PermissionApprovalResult(false, false, "Unknown response");
        }
    }

    public boolean isApproved(String toolName, String input) {
        return approvedRules.values().stream()
                .flatMap(Collection::stream)
                .anyMatch(rule -> rule.getAction() == PermissionAction.ALLOW
                        && wildcardMatch(rule.getPermission(), toolName)
                        && wildcardMatch(rule.getPattern(), input));
    }

    private List<PermissionRule> loadProjectApprovals(Integer projectId) {
        try {
            return jdbcTemplate.query(
                    "SELECT permission, pattern FROM t_agent_permission_approval WHERE project_id = ?",
                    (rs, rowNum) -> new PermissionRule(rs.getString("permission"), rs.getString("pattern"), PermissionAction.ALLOW),
                    String.valueOf(projectId)
            );
        } catch (Exception e) {
            log.debug("Load permission approvals failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void persistProjectApproval(PermissionApprovalRequest request) {
        if (request.getProjectId() == null) {
            return;
        }
        String pattern = approvalPattern(request);
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_agent_permission_approval WHERE project_id = ? AND permission = ? AND pattern = ?",
                    Integer.class,
                    String.valueOf(request.getProjectId()),
                    request.getToolName(),
                    pattern
            );
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.update(
                    "INSERT INTO t_agent_permission_approval(project_id, permission, pattern, created_at) VALUES (?, ?, ?, ?)",
                    String.valueOf(request.getProjectId()),
                    request.getToolName(),
                    pattern,
                    System.currentTimeMillis()
            );
        } catch (Exception e) {
            log.warn("Persist permission approval failed: {}", e.getMessage());
        }
    }

    private String approvalPattern(PermissionApprovalRequest request) {
        String pattern = request.getMatchedRulePattern();
        if (pattern != null && !pattern.isBlank() && !"*".equals(pattern)) {
            return pattern;
        }
        return request.getInput();
    }

    public List<PermissionRule> getApprovedRules() {
        return approvedRules.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    public void clearSessionRules(String sessionId) {
        if (sessionId == null) {
            approvedRules.clear();
            pendingApprovals.clear();
            return;
        }
        approvedRules.remove(sessionId);
        pendingApprovals.entrySet().removeIf(entry -> sessionId.equals(entry.getValue().request.getSessionId()));
    }

    private boolean wildcardMatch(String pattern, String input) {
        if (pattern == null || input == null) return false;
        if ("*".equals(pattern)) return true;

        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '*') {
                boolean globstar = i + 1 < pattern.length() && pattern.charAt(i + 1) == '*';
                regex.append(".*");
                if (globstar) i++;
            } else if (ch == '?') {
                regex.append('.');
            } else if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }

        return input.matches(regex.toString());
    }

    public static class PermissionEvaluation {
        private final PermissionAction action;
        private final PermissionRule matchedRule;
        private final List<PermissionRule> allMatched;

        public PermissionEvaluation(PermissionAction action, PermissionRule matchedRule, List<PermissionRule> allMatched) {
            this.action = action;
            this.matchedRule = matchedRule;
            this.allMatched = allMatched;
        }

        public PermissionAction getAction() { return action; }
        public PermissionRule getMatchedRule() { return matchedRule; }
        public List<PermissionRule> getAllMatched() { return allMatched; }
    }

    public static class PermissionApprovalResult {
        private final boolean granted;
        private final boolean remember;
        private final String feedback;

        public PermissionApprovalResult(boolean granted, boolean remember, String feedback) {
            this.granted = granted;
            this.remember = remember;
            this.feedback = feedback;
        }

        public boolean isGranted() { return granted; }
        public boolean isRemember() { return remember; }
        public String getFeedback() { return feedback; }
    }

    private static class PendingApproval {
        private final PermissionApprovalRequest request;
        private final CompletableFuture<PermissionApprovalResult> future = new CompletableFuture<>();

        private PendingApproval(PermissionApprovalRequest request) {
            this.request = request;
        }
    }
}
