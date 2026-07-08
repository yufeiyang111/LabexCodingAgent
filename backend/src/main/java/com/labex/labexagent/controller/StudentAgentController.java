package com.labex.labexagent.controller;

import com.labex.common.Result;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentCommandService;
import com.labex.labexagent.permission.PermissionApprovalRequest;
import com.labex.labexagent.permission.PermissionService;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.AgentInteractionService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.service.TokenTracker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping(value={"/student/projects/{projectId}/agent"})
public class StudentAgentController {
    private final AgentLoopEngine agentLoopEngine;
    private final AgentCancellationRegistry cancellationRegistry;
    private final DiffService diffService;
    private final AgentCommandService commandService;
    private final AgentConversationService conversationService;
    private final AgentTaskService taskService;
    private final TokenTracker tokenTracker;
    private final PermissionService permissionService;
    private final AgentInteractionService interactionService;

    public StudentAgentController(AgentLoopEngine agentLoopEngine, AgentCancellationRegistry cancellationRegistry, DiffService diffService, AgentCommandService commandService, AgentConversationService conversationService, AgentTaskService taskService, TokenTracker tokenTracker, PermissionService permissionService, AgentInteractionService interactionService) {
        this.agentLoopEngine = agentLoopEngine;
        this.cancellationRegistry = cancellationRegistry;
        this.diffService = diffService;
        this.commandService = commandService;
        this.conversationService = conversationService;
        this.taskService = taskService;
        this.tokenTracker = tokenTracker;
        this.permissionService = permissionService;
        this.interactionService = interactionService;
    }

    @GetMapping(value={"/conversations"})
    public Result<List<?>> conversations(@PathVariable Integer projectId, Authentication auth) {
        return Result.success(this.conversationService.list(this.getStudentId(auth), projectId));
    }

