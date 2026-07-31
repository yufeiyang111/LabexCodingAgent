package com.labex.labexagent.controller;

import com.google.gson.Gson;
import com.labex.common.Result;
import com.labex.labexagent.commandsecurity.AgentApprovedCommandExecutor;
import com.labex.labexagent.commandsecurity.CommandApprovalOrchestrator;
import com.labex.labexagent.commandsecurity.CommandApprovalService;
import com.labex.labexagent.commandsecurity.CommandRedactor;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.entity.AgentRunEvent;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.dto.PromptOptimizationRequest;
import com.labex.labexagent.run.AgentRunEventReplayService;
import com.labex.labexagent.run.AgentRunContinuationRequestFactory;
import com.labex.labexagent.run.AgentSubagentService;
import com.labex.labexagent.run.RunCompletionEvidenceService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.runtime.ContextUsageSnapshot;
import com.labex.labexagent.runtime.AgentSsePublisher;
import com.labex.labexagent.service.AgentCommandService;
import com.labex.labexagent.permission.PermissionApprovalRequest;
import com.labex.labexagent.permission.PermissionService;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.AgentInteractionService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.service.ManualCompactionTaskRunner;
import com.labex.labexagent.service.TokenTracker;
import com.labex.service.StudentProjectService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping(value={"/student/projects/{projectId}/agent"})
public class StudentAgentController {
    private static final Logger log = LoggerFactory.getLogger(StudentAgentController.class);
    private static final Gson GSON = new Gson();

    private final AgentLoopEngine agentLoopEngine;
    private final AgentCancellationRegistry cancellationRegistry;
    private final DiffService diffService;
    private final AgentCommandService commandService;
    private final AgentConversationService conversationService;
    private final AgentTaskService taskService;
    private final TokenTracker tokenTracker;
    private final PermissionService permissionService;
    private final AgentInteractionService interactionService;
    private final AgentRunEventReplayService eventReplayService;
    private final AgentSubagentService subagentService;
    private final CommandApprovalService commandApprovalService;
    private final AgentApprovedCommandExecutor approvedCommandExecutor;
    private final StudentProjectService studentProjectService;
    private final CommandApprovalOrchestrator commandApprovalOrchestrator;

    @Autowired(required = false)
    private ManualCompactionTaskRunner manualCompactionTaskRunner;

    @Autowired(required = false)
    private RunCompletionEvidenceService completionEvidenceService;

    public StudentAgentController(AgentLoopEngine agentLoopEngine, AgentCancellationRegistry cancellationRegistry, DiffService diffService, AgentCommandService commandService, AgentConversationService conversationService, AgentTaskService taskService, TokenTracker tokenTracker, PermissionService permissionService, AgentInteractionService interactionService) {
        this(agentLoopEngine, cancellationRegistry, diffService, commandService, conversationService, taskService,
                tokenTracker, permissionService, interactionService, null, null);
    }

    public StudentAgentController(AgentLoopEngine agentLoopEngine, AgentCancellationRegistry cancellationRegistry, DiffService diffService, AgentCommandService commandService, AgentConversationService conversationService, AgentTaskService taskService, TokenTracker tokenTracker, PermissionService permissionService, AgentInteractionService interactionService, AgentRunEventReplayService eventReplayService) {
        this(agentLoopEngine, cancellationRegistry, diffService, commandService, conversationService, taskService, tokenTracker, permissionService, interactionService, eventReplayService, null);
    }

    public StudentAgentController(AgentLoopEngine agentLoopEngine, AgentCancellationRegistry cancellationRegistry, DiffService diffService, AgentCommandService commandService, AgentConversationService conversationService, AgentTaskService taskService, TokenTracker tokenTracker, PermissionService permissionService, AgentInteractionService interactionService, AgentRunEventReplayService eventReplayService, AgentSubagentService subagentService) {
        this(agentLoopEngine, cancellationRegistry, diffService, commandService, conversationService, taskService,
                tokenTracker, permissionService, interactionService, eventReplayService, subagentService, null, null, null, null);
    }

