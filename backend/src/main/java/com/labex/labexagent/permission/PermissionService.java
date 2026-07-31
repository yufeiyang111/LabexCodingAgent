package com.labex.labexagent.permission;

import com.labex.entity.AgentRunInteraction;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.run.AgentRunResumeScheduler;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AgentRunInteractionService runInteractionService;
    private final AgentRunResumeScheduler resumeScheduler;
    private final Map<String, List<PermissionRule>> approvedRules = new ConcurrentHashMap<>();
    private final Map<String, PermissionApprovalRequest> pendingApprovals = new ConcurrentHashMap<>();

    public PermissionService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null, null);
    }

    public PermissionService(JdbcTemplate jdbcTemplate, AgentRunInteractionService runInteractionService) {
        this(jdbcTemplate, runInteractionService, null);
    }

    @Autowired
    public PermissionService(JdbcTemplate jdbcTemplate, AgentRunInteractionService runInteractionService,
                             @Lazy AgentRunResumeScheduler resumeScheduler) {
        this.jdbcTemplate = jdbcTemplate;
        this.runInteractionService = runInteractionService;
        this.resumeScheduler = resumeScheduler;
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
        pendingApprovals.put(requestId, request);
        return request;
    }

    public PermissionApprovalRequest beginApproval(Integer projectId, Integer studentId, Long taskId,
                                                    String conversationId, String sessionId, String toolName,
                                                    String input, String summary, String matchedRulePermission,
                                                    String matchedRulePattern) {
        return beginApproval(projectId, studentId, taskId, conversationId, sessionId, toolName, input, summary,
                matchedRulePermission, matchedRulePattern, null);
    }

    public PermissionApprovalRequest beginApproval(Integer projectId, Integer studentId, Long taskId,
                                                    String conversationId, String sessionId, String toolName,
                                                    String input, String summary, String matchedRulePermission,
                                                    String matchedRulePattern, String toolCallId) {
        PermissionApprovalRequest request = beginApproval(
                projectId, sessionId, toolName, input, summary, matchedRulePermission, matchedRulePattern);
        if (runInteractionService == null) {
            return request;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", toolName);
            payload.put("input", input);
            payload.put("summary", summary);
            payload.put("matchedRulePermission", matchedRulePermission);
            payload.put("matchedRulePattern", matchedRulePattern);
            if (toolCallId != null && !toolCallId.isBlank()) {
                payload.put("toolCallId", toolCallId);
            }
            runInteractionService.createWaiting(new AgentRunInteractionService.WaitingInteraction(
                    request.getRequestId(), taskId, conversationId, sessionId, studentId, projectId,
                    "permission", payload, "permission-" + request.getRequestId(),
                    LocalDateTime.now().plusMinutes(10)));
            return request;
        } catch (RuntimeException e) {
            pendingApprovals.remove(request.getRequestId());
            throw e;
        }
    }

    public PermissionApprovalResult reply(String requestId, String action, String feedback) {
        return reply(null, null, requestId, action, feedback);
    }

    public PermissionApprovalResult reply(Integer projectId, String requestId, String action, String feedback) {
        return reply(projectId, null, requestId, action, feedback);
    }

    public PermissionApprovalResult reply(Integer projectId, Integer studentId, String requestId, String action, String feedback) {
        PermissionApprovalRequest pending = pendingApprovals.remove(requestId);
        if (pending != null && projectId != null && pending.getProjectId() != null && !projectId.equals(pending.getProjectId())) {
            return new PermissionApprovalResult(false, false, "Permission request does not belong to this project");
        }
        PermissionApprovalResult result = pending == null
                ? handlePersistedAskResult(action, feedback)
                : handleAskResult(pending, action, feedback);
        AgentRunInteraction persisted = null;
        if (runInteractionService != null && studentId != null && projectId != null) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("action", action == null ? "" : action);
                payload.put("granted", result.isGranted());
                payload.put("remember", result.isRemember());
                payload.put("feedback", result.getFeedback());
                persisted = runInteractionService.respond(
                        studentId,
                        projectId,
                        requestId,
                        result.isGranted() ? "approved" : "rejected",
                        payload);
            } catch (IllegalArgumentException e) {
                return new PermissionApprovalResult(false, false, e.getMessage());
            }
        }
        if (persisted == null) {
            return result;
        }
        if (result.isRemember() && pending != null) {
            String pattern = approvalPattern(pending);
            approvedRules.computeIfAbsent(pending.getSessionId(), key -> new ArrayList<>())
                    .add(new PermissionRule(pending.getToolName(), pattern, PermissionAction.ALLOW));
            persistProjectApproval(pending);
        }
        if (resumeScheduler != null) {
            resumeScheduler.resumeIfWaiting(persisted);
        }
        return result;
    }

    private PermissionApprovalResult handlePersistedAskResult(String action, String feedback) {
        if (action == null) {
            return new PermissionApprovalResult(false, false, "Unknown response");
        }
        return switch (action.toLowerCase()) {
            case "allow_once", "once", "allow_always", "always" -> new PermissionApprovalResult(true, false, null);
            case "reject" -> new PermissionApprovalResult(false, false, feedback);
            default -> new PermissionApprovalResult(false, false, "Unknown response");
        };
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
        pendingApprovals.entrySet().removeIf(entry -> sessionId.equals(entry.getValue().getSessionId()));
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


}
