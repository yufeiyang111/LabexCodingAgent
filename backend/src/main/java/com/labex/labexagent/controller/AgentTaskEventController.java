package com.labex.labexagent.controller;

import com.google.gson.Gson;
import com.labex.common.Result;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.entity.CommandAuditEvent;
import com.labex.labexagent.commandsecurity.CommandApprovalService;
import com.labex.labexagent.commandsecurity.CommandAuditService;
import com.labex.labexagent.run.AgentTaskEventSubscriptionService;
import com.labex.labexagent.run.AgentToolCallJournalService;
import com.labex.labexagent.run.AgentRunPartService;
import com.labex.labexagent.run.AgentRunMessageService;
import com.labex.labexagent.run.AgentRunSessionSnapshot;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.service.AgentTaskService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Routes for reconnecting a browser observer to a durable Agent task. */
@RestController
@RequestMapping("/student/projects/{projectId}/agent")
public class AgentTaskEventController {
    private static final Logger log = LoggerFactory.getLogger(AgentTaskEventController.class);
    private static final Gson GSON = new Gson();
    private final AgentTaskService taskService;
    private final AgentTaskEventSubscriptionService subscriptionService;
    private final CommandApprovalService commandApprovalService;
    private final CommandAuditService commandAuditService;
    private final AgentToolCallJournalService toolCallJournalService;
    private final AgentRunPartService partService;
    private final AgentRunMessageService runMessageService;
    private final AgentRunInteractionService interactionService;

    /** 兼容旧测试构造器；生产路径由 Spring 注入运行时服务。 */
    AgentTaskEventController(AgentTaskService taskService,
                             AgentTaskEventSubscriptionService subscriptionService,
                             CommandApprovalService commandApprovalService,
                             CommandAuditService commandAuditService) {
        this(taskService, subscriptionService, commandApprovalService, commandAuditService, null, null, null, null);
    }

    public AgentTaskEventController(AgentTaskService taskService,
                                    AgentTaskEventSubscriptionService subscriptionService,
                                    CommandApprovalService commandApprovalService,
                                    CommandAuditService commandAuditService,
                                    AgentToolCallJournalService toolCallJournalService) {
        this(taskService, subscriptionService, commandApprovalService, commandAuditService,
                toolCallJournalService, null, null, null);
    }

    public AgentTaskEventController(AgentTaskService taskService,
                                    AgentTaskEventSubscriptionService subscriptionService,
                                    CommandApprovalService commandApprovalService,
                                    CommandAuditService commandAuditService,
                                    AgentToolCallJournalService toolCallJournalService,
                                    AgentRunPartService partService) {
        this(taskService, subscriptionService, commandApprovalService, commandAuditService,
                toolCallJournalService, partService, null, null);
    }

    public AgentTaskEventController(AgentTaskService taskService,
                                    AgentTaskEventSubscriptionService subscriptionService,
                                    CommandApprovalService commandApprovalService,
                                    CommandAuditService commandAuditService,
                                    AgentToolCallJournalService toolCallJournalService,
                                    AgentRunPartService partService,
                                    AgentRunMessageService runMessageService) {
        this(taskService, subscriptionService, commandApprovalService, commandAuditService,
                toolCallJournalService, partService, runMessageService, null);
    }

    @Autowired
    public AgentTaskEventController(AgentTaskService taskService,
                                    AgentTaskEventSubscriptionService subscriptionService,
                                    CommandApprovalService commandApprovalService,
                                    CommandAuditService commandAuditService,
                                    AgentToolCallJournalService toolCallJournalService,
                                    AgentRunPartService partService,
                                    AgentRunMessageService runMessageService,
                                     AgentRunInteractionService interactionService) {
        this.taskService = taskService;
        this.subscriptionService = subscriptionService;
        this.commandApprovalService = commandApprovalService;
        this.commandAuditService = commandAuditService;
        this.toolCallJournalService = toolCallJournalService;
        this.partService = partService;
        this.runMessageService = runMessageService;
        this.interactionService = interactionService;
    }

    @GetMapping("/conversations/{conversationId}/active-task")
    public Result<Map<String, Object>> activeTask(@PathVariable Integer projectId,
                                                   @PathVariable String conversationId,
                                                   Authentication auth) {
        Integer studentId = Integer.parseInt(auth.getName());
        String diagnosticConversationId = diagnosticConversationId(conversationId);
        log.info("ACTIVE_TASK_LOOKUP_REQUEST studentId={} projectId={} conversationId={}", studentId, projectId, diagnosticConversationId);
        AgentTask task = taskService.findLatestActiveTask(studentId, projectId, conversationId);
        log.info("ACTIVE_TASK_LOOKUP_RESULT studentId={} projectId={} conversationId={} taskId={} status={}",
                studentId, projectId, diagnosticConversationId, task == null ? null : task.getTaskId(), task == null ? "none" : task.getStatus());
        return Result.success(task == null ? null : publicTask(studentId, projectId, task));
    }