    public StudentAgentController(AgentLoopEngine agentLoopEngine, AgentCancellationRegistry cancellationRegistry, DiffService diffService, AgentCommandService commandService, AgentConversationService conversationService, AgentTaskService taskService, TokenTracker tokenTracker, PermissionService permissionService, AgentInteractionService interactionService, AgentRunEventReplayService eventReplayService, AgentSubagentService subagentService, CommandApprovalService commandApprovalService, AgentApprovedCommandExecutor approvedCommandExecutor, StudentProjectService studentProjectService) {
        this(agentLoopEngine, cancellationRegistry, diffService, commandService, conversationService, taskService,
                tokenTracker, permissionService, interactionService, eventReplayService, subagentService,
                commandApprovalService, approvedCommandExecutor, studentProjectService, null);
    }

    @Autowired
    public StudentAgentController(AgentLoopEngine agentLoopEngine, AgentCancellationRegistry cancellationRegistry, DiffService diffService, AgentCommandService commandService, AgentConversationService conversationService, AgentTaskService taskService, TokenTracker tokenTracker, PermissionService permissionService, AgentInteractionService interactionService, AgentRunEventReplayService eventReplayService, AgentSubagentService subagentService, CommandApprovalService commandApprovalService, AgentApprovedCommandExecutor approvedCommandExecutor, StudentProjectService studentProjectService, CommandApprovalOrchestrator commandApprovalOrchestrator) {
        this.agentLoopEngine = agentLoopEngine;
        this.cancellationRegistry = cancellationRegistry;
        this.diffService = diffService;
        this.commandService = commandService;
        this.conversationService = conversationService;
        this.taskService = taskService;
        this.tokenTracker = tokenTracker;
        this.permissionService = permissionService;
        this.interactionService = interactionService;
        this.eventReplayService = eventReplayService;
        this.subagentService = subagentService;
        this.commandApprovalService = commandApprovalService;
        this.approvedCommandExecutor = approvedCommandExecutor;
        this.studentProjectService = studentProjectService;
        this.commandApprovalOrchestrator = commandApprovalOrchestrator;
    }

    @GetMapping(value={"/conversations"})
    public Result<List<?>> conversations(@PathVariable Integer projectId, Authentication auth) {
        return Result.success(this.conversationService.list(this.getStudentId(auth), projectId));
    }