    @GetMapping(value={"/conversations/{conversationId}/messages"})
    public Result<List<?>> messages(@PathVariable Integer projectId, @PathVariable String conversationId, Authentication auth) {
        try {
            return Result.success(this.conversationService.messages(this.getStudentId(auth), projectId, conversationId));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @DeleteMapping(value={"/conversations/{conversationId}"})
    public Result<Void> deleteConversation(@PathVariable Integer projectId, @PathVariable String conversationId, Authentication auth) {
        this.conversationService.delete(this.getStudentId(auth), projectId, conversationId);
        return Result.success(null);
    }

    @PostMapping(value={"/conversations/{conversationId}/fork"})
    public Result<?> forkConversation(@PathVariable Integer projectId, @PathVariable String conversationId, @RequestBody(required = false) Map<String, Object> request, Authentication auth) {
        try {
            Long messageId = null;
            Object rawMessageId = request == null ? null : request.get("messageId");
            if (rawMessageId instanceof Number number) {
                messageId = number.longValue();
            } else if (rawMessageId instanceof String text && !text.isBlank()) {
                messageId = Long.parseLong(text);
            }
            return Result.success(this.conversationService.forkConversation(this.getStudentId(auth), projectId, conversationId, messageId));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping(value={"/conversations/{conversationId}/compact"})
    public Result<Map<String, Object>> compactConversation(@PathVariable Integer projectId, @PathVariable String conversationId, Authentication auth) {
        try {
            Integer studentId = this.getStudentId(auth);
            String summary = this.conversationService.compactConversation(studentId, projectId, conversationId);
            return Result.success(Map.of(
                    "summary", summary,
                    "stats", this.conversationService.getMemoryStats(studentId, projectId, conversationId)
            ));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping(value={"/conversations/{conversationId}/memory"})
    public Result<?> conversationMemory(@PathVariable Integer projectId, @PathVariable String conversationId, Authentication auth) {
        try {
            return Result.success(this.conversationService.getMemoryStats(this.getStudentId(auth), projectId, conversationId));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping(value={"/stream"}, produces={"text/event-stream"})
    public SseEmitter stream(@PathVariable Integer projectId, @RequestBody AgentStreamRequest request, Authentication auth) {
        return this.agentLoopEngine.start(this.getStudentId(auth), projectId, request);
    }

    @PostMapping(value={"/prompt/optimize"})
    public Result<Map<String, String>> optimizePrompt(@PathVariable Integer projectId, @RequestBody Map<String, String> request, Authentication auth) {
        try {
            return Result.success(this.commandService.optimizePrompt(this.getStudentId(auth), projectId, request));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/commands"})
    public Result<Map<String, Object>> runCommand(@PathVariable Integer projectId, @RequestBody Map<String, String> request, Authentication auth) {
        try {
            return Result.success(this.commandService.runCommand(this.getStudentId(auth), projectId, request));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @GetMapping(value={"/tasks"})
    public Result<List<?>> tasks(@PathVariable Integer projectId, Authentication auth) {
        return Result.success(this.taskService.listTasks(this.getStudentId(auth), projectId));
    }

    @GetMapping(value={"/changes"})
    public Result<List<?>> changes(@PathVariable Integer projectId, Authentication auth) {
        return Result.success(this.taskService.listPendingChanges(this.getStudentId(auth), projectId));
    }

    @GetMapping(value={"/diff/{changeId}"})
    public Result<PendingChange> diff(@PathVariable String changeId, Authentication auth) {
        try {
            return Result.success(this.diffService.get(this.getStudentId(auth), changeId));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/interrupt"})
    public Result<Void> interrupt(@RequestBody Map<String, String> request) {
        this.cancellationRegistry.cancel(request.get("sessionId"));
        return Result.success(null);
    }

    @GetMapping(value={"/tokens/{conversationId}"})
    public Result<Map<String, Object>> tokenStats(@PathVariable Integer projectId, @PathVariable String conversationId, Authentication auth) {
        try {
            return Result.success(this.tokenTracker.getConversationStats(conversationId));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @GetMapping(value={"/tokens/student/summary"})
    public Result<Map<String, Object>> studentTokenSummary(@PathVariable Integer projectId, Authentication auth) {
        try {
            return Result.success(this.tokenTracker.getStudentStats(this.getStudentId(auth)));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/diff/{changeId}/apply"})
    public Result<PendingChange> applyDiff(@PathVariable String changeId, Authentication auth) {
        try {
            return Result.success(this.diffService.apply(this.getStudentId(auth), changeId));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/diff/{changeId}/reject"})
    public Result<Void> rejectDiff(@PathVariable String changeId, Authentication auth) {
        try {
            this.diffService.reject(this.getStudentId(auth), changeId);
            return Result.success(null);
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/diff/{changeId}/undo"})
    public Result<PendingChange> undoDiff(@PathVariable String changeId, Authentication auth) {
        try {
            return Result.success(this.diffService.undo(this.getStudentId(auth), changeId));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/permission/approve"})
    public Result<Map<String, Object>> approvePermission(@PathVariable Integer projectId, @RequestBody Map<String, String> request, Authentication auth) {
        try {
            String requestId = request.get("requestId");
            String action = request.get("action");
            String feedback = request.get("feedback");
            PermissionService.PermissionApprovalResult result = this.permissionService.reply(projectId, requestId, action, feedback);
            return Result.success(Map.of(
                    "granted", result.isGranted(),
                    "remember", result.isRemember(),
                    "feedback", result.getFeedback() == null ? "" : result.getFeedback()
            ));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping(value={"/question/reply"})
    public Result<Map<String, Object>> replyQuestion(@PathVariable Integer projectId, @RequestBody Map<String, String> request, Authentication auth) {
        try {
            String requestId = request.get("requestId");
            String action = request.get("action");
            String answer = request.get("answer");
            AgentInteractionService.UserQuestionResult result = this.interactionService.reply(
                    projectId,
                    this.getStudentId(auth),
                    requestId,
                    action,
                    answer
            );
            LinkedHashMap<String, Object> response = new LinkedHashMap<>();
            response.put("answered", result.answered());
            response.put("cancelled", result.cancelled());
            response.put("timedOut", result.timedOut());
            response.put("answer", result.answer() == null ? "" : result.answer());
            response.put("feedback", result.feedback() == null ? "" : result.feedback());
            return Result.success(response);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private Integer getStudentId(Authentication auth) {
        return Integer.parseInt(auth.getName());
    }
}