    @GetMapping(value = "/tasks/{taskId}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable Integer projectId,
                                @PathVariable Long taskId,
                                @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                @RequestParam(value = "lastEventId", required = false) String requestedLastEventId,
                                Authentication auth) {
        SseEmitter emitter = new SseEmitter(0L);
        long afterSequence = sequence(lastEventId);
        if (afterSequence < 0L) {
            afterSequence = Math.max(0L, sequence(requestedLastEventId));
        }
        Integer studentId = Integer.parseInt(auth.getName());
        log.info("TASK_EVENT_SUBSCRIBE_HTTP studentId={} projectId={} taskId={} afterSequence={} cursorSource={}",
                studentId, projectId, taskId, afterSequence, lastEventId == null || lastEventId.isBlank() ? "query_or_default" : "last_event_id");
        return subscriptionService.subscribe(studentId, projectId, taskId, afterSequence, emitter);
    }

    private Map<String, Object> publicTask(Integer studentId, Integer projectId, AgentTask task) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", task.getTaskId());
        response.put("conversationId", task.getConversationId());
        response.put("sessionId", task.getSessionId());
        response.put("mode", task.getMode());
        response.put("status", task.getStatus());
        response.put("currentStep", task.getCurrentStep());
        response.put("summary", task.getSummary());
        response.put("lastEventSequence", task.getLastEventSequence() == null ? 0L : task.getLastEventSequence());
        response.put("runSession", AgentRunSessionSnapshot.from(task).toPayload());
        response.put("toolCalls", toolCallJournalService == null ? List.of() : toolCallJournalService.latestForTask(task.getTaskId()));
        response.put("parts", partService == null ? List.of() : partService.publicHistory(task.getTaskId()));
        response.put("runMessages", runMessageService == null ? List.of() : runMessageService.publicHistory(task.getTaskId()));
        AgentRunInteraction pendingInteraction = interactionService == null
                ? null : interactionService.findWaitingForTask(task.getTaskId());
        if (pendingInteraction != null) {
            response.put("pendingInteraction", publicInteraction(pendingInteraction));
        }
        CommandApproval approval = commandApprovalService.findLatestForTask(studentId, projectId, task.getTaskId());
        if (approval != null) {
            response.put("commandApproval", publicApproval(approval));
        }
        return response;
    }

    private Map<String, Object> publicInteraction(AgentRunInteraction interaction) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("interactionId", interaction.getInteractionId());
        response.put("requestId", interaction.getInteractionId());
        response.put("taskId", interaction.getTaskId());
        response.put("conversationId", interaction.getConversationId());
        response.put("sessionId", interaction.getSessionId());
        response.put("interactionType", interaction.getInteractionType());
        response.put("status", interaction.getStatus());
        if (interaction.getRequestPayload() != null && !interaction.getRequestPayload().isBlank()) {
            try {
                Object payload = GSON.fromJson(interaction.getRequestPayload(), Object.class);
                if (payload instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> response.put(String.valueOf(key), value));
                }
            } catch (RuntimeException ignored) {
                log.warn("Unable to decode pending interaction payload interactionId={}", interaction.getInteractionId());
            }
        }
        return response;
    }

    private Map<String, Object> publicApproval(CommandApproval approval) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("approvalId", approval.getApprovalId());
        response.put("status", approval.getStatus());
        response.put("displayCommand", approval.getDisplayCommand());
        response.put("classification", approval.getClassification());
        response.put("expiresTime", approval.getExpiresTime());
        CommandAuditEvent execution = commandAuditService.findLatestExecutionOutcome(approval.getApprovalId());
        if (execution != null) {
            response.put("executionStatus", execution.getExecutionStatus());
            response.put("exitCode", execution.getExitCode() == null ? "" : execution.getExitCode());
            response.put("durationMs", execution.getDurationMs() == null ? 0L : execution.getDurationMs());
        }
        return response;
    }

    private String diagnosticConversationId(String conversationId) {
        if (conversationId == null) {
            return "";
        }
        String normalized = conversationId.replace("\r", "_").replace("\n", "_").replace("\t", "_");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    private long sequence(String value) {
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