    @GetMapping(value={"/conversations/{conversationId}/messages"})
    public Result<AgentConversationService.MessagePage> messages(@PathVariable Integer projectId,
                                                                  @PathVariable String conversationId,
                                                                  @RequestParam(required=false) Long beforeMessageId,
                                                                  @RequestParam(defaultValue="20") int limit,
                                                                  Authentication auth) {
        try {
            return Result.success(this.conversationService.messagePage(this.getStudentId(auth), projectId,
                    conversationId, beforeMessageId, limit));
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
    public Result<Map<String, Object>> compactConversation(@PathVariable Integer projectId, @PathVariable String conversationId,
                                                             @RequestBody(required = false) Map<String, Object> request,
                                                             Authentication auth) {
        try {
            Integer studentId = this.getStudentId(auth);
            Integer modelConfigId = null;
            Object rawModelConfigId = request == null ? null : request.get("modelConfigId");
            if (rawModelConfigId instanceof Number number) {
                modelConfigId = number.intValue();
            } else if (rawModelConfigId instanceof String value && !value.isBlank()) {
                modelConfigId = Integer.valueOf(value);
            }
            if (this.manualCompactionTaskRunner == null) {
                throw new IllegalStateException("Manual compaction runner is unavailable");
            }
            var task = this.manualCompactionTaskRunner.start(studentId, projectId, conversationId, modelConfigId);
            return Result.success(Map.of("taskId", task.getTaskId(), "status", task.getStatus(),
                    "conversationId", conversationId, "sessionId", task.getSessionId()));
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

    @GetMapping(value = {"/tasks/{taskId}/events"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter replayEvents(@PathVariable Integer projectId,
                                   @PathVariable Long taskId,
                                   @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                   @RequestParam(value = "lastEventId", required = false) String requestedLastEventId,
                                   Authentication auth) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            long afterSequence = this.lastEventSequence(lastEventId, requestedLastEventId);
            AgentSsePublisher publisher = new AgentSsePublisher(emitter);
            for (AgentRunEvent event : this.eventReplayService.eventsAfter(
                    this.getStudentId(auth), projectId, taskId, afterSequence)) {
                publisher.send(event.getSequenceNumber(), event.getEventType(), this.eventPayload(event.getPayload()));
            }
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @PostMapping(value={"/prompt/optimize"})
    public Result<Map<String, String>> optimizePrompt(@PathVariable Integer projectId, @RequestBody PromptOptimizationRequest request, Authentication auth) {
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

    @GetMapping(value={"/tasks/{taskId}/completion-evidence"})
    public Result<?> completionEvidence(@PathVariable Integer projectId, @PathVariable Long taskId,
                                        Authentication auth) {
        Integer studentId = this.getStudentId(auth);
        if (this.taskService.getOwnedTask(studentId, projectId, taskId) == null) {
            return Result.error("Task not found");
        }
        if (this.completionEvidenceService == null) {
            return Result.error("Completion evidence service is unavailable");
        }
        var evidence = this.completionEvidenceService.latest(taskId);
        return Result.success(evidence == null ? null : evidence.toPayload());
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
    public Result<Void> interrupt(@PathVariable Integer projectId, @RequestBody Map<String, String> request, Authentication auth) {
        AgentCancellationRegistry.CancellationTarget target = this.cancellationRegistry.findCancellationTarget(
                request == null ? null : request.get("sessionId"),
                this.getStudentId(auth),
                projectId);
        if (target.status() == AgentCancellationRegistry.CancellationStatus.FORBIDDEN) {
            return Result.error("Agent session is not active for this project");
        }
        if (target.activeRun() != null && target.taskId() != null) {
            boolean persisted = this.taskService.requestCancellation(
                    target.taskId(),
                    "Cancellation requested",
                    "User requested cancellation");
            if (persisted) {
                if (this.subagentService != null) this.subagentService.cancelActiveForTask(target.taskId());
                this.cancellationRegistry.signalCancellation(target);
            }
        } else if (target.status() == AgentCancellationRegistry.CancellationStatus.NOT_FOUND && request != null) {
            try {
                Long taskId = Long.valueOf(request.get("taskId"));
                this.taskService.cancelInactiveRun(this.getStudentId(auth), projectId, taskId);
            } catch (NumberFormatException ignored) {
                // An in-memory session can be absent and a task id is optional for this endpoint.
            }
        }
        return Result.success(null);
    }

    @PostMapping(value = {"/tasks/{taskId}/retry-environment"})
    public Result<Map<String, Object>> retryEnvironmentBlockedTask(@PathVariable Integer projectId,
                                                                    @PathVariable Long taskId,
                                                                    Authentication auth) {
        try {
            Integer studentId = this.getStudentId(auth);
            com.labex.entity.AgentTask task = this.taskService.getOwnedTask(studentId, projectId, taskId);
            if (task == null) return Result.error("Agent task not found");
            com.labex.labexagent.run.AgentRunLifecycleService.DispatchClaim claim =
                    this.taskService.claimEnvironmentResume(taskId);
            if (claim == null) {
                return Result.error("Agent task is not waiting for environment recovery or is already being resumed");
            }
            AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task,
                    "The user indicated the dependency environment is restored. "
                            + "Re-run only the previously blocked verification and reassess the workspace before modifying files.");
            try {
                this.agentLoopEngine.resume(studentId, projectId, request, taskId, true, claim.lease());
            } catch (RuntimeException queueFailure) {
                this.taskService.waitForEnvironment(taskId, "Waiting for environment recovery",
                        "Agent queue rejected environment continuation", "ENVIRONMENT_RESUME_QUEUE_REJECTED");
                return Result.error(queueFailure.getMessage());
            }
            return Result.success(Map.of("taskId", taskId, "status", "queued"));
        } catch (Exception exception) {
            return Result.error(exception.getMessage());
        }
    }

    @GetMapping(value={"/conversations/{conversationId}/context-preview"})
    public Result<Map<String, Object>> contextPreview(@PathVariable Integer projectId,
                                                       @PathVariable String conversationId,
                                                       Authentication auth) {
        try {
            if (this.conversationService.getOwnedConversation(this.getStudentId(auth), projectId, conversationId) == null) {
                return Result.error("Conversation not found");
            }
            return Result.success(this.agentLoopEngine.getContextUsage(conversationId)
                    .map(ContextUsageSnapshot::toPreviewPayload)
                    .orElseGet(() -> Map.of(
                            "conversationId", conversationId,
                            "previewSource", "UNAVAILABLE",
                            "previewSections", List.of(),
                            "status", "AWAITING_FIRST_REQUEST")));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping(value={"/conversations/{conversationId}/context-preview"})
    public Result<Map<String, Object>> nextContextPreview(@PathVariable Integer projectId,
                                                            @PathVariable String conversationId,
                                                            @RequestBody(required = false) Map<String, Object> request,
                                                            Authentication auth) {
        try {
            Integer studentId = this.getStudentId(auth);
            if (this.conversationService.getOwnedConversation(studentId, projectId, conversationId) == null) {
                return Result.error("Conversation not found");
            }
            Object rawModelConfigId = request == null ? null : request.get("modelConfigId");
            Integer modelConfigId = null;
            if (rawModelConfigId instanceof Number number) {
                modelConfigId = number.intValue();
            } else if (rawModelConfigId instanceof String value && !value.isBlank()) {
                modelConfigId = Integer.valueOf(value);
            }
            String activePath = requestString(request, "activePath");
            String agentMode = requestString(request, "agentMode");
            String draftMessage = requestString(request, "draftMessage");
            ContextUsageSnapshot preview = this.agentLoopEngine.previewNextRequest(studentId, projectId, conversationId,
                    modelConfigId, activePath, agentMode, draftMessage);
            return Result.success(preview.toPreviewPayload());
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping(value={"/conversations/{conversationId}/context-status"})
    public Result<Map<String, Object>> contextStatus(@PathVariable Integer projectId, @PathVariable String conversationId, Authentication auth) {
        try {
            if (this.conversationService.getOwnedConversation(this.getStudentId(auth), projectId, conversationId) == null) {
                return Result.error("Conversation not found");
            }
            return Result.success(this.agentLoopEngine.getContextUsage(conversationId)
                    .map(ContextUsageSnapshot::toPayload)
                    .orElseGet(() -> {
                        Map<String, Object> empty = new LinkedHashMap<>();
                        empty.put("conversationId", conversationId);
                        empty.put("measurementSource", "ESTIMATED_CHARS");
                        empty.put("categories", Map.of());
                        empty.put("status", "AWAITING_FIRST_REQUEST");
                        return empty;
                    }));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
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
            PermissionService.PermissionApprovalResult result = this.permissionService.reply(
                    projectId,
                    this.getStudentId(auth),
                    requestId,
                    action,
                    feedback);
            return Result.success(Map.of(
                    "granted", result.isGranted(),
                    "remember", result.isRemember(),
                    "feedback", result.getFeedback() == null ? "" : result.getFeedback()
            ));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping(value={"/command-approvals/{approvalId}/decision"})
    public Result<Map<String, Object>> decideCommandApproval(@PathVariable Integer projectId,
                                                               @PathVariable String approvalId,
                                                               @RequestBody CommandApprovalDecisionRequest request,
                                                               Authentication auth) {
        try {
            if (commandApprovalService == null || request == null || request.getAction() == null
                    || request.getDecisionIdempotencyKey() == null || request.getDecisionIdempotencyKey().isBlank()) {
                return commandApprovalUnavailable();
            }
            boolean approve;
            if ("approve".equalsIgnoreCase(request.getAction())) {
                approve = true;
            } else if ("reject".equalsIgnoreCase(request.getAction())) {
                approve = false;
            } else {
                return commandApprovalUnavailable();
            }
            if (commandApprovalOrchestrator != null) {
                CommandApprovalOrchestrator.DecisionResult result = commandApprovalOrchestrator.decide(
                        getStudentId(auth), projectId, approvalId, approve, request.getDecisionIdempotencyKey());
                return result.available() ? Result.success(commandApprovalView(result.approval(), result.resumeAgentLoop())) : commandApprovalUnavailable();
            }
            Integer studentId = getStudentId(auth);
            CommandApproval approval = commandApprovalService.findOwned(studentId, projectId, approvalId);
            if (approval == null || !"agent_shell".equals(approval.getSource())) {
                return commandApprovalUnavailable();
            }
            CommandApproval decided = commandApprovalService.decide(studentId, projectId, approvalId, approve,
                    request.getDecisionIdempotencyKey());
            return Result.success(commandApprovalView(decided,
                    "rejected".equals(decided.getStatus()) || "expired".equals(decided.getStatus())));
        } catch (Exception ignored) {
            return commandApprovalUnavailable();
        }
    }

    @PostMapping(value={"/command-approvals/{approvalId}/execute"})
    public Result<Map<String, Object>> executeCommandApproval(@PathVariable Integer projectId,
                                                               @PathVariable String approvalId,
                                                               Authentication auth) {
        try {
            if (commandApprovalOrchestrator != null) {
                CommandApprovalOrchestrator.ExecutionResult execution = commandApprovalOrchestrator.execute(
                        getStudentId(auth), projectId, approvalId);
                if (!execution.available()) {
                    return commandApprovalUnavailable();
                }
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("approvalId", execution.approval().getApprovalId());
                response.put("status", execution.status());
                response.put("executionStatus", execution.result().succeeded() ? "completed" : "failed");
                response.put("exitCode", execution.result().exitCode() == null ? "" : execution.result().exitCode());
                response.put("durationMs", execution.result().durationMs());
                response.put("output", CommandRedactor.redact(execution.result().output()));
                response.put("resumeAgentLoop", "resuming".equals(execution.status()));
                return Result.success(response);
            }
            if (commandApprovalService == null || approvedCommandExecutor == null || studentProjectService == null) {
                return commandApprovalUnavailable();
            }
            Integer studentId = getStudentId(auth);
            StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
            CommandApproval approval = commandApprovalService.findOwned(studentId, projectId, approvalId);
            if (project == null || approval == null || !"agent_shell".equals(approval.getSource())) {
                return commandApprovalUnavailable();
            }
            if (!consumeCommandApproval(approval)) {
                return commandApprovalUnavailable();
            }
            approval.setStatus("consumed");
            ProcessExecutionResult result = approvedCommandExecutor.execute(approval, project);
            studentProjectService.refreshProjectMetadata(studentId, projectId);
            String status = result.succeeded() ? "completed" : "failed";
            taskService.updateTask(approval.getTaskId(), status,
                    result.succeeded() ? "Approved command completed" : "Approved command failed",
                    "A one-time approved command was executed; start a new agent run to continue.");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("approvalId", approval.getApprovalId());
            response.put("status", status);
            response.put("exitCode", result.exitCode() == null ? "" : result.exitCode());
            response.put("output", CommandRedactor.redact(result.output()));
            response.put("resumeAgentLoop", false);
            return Result.success(response);
        } catch (Exception ignored) {
            return commandApprovalUnavailable();
        }
    }

    private boolean consumeCommandApproval(CommandApproval approval) {
        return commandApprovalService.consume(new CommandApprovalService.ConsumeRequest(
                approval.getApprovalId(), approval.getStudentId(), approval.getProjectId(), approval.getTaskId(),
                approval.getConversationId(), approval.getSessionId(), approval.getSource(), approval.getInvocationId(),
                approval.getToolCallId(), approval.getCommandDigest(), approval.getCanonicalCommand(),
                approval.getWorkingDirectory(), approval.getShell(), approval.getCommandOptions(),
                approval.getClassification(), approval.getPolicyVersion(), approval.getExpiresTime()));
    }

    private Result<Map<String, Object>> commandApprovalUnavailable() {
        return Result.success(Map.of("approvalUnavailable", true, "message", "Command approval is unavailable"));
    }

    private Map<String, Object> commandApprovalView(CommandApproval approval, boolean resumeAgentLoop) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("approvalId", approval.getApprovalId());
        response.put("status", approval.getStatus());
        response.put("expiresTime", approval.getExpiresTime() == null ? "" : approval.getExpiresTime().toString());
        response.put("displayCommand", approval.getDisplayCommand());
        response.put("resumeAgentLoop", resumeAgentLoop);
        return response;
    }

    @PostMapping(value={"/network/approve"})
    public Result<Map<String, Object>> approveNetwork(@PathVariable Integer projectId,
                                                       @RequestBody Map<String, String> request,
                                                       Authentication auth) {
        try {
            String requestId = request == null ? null : request.get("requestId");
            String action = request == null ? null : request.get("action");
            if (requestId == null || requestId.isBlank()) return Result.error("requestId is required");
            boolean approved = "once".equalsIgnoreCase(action) || "allow_once".equalsIgnoreCase(action);
            if (!approved && !"reject".equalsIgnoreCase(action)) return Result.error("Unknown network decision");
            PermissionService.PermissionApprovalResult result = this.permissionService.reply(
                    projectId, this.getStudentId(auth), requestId,
                    approved ? "allow_once" : "reject", "");
            return Result.success(Map.of("approved", result.isGranted(), "scope", "single_command",
                    "feedback", result.getFeedback() == null ? "" : result.getFeedback()));
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
            Integer studentId = this.getStudentId(auth);
            log.info("AGENT_QUESTION_REPLY_RECEIVED projectId={} studentId={} requestId={} action={} answerChars={}",
                    projectId, studentId, requestId, action, answer == null ? 0 : answer.length());
            AgentInteractionService.UserQuestionResult result = this.interactionService.reply(
                    projectId,
                    studentId,
                    requestId,
                    action,
                    answer
            );
            log.info("AGENT_QUESTION_REPLY_RESULT projectId={} studentId={} requestId={} answered={} cancelled={} timedOut={}",
                    projectId, studentId, requestId, result.answered(), result.cancelled(), result.timedOut());
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

    public static class CommandApprovalDecisionRequest {
        private String action;
        private String decisionIdempotencyKey;

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getDecisionIdempotencyKey() { return decisionIdempotencyKey; }
        public void setDecisionIdempotencyKey(String decisionIdempotencyKey) {
            this.decisionIdempotencyKey = decisionIdempotencyKey;
        }
    }

    private String requestString(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Integer getStudentId(Authentication auth) {
        return Integer.parseInt(auth.getName());
    }

    private long lastEventSequence(String lastEventId, String requestedLastEventId) {
        long fromHeader = this.parseSequence(lastEventId);
        return fromHeader >= 0L ? fromHeader : Math.max(0L, this.parseSequence(requestedLastEventId));
    }

    private long parseSequence(String value) {
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private Object eventPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            return GSON.fromJson(payload, Object.class);
        } catch (Exception ignored) {
            return Map.of("message", "Persisted agent event payload could not be decoded");
        }
    }
}
