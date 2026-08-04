package com.labex.labexagent.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.context.AgentCompactionRecord;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.context.AgentRequestTokenEstimator;
import com.labex.labexagent.context.CompactionSelection;
import com.labex.labexagent.context.ContextOverflowRecoveryPolicy;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.commandsecurity.CommandApprovalService;
import com.labex.labexagent.commandsecurity.CommandClassification;
import com.labex.labexagent.commandsecurity.CommandClassifier;
import com.labex.labexagent.commandsecurity.CommandDecision;
import com.labex.labexagent.commandsecurity.CommandRedactor;
import com.labex.labexagent.commandsecurity.CommandRequest;
import com.labex.labexagent.commandsecurity.TestCommandResolver;
import com.labex.labexagent.commandsecurity.VerificationStrategy;
import com.labex.labexagent.diff.GitSnapshotService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.prompt.LabexSystemPrompt;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunPlanService;
import com.labex.labexagent.run.AgentRunTransitionKey;
import com.labex.labexagent.run.AgentRunArtifactService;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.run.AgentToolCallJournalService;
import com.labex.labexagent.run.CommandFailureGuard;
import com.labex.labexagent.run.AgentRecoveryProperties;
import com.labex.labexagent.run.AgentTaskEventSubscriptionService;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunLeaseHeartbeatService;
import com.labex.labexagent.run.EnvironmentBlockerClassifier;
import com.labex.labexagent.workspace.WorkspaceLeaseService;
import com.labex.labexagent.workspace.ProjectCheckoutLeaseService;
import com.labex.labexagent.workspace.ProjectCheckoutLeaseHeartbeatService;
import com.labex.labexagent.run.BackgroundRunWorkspaceResolver;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentContextManager;
import com.labex.labexagent.runtime.AgentSsePublisher;
import com.labex.labexagent.runtime.ToolCallExtractor;
import com.labex.labexagent.permission.*;
import com.labex.labexagent.network.NetworkAccessService;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.AgentContextOrchestrator;
import com.labex.labexagent.service.AgentInteractionService;
import com.labex.labexagent.service.AgentMetricsService;
import com.labex.labexagent.service.AgentPostEditHookService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.service.TokenTracker;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolRegistry;
import com.labex.labexagent.tool.ToolSelectionPolicy;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.labexagent.llm.CacheTelemetry;
import com.labex.labexagent.llm.CacheTelemetryStatus;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.labexagent.llm.ProviderEventType;
import com.labex.labexagent.llm.PromptCacheKeyFactory;
import com.labex.rag.config.RagConfig;
import com.labex.rag.llm.MiniMaxChat;
import com.labex.rag.llm.OllamaChat;
import com.labex.service.AgentModelConfigService;
import com.labex.service.AgentMcpServerService;
import com.labex.service.AgentSkillService;
import com.labex.service.StudentProjectService;
import com.labex.entity.AgentModelConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AgentLoopEngine {
    private static final Logger log = LoggerFactory.getLogger(AgentLoopEngine.class);
    private static final Gson GSON = new Gson();
    private static final ThreadPoolExecutor AGENT_EXECUTOR = new ThreadPoolExecutor(
            4,
            16,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            new AgentThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
    private static final long PROVIDER_FIRST_EVENT_TIMEOUT_MS = 45_000L;
    private static final int MAX_TEXT_TOOL_CALL_RECOVERY_FAILURES = 2;
    private static final int MAX_NATIVE_TOOL_INPUT_FAILURE_ROUNDS = 2;
    private final StudentProjectService studentProjectService;
    private final ToolRegistry toolRegistry;
    private AgentToolTurnExecutor toolTurnExecutor;
    private final AgentContextManager contextManager;
    private final AgentCancellationRegistry cancellationRegistry;
    private AgentModelTurnExecutor modelTurnExecutor;
    private AgentToolCallBatchProtocol toolCallBatchProtocol;
    private AgentProviderMessageProjector providerMessageProjector;
    private AgentToolNarrator toolNarrator;
    private ToolSelectionPolicy toolSelectionPolicy;
    private ContextAdmissionService contextAdmissionService;
    private ContextAdmissionGate contextAdmissionGate;
    private final MiniMaxChat miniMaxChat;
    private final OllamaChat ollamaChat;
    private final RagConfig ragConfig;
    private final AgentConversationService conversationService;
    private final AgentTaskService taskService;
    private final LlmProviderFactory providerFactory;
    private final AgentModelConfigService modelConfigService;
    private final TokenTracker tokenTracker;
    private final AgentSkillService skillService;
    private final AgentMcpServerService mcpServerService;
    private final PermissionService permissionService;
    private NetworkAccessService networkAccessService;
    private final GitSnapshotService gitSnapshotService;
    private final DiffService diffService;
    private final AgentContextOrchestrator contextOrchestrator;
    private final AgentPostEditHookService postEditHookService;
    private final AgentMetricsService metricsService;
    private final AgentInteractionService interactionService;
    private AgentInteractionPauser interactionPauser;
    private final AgentRunLifecycleService runLifecycleService;
    private final CommandApprovalService commandApprovalService;
    private final ContextUsageEstimator contextUsageEstimator;
    private final ContextUsageRegistry contextUsageRegistry;
    private final CompactionAgent compactionAgent;
    private CommandClassifier commandClassifier;
    private CommandFailureGuard commandFailureGuard;
    private AgentRecoveryProperties recoveryProperties;
    private AgentLoopProperties loopProperties;

    @Value("${labex-agent.acceptance.auto-approve-verification:false}")
    private boolean acceptanceAutoApproveVerification;
    private AgentCheckpointStore checkpointStore;
    private AgentRunExecutionLeaseService executionLeaseService;
    private AgentRunLeaseHeartbeatService leaseHeartbeatService;
    private WorkspaceLeaseService workspaceLeaseService;
    private ProjectCheckoutLeaseService projectCheckoutLeaseService;
    private ProjectCheckoutLeaseHeartbeatService projectCheckoutLeaseHeartbeatService;
    private AgentTaskEventSubscriptionService taskEventSubscriptionService;
    private AgentRunFinalizer runFinalizer;
    private AgentRunArtifactService artifactService;
    private AgentToolCallJournalService toolCallJournalService;
    private AgentRunTranscriptService transcriptService;
    private AgentRunInteractionService runInteractionService;
    private AgentRunPlanService runPlanService;
    private AgentTranscriptProjectionService transcriptProjectionService;
    private AgentCompactionService compactionService;
    private AgentRequestTokenEstimator requestTokenEstimator;

    @Autowired
    public AgentLoopEngine(StudentProjectService s, ToolRegistry t, AgentContextManager c, AgentCancellationRegistry cr, @Lazy MiniMaxChat mm, @Lazy OllamaChat oc, RagConfig r, AgentConversationService cs, AgentTaskService ts, LlmProviderFactory pf, AgentModelConfigService mcs, TokenTracker tt, AgentSkillService skillService, AgentMcpServerService mcpServerService, PermissionService permissionService, GitSnapshotService gitSnapshotService, DiffService diffService, AgentContextOrchestrator contextOrchestrator, AgentPostEditHookService postEditHookService, AgentMetricsService metricsService, AgentInteractionService interactionService, AgentRunLifecycleService runLifecycleService, CommandApprovalService commandApprovalService, ContextUsageEstimator contextUsageEstimator, ContextUsageRegistry contextUsageRegistry, CompactionAgent compactionAgent) {
        this.studentProjectService = s;
        this.toolRegistry = t;
        this.contextManager = c;
        this.cancellationRegistry = cr;
        this.miniMaxChat = mm;
        this.ollamaChat = oc;
        this.ragConfig = r;
        this.conversationService = cs;
        this.taskService = ts;
        this.providerFactory = pf;
        this.modelConfigService = mcs;
        this.tokenTracker = tt;
        this.skillService = skillService;
        this.mcpServerService = mcpServerService;
        this.permissionService = permissionService;
        this.networkAccessService = null;
        this.gitSnapshotService = gitSnapshotService;
        this.diffService = diffService;
        this.contextOrchestrator = contextOrchestrator;
        this.postEditHookService = postEditHookService;
        this.metricsService = metricsService;
        this.interactionService = interactionService;
        this.runLifecycleService = runLifecycleService;
        this.commandApprovalService = commandApprovalService;
        this.contextUsageEstimator = contextUsageEstimator;
        this.contextUsageRegistry = contextUsageRegistry;
        this.compactionAgent = compactionAgent;
    }

    @Autowired
    void setNetworkAccessService(NetworkAccessService networkAccessService) {
        this.networkAccessService = networkAccessService;
    }

    @Autowired
    void setExecutionLeaseServices(AgentRunExecutionLeaseService executionLeaseService,
                                   AgentRunLeaseHeartbeatService leaseHeartbeatService) {
        this.executionLeaseService = executionLeaseService;
        this.leaseHeartbeatService = leaseHeartbeatService;
    }

    @Autowired
    void setRunProcessors(AgentModelTurnExecutor modelTurnExecutor,
                          AgentToolTurnExecutor toolTurnExecutor,
                          AgentToolCallBatchProtocol toolCallBatchProtocol,
                          AgentProviderMessageProjector providerMessageProjector,
                          AgentToolNarrator toolNarrator,
                          ToolSelectionPolicy toolSelectionPolicy,
                          ContextAdmissionService contextAdmissionService,
                          ContextAdmissionGate contextAdmissionGate,
                          AgentInteractionPauser interactionPauser) {
        this.modelTurnExecutor = requireProcessor(modelTurnExecutor, "modelTurnExecutor");
        this.toolTurnExecutor = requireProcessor(toolTurnExecutor, "toolTurnExecutor");
        this.toolCallBatchProtocol = requireProcessor(toolCallBatchProtocol, "toolCallBatchProtocol");
        this.providerMessageProjector = requireProcessor(providerMessageProjector, "providerMessageProjector");
        this.toolNarrator = requireProcessor(toolNarrator, "toolNarrator");
        this.toolSelectionPolicy = requireProcessor(toolSelectionPolicy, "toolSelectionPolicy");
        this.contextAdmissionService = requireProcessor(contextAdmissionService, "contextAdmissionService");
        this.contextAdmissionGate = requireProcessor(contextAdmissionGate, "contextAdmissionGate");
        this.interactionPauser = requireProcessor(interactionPauser, "interactionPauser");
    }

    private <T> T requireProcessor(T processor, String name) {
        if (processor == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return processor;
    }

    @Autowired
    void setTaskEventSubscriptionService(AgentTaskEventSubscriptionService taskEventSubscriptionService) {
        this.taskEventSubscriptionService = requireRuntimeDependency(taskEventSubscriptionService, "taskEventSubscriptionService");
    }

    @Autowired
    void setRunFinalizer(AgentRunFinalizer runFinalizer) {
        this.runFinalizer = requireRuntimeDependency(runFinalizer, "runFinalizer");
    }

    @Autowired
    void setArtifactService(AgentRunArtifactService artifactService) {
        this.artifactService = requireRuntimeDependency(artifactService, "artifactService");
    }

    @Autowired
    void setToolCallJournalService(AgentToolCallJournalService toolCallJournalService) {
        this.toolCallJournalService = requireRuntimeDependency(toolCallJournalService, "toolCallJournalService");
    }

    @Autowired
    void setTranscriptService(AgentRunTranscriptService transcriptService) {
        this.transcriptService = requireRuntimeDependency(transcriptService, "transcriptService");
    }

    @Autowired
    void setTranscriptProjectionService(AgentTranscriptProjectionService transcriptProjectionService) {
        this.transcriptProjectionService = requireRuntimeDependency(transcriptProjectionService, "transcriptProjectionService");
    }
    @Autowired
    void setRunInteractionService(AgentRunInteractionService runInteractionService) {
        this.runInteractionService = requireRuntimeDependency(runInteractionService, "runInteractionService");
    }

    @Autowired
    void setRunPlanService(AgentRunPlanService runPlanService) {
        this.runPlanService = requireRuntimeDependency(runPlanService, "runPlanService");
    }

    @Autowired
    void setContextCompactionServices(AgentCompactionService compactionService,
                                      AgentRequestTokenEstimator requestTokenEstimator) {
        this.compactionService = requireRuntimeDependency(compactionService, "compactionService");
        this.requestTokenEstimator = requireRuntimeDependency(requestTokenEstimator, "requestTokenEstimator");
    }

    @Autowired
    void setCommandClassifier(CommandClassifier commandClassifier) {
        this.commandClassifier = requireRuntimeDependency(commandClassifier, "commandClassifier");
    }

    @Autowired
    void setCheckpointStore(AgentCheckpointStore checkpointStore) {
        this.checkpointStore = requireRuntimeDependency(checkpointStore, "checkpointStore");
    }

    @Autowired
    void setCommandFailureGuard(CommandFailureGuard commandFailureGuard) {
        this.commandFailureGuard = requireRuntimeDependency(commandFailureGuard, "commandFailureGuard");
    }

    @Autowired
    void setRecoveryProperties(AgentRecoveryProperties recoveryProperties) {
        this.recoveryProperties = requireRuntimeDependency(recoveryProperties, "recoveryProperties");
    }

    @Autowired
    void setLoopProperties(AgentLoopProperties loopProperties) {
        this.loopProperties = requireRuntimeDependency(loopProperties, "loopProperties");
    }

    private <T> T requireRuntimeDependency(T dependency, String name) {
        if (dependency == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return dependency;
    }

    @Autowired
    void setWorkspaceLeaseService(WorkspaceLeaseService workspaceLeaseService) {
        this.workspaceLeaseService = workspaceLeaseService;
    }

    @Autowired
    void setProjectCheckoutLeaseServices(ProjectCheckoutLeaseService projectCheckoutLeaseService,
                                         ProjectCheckoutLeaseHeartbeatService projectCheckoutLeaseHeartbeatService) {
        this.projectCheckoutLeaseService = projectCheckoutLeaseService;
        this.projectCheckoutLeaseHeartbeatService = projectCheckoutLeaseHeartbeatService;
    }

    public SseEmitter start(Integer studentId, Integer projectId, AgentStreamRequest request) {
        return this.enqueue(studentId, projectId, request, false, null);
    }

    public SseEmitter resume(Integer studentId, Integer projectId, AgentStreamRequest request, Long taskId) {
        return this.resume(studentId, projectId, request, taskId, false);
    }

    /**
     * Enqueues a durable continuation. Recovery callers request strict queue handoff so a rejected
     * executor submission can be persisted as a terminal recovery failure instead of being hidden in SSE.
     */
    public SseEmitter resume(Integer studentId, Integer projectId, AgentStreamRequest request, Long taskId,
                             boolean failWhenQueueRejected) {
        return this.resume(studentId, projectId, request, taskId, failWhenQueueRejected, null);
    }

    /** 将数据库事务内领取的执行租约交接给实际运行线程，避免入队后再竞争一次。 */
    public SseEmitter resume(Integer studentId, Integer projectId, AgentStreamRequest request, Long taskId,
                             boolean failWhenQueueRejected,
                             AgentRunExecutionLeaseService.ExecutionLease preclaimedLease) {
        if (request == null || taskId == null) {
            throw new IllegalArgumentException("A continuation request and task ID are required");
        }
        if (this.isEnvironmentRecoveryRequest(request.getMessage())) {
            this.commandFailureGuard.reset(taskId);
        }
        request.setResumeTaskId(taskId);
        return this.enqueue(studentId, projectId, request, failWhenQueueRejected, preclaimedLease);
    }

    private boolean isEnvironmentRecoveryRequest(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("dependency environment is restored")
                || normalized.contains("环境已恢复")
                || normalized.contains("环境恢复后重试");
    }
    private SseEmitter enqueue(Integer studentId, Integer projectId, AgentStreamRequest request,
                               boolean failWhenQueueRejected,
                               AgentRunExecutionLeaseService.ExecutionLease preclaimedLease) {
        SseEmitter emitter = new SseEmitter(Long.valueOf(0L));
        request.setSubmittedAt(LocalDateTime.now());
        String sid = request.getSessionId() != null && !request.getSessionId().isBlank()
                ? request.getSessionId() : UUID.randomUUID().toString();
        request.setSessionId(sid);
        try {
            AGENT_EXECUTOR.execute(() -> this.runLoop(studentId, projectId, request, emitter, preclaimedLease));
        } catch (RuntimeException queueFailure) {
            if (preclaimedLease != null && this.executionLeaseService != null) {
                this.executionLeaseService.release(preclaimedLease);
            }
            if (failWhenQueueRejected) {
                throw new IllegalStateException("Unable to enqueue agent continuation", queueFailure);
            }
            try {
                String queueLanguage = VisibleLanguageResolver.resolve(request.getMessage(), null).code();
                new AgentSsePublisher(emitter).sendTransient("ERROR", Map.of("message", this.localText(queueLanguage,
                        "Agent \u961f\u5217\u7e41\u5fd9\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002", "Agent queue is busy. Please retry shortly.")));
            } catch (Exception ignored) {
                // Ignore a best-effort SSE error notification failure.
            }
            emitter.complete();
        }
        return emitter;
    }

    public java.util.Optional<ContextUsageSnapshot> getContextUsage(String conversationId) {
        return contextUsageRegistry == null ? java.util.Optional.empty() : contextUsageRegistry.find(conversationId);
    }


    /**
     * Builds a no-provider, no-persistence estimate for the next request in an existing conversation.
     * A drafted message and active file make the adaptive project context more representative; neither is saved.
     */
    public ContextUsageSnapshot previewNextRequest(Integer studentId, Integer projectId, String conversationId,
                                                   Integer requestedModelConfigId, String activePath,
                                                   String requestedMode, String draftedUserMessage) {
        if (contextUsageEstimator == null) {
            throw new IllegalStateException("Context preview is unavailable");
        }
        StudentProject project = this.studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        AgentConversation conversation = this.conversationService.getOwnedConversation(studentId, projectId, conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        AgentModelConfig modelConfig = this.modelConfigService.resolveForStudent(studentId, requestedModelConfigId);
        if (modelConfig == null || (requestedModelConfigId != null && requestedModelConfigId > 0
                && (!requestedModelConfigId.equals(modelConfig.getConfigId())
                || !Integer.valueOf(1).equals(modelConfig.getStatus())))) {
            throw new IllegalArgumentException("Preview model config not found or disabled");
        }

        String mode = AgentMode.normalize(requestedMode);
        String draft = draftedUserMessage == null ? "" : draftedUserMessage.trim();
        boolean draftedMessageIncluded = !draft.isBlank();
        String memoryContext = this.conversationService.buildMemoryContext(studentId, projectId, conversationId);
        String visibleLanguage = this.visibleLanguage(draft, memoryContext);
        AgentContext previewContext = AgentContext.create("context-preview-" + conversationId,
                studentId, project, conversationId, null);
        previewContext.setModelConfigId(modelConfig.getConfigId());
        previewContext.setMode(mode);
        previewContext.setStage("intake");

        List<ToolDefinition> selectedToolDefinitions = this.selectToolDefinitions(studentId, mode, modelConfig);
        previewContext.setSelectedToolNames(this.toolSelectionPolicy.selectedNames(selectedToolDefinitions));
        String toolDefinitions = this.buildToolDefinitions(selectedToolDefinitions);
        String systemPrompt = LabexSystemPrompt.buildSystemPrompt(project, toolDefinitions, visibleLanguage);
        List<Map<String, Object>> tools = new ArrayList<>(this.buildToolsList(selectedToolDefinitions));
        String activeFileContent = this.readActiveFile(studentId, projectId, activePath);
        String projectRules = this.readProjectRules(studentId, projectId);
        String projectIndex = this.readProjectIndex(studentId, projectId);
        AgentContextOrchestrator.ContextBundle contextBundle = this.contextOrchestrator.buildInitialBundle(
                project, activePath, activeFileContent, toolDefinitions, draft, projectIndex, false, previewContext);
        String recentRunLog = "";
        String checkpoint = "";
        String globalSkills = this.skillService.buildPromptContext(studentId);
        String mcpContext = this.mcpServerService.buildPromptContext(studentId);
        String modePolicy = this.buildModePolicy(mode);
        String languagePolicy = this.buildVisibleLanguagePolicy(visibleLanguage);
        String initialContextMessage = modePolicy + "\n\n" + languagePolicy + "\n\n"
                + this.buildContextMessage(projectRules, memoryContext, contextBundle.content(), recentRunLog,
                checkpoint, globalSkills, mcpContext);
        ContextUsageEstimator.PromptContext promptContext = ContextUsageEstimator.PromptContext.of(
                projectRules, memoryContext, contextBundle.content(), recentRunLog, checkpoint, globalSkills,
                mcpContext, modePolicy, languagePolicy, initialContextMessage);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", initialContextMessage));
        if (draftedMessageIncluded) {
            messages.add(Map.of("role", "user", "content", draft));
        }
        Map<String, Object> previewMetadata = new LinkedHashMap<>();
        previewMetadata.put("estimateBasis", "CURRENT_SESSION_STATE");
        previewMetadata.put("nextUserMessageIncluded", draftedMessageIncluded);
        previewMetadata.put("activePath", activePath == null ? "" : activePath);
        previewMetadata.put("agentMode", mode);
        previewMetadata.put("modelConfigId", modelConfig.getConfigId());
        previewMetadata.put("adaptiveContextDependsOnDraft", true);
        ContextUsageSnapshot snapshot = this.contextUsageEstimator.estimateNextRequest(conversationId,
                "context-preview-" + conversationId, modelConfig.getProvider(), modelConfig.getModelName(),
                modelConfig.getContextWindowTokens(), systemPrompt, tools, promptContext, messages, previewMetadata);
        ContextAdmissionDecision admission = this.evaluateContextAdmission(
                modelConfig, systemPrompt, tools, promptContext, messages);
        if (admission != null) snapshot.withBudgetBreakdown(admission.breakdown());
        return snapshot;
    }

    private static class AgentThreadFactory implements ThreadFactory {
        private int index = 1;

        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "labex-agent-" + index++);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static class ToolExecutionThreadFactory implements ThreadFactory {
        private int index = 1;

        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "labex-tool-execution-" + index++);
            thread.setDaemon(true);
            return thread;
        }
    }

    /** Provider 消息只写入 durable transcript；下一次读取统一经过 projector。 */
    private void appendProviderMessage(Long taskId, long executionEpoch, Map<String, Object> message) {
        Map<String, Object> durableCopy = this.providerMessageProjector.copyMessage(message);
        AgentRunTranscriptService transcriptService = this.requireTranscriptService();
        transcriptService.appendMessage(taskId, executionEpoch,
                transcriptService.nextSequence(taskId), durableCopy);
    }

    private void appendProviderMessages(Long taskId, long executionEpoch,
                                        Collection<? extends Map<String, Object>> messages) {
        if (messages == null) {
            return;
        }
        for (Map<String, Object> message : messages) {
            this.appendProviderMessage(taskId, executionEpoch, message);
        }
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private void runLoop(Integer studentId, Integer projectId, AgentStreamRequest request, SseEmitter emitter,
                         AgentRunExecutionLeaseService.ExecutionLease preclaimedLease) {
        AgentSsePublisher sse = new AgentSsePublisher(emitter,
                this.taskEventSubscriptionService::publishTransient);
        AgentConversation conv = null;
        AgentTask task = null;
        AgentContext ctx = null;
        StudentProject project = null;
        Path runLog = null;
        LlmProvider llmProvider = null;
        LlmProvider.LlmConfig llmConfig = null;
        String visibleLanguage = "en";
        AgentCancellationRegistry.ActiveRun activeCancellation = null;
        AgentRunExecutionLeaseService.ExecutionLease executionLease = null;
        ProjectCheckoutLeaseService.CheckoutLease checkoutLease = null;
        CancellationToken cancellationToken = CancellationToken.none();
        try {
            AgentModelConfig modelConfig = this.modelConfigService.resolveForStudent(studentId, request.getModelConfigId());
            if (modelConfig == null) {
                throw new IllegalStateException("No user model configuration is selected. Create and select a model configuration before starting the Agent.");
            }
            if (!this.modelConfigService.hasStoredApiKey(modelConfig)) {
                throw new IllegalStateException("Selected model configuration has no API key.");
            }
            ContextWindowPolicy contextWindowPolicy = ContextWindowPolicy.from(modelConfig).orElse(null);
            llmProvider = this.providerFactory.resolveProvider(modelConfig);
            llmConfig = this.providerFactory.buildConfig(modelConfig);
            project = this.studentProjectService.getOwnedProject(studentId, projectId);
            if (project == null) {
                throw new IllegalArgumentException("Project not found");
            }
            runLog = this.createRunLog(project, request);
            String userVisibleMessage = request.userVisibleMessage();
            this.appendRunLog(runLog, "# LabexAgent run log\n\n- Session: `" + this.safeLogText(request.getSessionId()) + "`\n- Project: `" + this.safeLogText(project.getProjectName()) + "`\n- Student: `" + studentId + "`\n- Start time: `" + String.valueOf(LocalDateTime.now()) + "`\n\n## User input\n\n" + this.safeLogText(userVisibleMessage) + "\n");
            boolean resumedRun = request.getResumeTaskId() != null;
            String mode;
            String memoryContext;
            if (resumedRun) {
                task = this.taskService.getOwnedTask(studentId, projectId, request.getResumeTaskId());
                if (task == null) {
                    throw new IllegalArgumentException("Suspended agent task not found");
                }
                conv = this.conversationService.getOwnedConversation(studentId, projectId, task.getConversationId());
                if (conv == null) {
                    throw new IllegalArgumentException("Suspended agent conversation not found");
                }
                mode = AgentMode.normalize(task.getMode());
                request.setConversationId(conv.getConversationId());
                request.setSessionId(task.getSessionId());
                memoryContext = this.conversationService.buildMemoryContext(studentId, projectId, conv.getConversationId());
                visibleLanguage = this.visibleLanguage(userVisibleMessage, memoryContext);
            } else {
                mode = AgentMode.normalize(request.getMode());
                conv = this.conversationService.ensureConversation(studentId, project, request.getConversationId(), mode,
                        userVisibleMessage, modelConfig);
                request.setConversationId(conv.getConversationId());
                memoryContext = this.conversationService.buildMemoryContext(studentId, projectId, conv.getConversationId());
                visibleLanguage = this.visibleLanguage(userVisibleMessage, memoryContext);
                task = this.taskService.createTask(studentId, project, conv.getConversationId(), request.getSessionId(), mode,
                        request.getMessage(), userVisibleMessage, request.getActivePath(), modelConfig.getConfigId(),
                        request.isBackgroundRun(), request.getSubmittedAt());
                this.conversationService.touchActivity(conv);
            }
            if (this.executionLeaseService != null) {
                if (preclaimedLease != null) {
                    if (!task.getTaskId().equals(preclaimedLease.taskId())
                            || !this.executionLeaseService.renew(preclaimedLease)) {
                        throw new IllegalStateException("Preclaimed agent dispatch lease is no longer valid");
                    }
                    executionLease = preclaimedLease;
                } else {
                    executionLease = this.executionLeaseService.acquire(task.getTaskId());
                    if (executionLease == null) {
                        throw new IllegalStateException("Agent run is already owned by another active worker");
                    }
                }
            } else if (preclaimedLease != null) {
                throw new IllegalStateException("Preclaimed agent dispatch requires execution lease service");
            }
            sse.bindRun(this.runLifecycleService, task.getTaskId());
            // 取得执行租约后立即注册取消令牌，不能先暴露 preparing/SESSION 再留下不可取消窗口。
            activeCancellation = this.cancellationRegistry.register(
                    request.getSessionId(), studentId, projectId, task.getTaskId());
            cancellationToken = activeCancellation;
            if (this.projectCheckoutLeaseService != null) {
                Path checkoutWorkspace = this.checkoutWorkspace(project, task);
                ProjectCheckoutLeaseService.AcquireResult admission = this.projectCheckoutLeaseService.acquire(
                        task.getTaskId(), project.getProjectId(), checkoutWorkspace);
                if (!admission.acquired()) {
                    this.waitForProjectCheckout(sse, conv, task, project, request, runLog, checkoutWorkspace, admission,
                            visibleLanguage, emitter);
                    return;
                }
                checkoutLease = admission.lease();
            }
            this.taskService.startTiming(task.getTaskId());
            this.sendEvent(sse, conv, "SESSION", Map.of(
                    "sessionId", request.getSessionId(),
                    "conversationId", conv.getConversationId(),
                    "taskId", task.getTaskId(),
                    "iterationLimit", "none",
                    "logPath", this.workspaceRelativeLogPath(project, runLog)));
            this.sendThought(sse, conv, 0,
                    resumedRun
                            ? this.localText(visibleLanguage, "恢复任务", "Resuming task")
                            : this.localText(visibleLanguage, "准备工作区", "Preparing workspace"),
                    this.localText(visibleLanguage,
                            resumedRun ? "正在从已保存的对话和用户回复恢复任务。" : "正在准备项目上下文和可用工具。",
                            resumedRun ? "Restoring the task from its saved conversation and user response." : "Preparing project context and available tools."),
                    task.getTaskId());
            if (resumedRun && "recovering".equalsIgnoreCase(task.getStatus())) {
                this.taskService.updateTask(
                        task.getTaskId(),
                        "running",
                        "Recovered execution",
                        "Execution resumed after lease takeover",
                        AgentRunTransitionKey.forResumedRunUpdate(
                                task.getTaskId(), request.getSubmittedAt(), "running",
                                "Recovered execution", "Execution resumed after lease takeover"));
            }
            if (!resumedRun || (resumedRun && "queued".equalsIgnoreCase(task.getStatus()))) {
                this.taskService.updateTask(
                        task.getTaskId(),
                        "preparing",
                        this.localText(visibleLanguage, "准备工作区", "Preparing workspace"),
                        this.localText(visibleLanguage, "运行已创建，正在准备上下文。", "Run created and preparing context."));
            }
            if (executionLease != null && this.leaseHeartbeatService != null) {
                this.leaseHeartbeatService.track(executionLease, request.getSessionId());
            }
            if (checkoutLease != null && this.projectCheckoutLeaseHeartbeatService != null) {
                this.projectCheckoutLeaseHeartbeatService.track(checkoutLease, request.getSessionId());
            }
            java.util.Optional<AgentCheckpointStore.Snapshot> resumedCheckpoint = java.util.Optional.empty();
            ctx = AgentContext.create(request.getSessionId(), studentId, project, conv.getConversationId(), task.getTaskId());
            this.applyBackgroundWorkspace(ctx, project, task);
            ctx.setCancellationToken(cancellationToken);
            ctx.setModelConfigId(modelConfig.getConfigId());
            ctx.setMode(mode);
            ctx.setEnvironmentRecovery(this.isEnvironmentRecoveryRequest(request.getMessage()));
            long activeExecutionEpoch = executionLease == null
                    ? (task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch())
                    : executionLease.epoch();
            task.setExecutionEpoch(activeExecutionEpoch);
            ctx.setExecutionEpoch(activeExecutionEpoch);
            if (resumedRun) {
                resumedCheckpoint = this.checkpointStore.load(project, conv.getConversationId(), task.getTaskId());
                if (resumedCheckpoint.isPresent()) {
                    resumedCheckpoint.get().restoreInto(ctx);
                }
            } else {
                ctx.setStage("intake");
            }
            List<AgentRunPlanService.PlanDraft> legacyPlan = resumedCheckpoint
                    .flatMap(AgentCheckpointStore.Snapshot::legacyPlanSeed)
                    .map(seed -> seed.items().stream()
                            .map(item -> new AgentRunPlanService.PlanDraft(
                                    item.getTitle(), item.getDescription(), item.isCompleted()))
                            .toList())
                    .orElse(List.of());
            AgentRunPlanService.Projection restoredPlan = this.requireRunPlanService()
                    .restoreOrMigrate(task.getTaskId(), activeExecutionEpoch, legacyPlan);
            restoredPlan.applyTo(ctx);
            if (restoredPlan.eventSequence() > 0L) {
                this.projectPersistedPlanUpdate(sse, ctx);
            }
            this.appendRunLog(runLog, "\n## Runtime metadata\n\n- Conversation: `" + conv.getConversationId() + "`\n- Task: `" + task.getTaskId() + "`\n- Mode: `" + mode + "`\n- Iteration policy: `" + this.iterationPolicyDescription() + "`\n");
            long contextBuildStartedAt = System.nanoTime();
            List<ToolDefinition> selectedToolDefinitions = this.selectToolDefinitions(studentId, mode, modelConfig);
            ctx.setSelectedToolNames(this.toolSelectionPolicy.selectedNames(selectedToolDefinitions));
            String toolDefinitions = this.buildToolDefinitions(selectedToolDefinitions);
            String sysPrompt = LabexSystemPrompt.buildSystemPrompt((StudentProject)project, (String)toolDefinitions, visibleLanguage);
            List<Map<String, Object>> tools = new ArrayList<>(this.buildToolsList(selectedToolDefinitions));
            llmConfig = llmConfig.withPromptCacheKey(PromptCacheKeyFactory.forStablePrefix(
                    studentId, modelConfig.getConfigId(), llmConfig.baseUrl(), llmConfig.modelName(),
                    sysPrompt, GSON.toJson(tools)));
            long transcriptEpoch = task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch();
            String activeFileContent = this.readActiveFile(studentId, projectId, request.getActivePath());
            String projectRules = this.readProjectRules(studentId, projectId);
            String projectIndex = this.readProjectIndex(studentId, projectId);
            AgentContextOrchestrator.ContextBundle contextBundle = this.contextOrchestrator.buildInitialBundle(project, request.getActivePath(), activeFileContent, toolDefinitions, request.getMessage(), projectIndex, false, ctx);
            String sessionContext = contextBundle.content();
            String recentRunLog = "";
            String checkpoint = resumedCheckpoint.map(this.checkpointStore::renderForPrompt).orElse("");
            String globalSkills = this.skillService.buildPromptContext(studentId);
            String mcpContext = this.mcpServerService.buildPromptContext(studentId);
            String modePolicy = this.buildModePolicy(mode);
            String languagePolicy = this.buildVisibleLanguagePolicy(visibleLanguage);
            String initialContextMessage = modePolicy + "\n\n" + languagePolicy + "\n\n" + this.buildContextMessage(projectRules, memoryContext, sessionContext, recentRunLog, checkpoint, globalSkills, mcpContext);
            ContextUsageEstimator.PromptContext contextPrompt = ContextUsageEstimator.PromptContext.of(
                    projectRules, memoryContext, sessionContext, recentRunLog, checkpoint, globalSkills,
                    mcpContext, modePolicy, languagePolicy, initialContextMessage);
            List<Map<String, Object>> persistedMessages;
            try {
                AgentTranscriptProjectionService durableProjector = this.requireTranscriptProjectionService();
                persistedMessages = resumedRun && request.getResumeInteractionId() != null
                        ? durableProjector.loadDurableProjectionForInteractionResume(task.getTaskId()).messages()
                        : durableProjector.loadDurableProjection(task.getTaskId()).messages();
            } catch (RuntimeException transcriptFailure) {
                throw new IllegalStateException(
                        "Unable to restore durable Provider transcript for taskId=" + task.getTaskId(),
                        transcriptFailure);
            }
            boolean transcriptRestored = !persistedMessages.isEmpty();
            if (!transcriptRestored) {
                this.appendProviderMessage(task.getTaskId(), transcriptEpoch,
                        Map.of("role", "user", "content", initialContextMessage));
                this.appendProviderMessage(task.getTaskId(), transcriptEpoch,
                        Map.of("role", "user", "content", request.getMessage()));
            } else if (resumedRun) {
                if (request.getResumeInteractionId() != null) {
                    AgentRunInteraction interaction = this.runInteractionService.findById(request.getResumeInteractionId());
                    List<Map<String, Object>> toolResults = this.requireTranscriptService()
                            .resolvedInteractionToolResults(interaction, persistedMessages);
                    this.appendProviderMessages(task.getTaskId(), transcriptEpoch, toolResults);
                }
                if (request.getMessage() != null && !request.getMessage().isBlank()) {
                    // 持久化运行时边界说明。
                    this.appendProviderMessage(task.getTaskId(), transcriptEpoch,
                            Map.of("role", "user", "content", request.getMessage()));
                }
            }
            log.info("AGENT_CONTEXT_READY taskId={} buildMs={} systemPromptChars={} contextChars={} userChars={} toolCount={} toolSchemaChars={} estimatedContextTokens={}",
                    task.getTaskId(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - contextBuildStartedAt),
                    sysPrompt.length(), initialContextMessage.length(), request.getMessage() == null ? 0 : request.getMessage().length(),
                    tools.size(), GSON.toJson(tools).length(), contextBundle.stats().get("estimatedTokens"));
            this.sendEvent(sse, conv, "CONTEXT_STATS", contextBundle.stats());
            this.appendRunLog(runLog, "\n## Context orchestration\n\n```json\n" + GSON.toJson(contextBundle.stats()) + "\n```\n");
            this.writeAgentCheckpoint(project, request, task, ctx, "running", "Session started, preparing first model call.", "", "", runLog);
            int i = 1;
            AgentLoopGuard loopGuard = new AgentLoopGuard(loopProperties);
            ContextOverflowRecoveryPolicy overflowRecoveryPolicy = new ContextOverflowRecoveryPolicy();
            int textToolCallRecoveryFailures = 0;
            int nativeToolInputFailureRounds = 0;
            while (true) {
                block19: {
                    boolean executed;
                    String type;
                    Map lr;
                    block21: {
                        String ft;
                        block24: {
                            String cleaned;
                            String content;
                            block23: {
                                block22: {
                                    block20: {
                                        this.appendRunLog(runLog, "\n## Iteration " + i + "\n");
                                        if (cancellationToken.isCancellationRequested()) {
                                            this.completeCancelledRun(sse, conv, task, project, runLog, i, visibleLanguage, emitter);
                                            return;
                                        }
                                        AgentLoopGuard.IterationDecision iterationDecision = loopGuard.beforeIteration(i);
                                        if (iterationDecision.action() == AgentLoopGuard.IterationAction.STOP) {
                                            boolean noProgressStop = "non_progress".equals(iterationDecision.reason());
                                            String stopReason = noProgressStop
                                                    ? this.localText(visibleLanguage,
                                                            "连续 " + iterationDecision.nonProgressIterations() + " 次没有取得进展，已触发循环保护。",
                                                            "No confirmed progress was produced for " + iterationDecision.nonProgressIterations() + " consecutive model turns. Further requests were stopped; add guidance, switch strategy, or resume from the current progress.")
                                                    : this.localText(visibleLanguage,
                                                            "\u5df2\u8fbe\u5230\u914d\u7f6e\u7684\u6700\u7ec8\u4fdd\u9669\u4e0a\u9650 " + iterationDecision.configuredHardMax() + " \u8f6e\u3002\u8be5\u4e0a\u9650\u9ed8\u8ba4\u5173\u95ed\uff1b\u5f53\u524d\u8fd0\u884c\u5df2\u5b89\u5168\u505c\u6b62\u3002",
                                                            "Reached the configured final safety fuse of " + iterationDecision.configuredHardMax() + " iterations. This fuse is disabled by default; the run stopped safely.");
                                            this.appendRunLog(runLog, "\n- Stop reason: " + stopReason + "\n");
                                            this.failTaskAndProject(sse, conv, task, noProgressStop
                                                    ? this.localText(visibleLanguage, "\u8fde\u7eed\u65e0\u8fdb\u5c55", "No progress")
                                                    : this.localText(visibleLanguage, "\u8fbe\u5230\u6700\u7ec8\u8fd0\u884c\u4fdd\u9669\u4e0a\u9650", "Hard iteration fuse reached"), stopReason);
                                            this.writeAgentCheckpoint(project, request, task, ctx, noProgressStop ? "no_progress_guard" : "hard_iteration_limit", stopReason, "", "", runLog);
                                            this.streamFinal(sse, conv, this.buildStopFinal(this.localText(visibleLanguage, "\u5df2\u505c\u6b62", "Stopped"), stopReason, project, runLog, visibleLanguage), visibleLanguage);
                                            this.sendEvent(sse, conv, "DONE", Map.of("message", noProgressStop
                                                    ? this.localText(visibleLanguage, "\u65e0\u8fdb\u5c55\u5faa\u73af\u4fdd\u62a4\u5df2\u505c\u6b62", "No-progress guard stopped the run")
                                                    : this.localText(visibleLanguage, "\u8fbe\u5230\u6700\u7ec8\u8fd0\u884c\u4fdd\u9669\u4e0a\u9650", "Hard iteration fuse reached"), "iterations", i - 1));
                                            emitter.complete();
                                            return;
                                        }
                                        String runningStep = this.localText(visibleLanguage, "\u601d\u8003\u4e2d", "Thinking");
                                        if (resumedRun) {
                                            this.taskService.updateTask(task.getTaskId(), "running", runningStep, null,
                                                    AgentRunTransitionKey.forResumedRunUpdate(task.getTaskId(), request.getSubmittedAt(),
                                                            "running", runningStep, null));
                                        } else {
                                            this.taskService.updateTask(task.getTaskId(), "running", runningStep, null);
                                        }
                                        // Provider 请求、上下文预算和最终门禁必须使用同一份持久化投影。
                                        List<Map<String, Object>> providerMessagesBeforeManagement = this.providerMessagesForBudget(task.getTaskId());
                                        ContextAdmissionDecision preCompactionAdmission = this.evaluateContextAdmission(
                                                modelConfig, sysPrompt, tools, contextPrompt, providerMessagesBeforeManagement);
                                        if (preCompactionAdmission != null
                                                && preCompactionAdmission.action() == ContextAdmissionDecision.Action.BLOCK_STATIC_OVERFLOW) {
                                            this.publishContextStatus(sse, conv, request, llmProvider, llmConfig, modelConfig,
                                                    sysPrompt, tools, contextPrompt, providerMessagesBeforeManagement,
                                                    "STATIC_ADMISSION_BLOCKED");
                                            this.stopForContextLimit(sse, conv, task, project, request, ctx, runLog,
                                                    preCompactionAdmission, i, visibleLanguage, emitter);
                                            return;
                                        }
                                        // Apply the OpenCode-style soft budget before sending the next provider request.
                                        ContextManagementResult contextManagement = this.manageContextBeforeModel(
                                                sysPrompt, tools, contextWindowPolicy, request.getMessage(), ctx,
                                                sse, conv, modelConfig, studentId, cancellationToken, transcriptEpoch);
                                        List<Map<String, Object>> providerMessages = this.providerMessagesForBudget(task.getTaskId());
                                        ContextAdmissionDecision admission = this.evaluateContextAdmission(
                                                modelConfig, sysPrompt, tools, contextPrompt, providerMessages);
                                        this.publishContextStatus(sse, conv, request, llmProvider, llmConfig, modelConfig,
                                                sysPrompt, tools, contextPrompt, providerMessages, contextManagement.strategy());
                                        AgentSsePublisher modelEventPublisher = sse;
                                        AgentConversation modelEventConversation = conv;
                                        int modelIteration = i;
                                        AgentModelTurnExecutor.ModelTurnRequest modelTurnRequest =
                                                new AgentModelTurnExecutor.ModelTurnRequest(
                                                        sysPrompt, providerMessages, tools, llmProvider, llmConfig, modelIteration, task.getTaskId(),
                                                        visibleLanguage, cancellationToken, new AgentModelTurnExecutor.EventSink() {
                                                    @Override
                                                    public void durable(String eventType, Object data) throws Exception {
                                                        AgentLoopEngine.this.sendEvent(modelEventPublisher, modelEventConversation, eventType, data);
                                                    }

                                                    @Override
                                                    public void transientEvent(String eventType, Object data) throws Exception {
                                                        modelEventPublisher.sendTransient(eventType, data);
                                                    }
                                                });
                                        Optional<AgentModelTurnExecutor.ModelTurnResult> admittedTurn = admission == null
                                                ? Optional.of(this.modelTurnExecutor.execute(modelTurnRequest))
                                                : this.contextAdmissionGate.invokeIfAllowed(admission,
                                                        () -> this.modelTurnExecutor.execute(modelTurnRequest));
                                        if (admittedTurn.isEmpty()) {
                                            this.stopForContextLimit(sse, conv, task, project, request, ctx, runLog,
                                                    admission, i, visibleLanguage, emitter);
                                            return;
                                        }
                                        AgentModelTurnExecutor.ModelTurnResult modelTurnResult = admittedTurn.orElseThrow();
                                        lr = modelTurnResult.toMap();
                                        type = (String)lr.get("type");
                                        if (cancellationToken.isCancellationRequested() || "cancelled".equals(type)) {
                                            this.completeCancelledRun(sse, conv, task, project, runLog, i, visibleLanguage, emitter);
                                            return;
                                        }
                                        this.appendRunLog(runLog, "\n- Model response type: `" + this.safeLogText(type) + "`\n");
                                        log.info("Iteration {}, type: {}", i, type);

                                        // Token usage tracking
                                        if (lr.containsKey("usage")) {
                                            try {
                                                @SuppressWarnings("unchecked")
                                                Map<String, Object> usageMap = (Map<String, Object>) lr.get("usage");
                                                if (usageMap != null) {
                                                    CacheTelemetryStatus cacheStatus = CacheTelemetry.status(
                                                            llmConfig.promptCacheKeyEnabled(), usageMap);
                                                    this.tokenTracker.recordFromMap(conv.getConversationId(), request.getSessionId(),
                                                            studentId, projectId, llmProvider.getProviderId(), llmConfig.modelName(),
                                                            usageMap, cacheStatus, i, null);
                                                    int totalTokens = intUsage(usageMap, "total_tokens");
                                                    if (totalTokens > 0) {
                                                        log.info("Iteration {}: sending TOKEN_USAGE, totalTokens={}, cacheStatus={}",
                                                                i, totalTokens, cacheStatus.value());
                                                        this.sendEvent(sse, conv, "TOKEN_USAGE", this.tokenUsagePayload(
                                                                usageMap, cacheStatus,
                                                                CacheTelemetry.hitRate(llmConfig.promptCacheKeyEnabled(), usageMap),
                                                                i, totalTokens, conv.getConversationId(), false));
                                                    }
                                                }
                                            } catch (Exception tokenEx) {
                                                log.warn("Token tracking error: {}", tokenEx.getMessage());
                                            }
                                        } else {
                                            // Estimate tokens when provider doesn't return usage
                                            try {
                                                int estimatedPrompt = sysPrompt.length() / 4 + providerMessages.stream().mapToInt(m -> {
                                                    Object c = m.get("content");
                                                    return c != null ? c.toString().length() / 4 : 0;
                                                }).sum();
                                                String responseContent = lr.get("content") != null ? lr.get("content").toString() : "";
                                                String responseThinking = lr.get("thinking") != null ? lr.get("thinking").toString() : "";
                                                int estimatedCompletion = (responseContent.length() + responseThinking.length()) / 4;
                                                int estimatedTotal = estimatedPrompt + estimatedCompletion;
                                                if (estimatedTotal > 0) {
                                                    log.info("Iteration {}: sending TOKEN_USAGE (estimated), totalTokens={}", i, estimatedTotal);
                                                    CacheTelemetryStatus cacheStatus = llmConfig.promptCacheKeyEnabled()
                                                            ? CacheTelemetryStatus.NOT_REPORTED
                                                            : CacheTelemetryStatus.DISABLED;
                                                    this.tokenTracker.record(conv.getConversationId(), request.getSessionId(),
                                                            studentId, projectId, llmProvider.getProviderId(), llmConfig.modelName(),
                                                            estimatedPrompt, estimatedCompletion, estimatedTotal, 0, 0,
                                                            cacheStatus, i, null);
                                                    Map<String, Object> estimatedUsage = new LinkedHashMap<>();
                                                    estimatedUsage.put("prompt_tokens", estimatedPrompt);
                                                    estimatedUsage.put("completion_tokens", estimatedCompletion);
                                                    estimatedUsage.put("total_tokens", estimatedTotal);
                                                    this.sendEvent(sse, conv, "TOKEN_USAGE", this.tokenUsagePayload(
                                                            estimatedUsage, cacheStatus, null, i, estimatedTotal,
                                                            conv.getConversationId(), true));
                                                }
                                            } catch (Exception estEx) {
                                                log.debug("Token estimation error: {}", estEx.getMessage());
                                            }
                                        }
                                        executed = false;
                                        if (!"tool_call".equals(type)) break block20;
                                        List<AgentModelTurnExecutor.NativeToolCall> nativeToolCalls = modelTurnResult.toolCalls();
                                        if (nativeToolCalls.isEmpty()) {
                                            this.stopForMissingToolCallIdentity(sse, conv, task, project, request, ctx,
                                                    runLog, i, "unknown", visibleLanguage, emitter);
                                            return;
                                        }
                                        for (AgentModelTurnExecutor.NativeToolCall call : nativeToolCalls) {
                                            String identityError = this.toolCallBatchProtocol.validateIdentity(call);
                                            if (!identityError.isEmpty()) {
                                                this.appendRunLog(runLog, "\n- Invalid native tool call: " + identityError + "\n");
                                                this.stopForMissingToolCallIdentity(sse, conv, task, project, request, ctx,
                                                        runLog, i, call == null ? "unknown" : call.toolName(), visibleLanguage, emitter);
                                                return;
                                            }
                                        }

                                        List<NativeToolAdmission> nativeAdmissions = new ArrayList<>();
                                        for (AgentModelTurnExecutor.NativeToolCall call : nativeToolCalls) {
                                            AgentToolTurnExecutor.ToolInputResolution input =
                                                    this.toolTurnExecutor.resolveNative(ctx, call, visibleLanguage);
                                            JsonObject publicArguments = this.publicToolArguments(
                                                    call.toolName(), input.arguments());
                                            nativeAdmissions.add(new NativeToolAdmission(call, input, publicArguments));
                                        }
                                        boolean nativeInputRejected = nativeAdmissions.stream()
                                                .anyMatch(nativeAdmission -> !nativeAdmission.allowed());
                                        if (nativeInputRejected) {
                                            nativeToolInputFailureRounds++;
                                        } else {
                                            nativeToolInputFailureRounds = 0;
                                        }

                                        String modelContent = lr.get("content") == null ? "" : lr.get("content").toString();
                                        String modelThinkingRaw = lr.get("thinking") != null ? lr.get("thinking").toString() : "";
                                        String modelThinking = this.cleanModelOutput(modelThinkingRaw);
                                        this.appendProviderMessage(task.getTaskId(), transcriptEpoch, this.toolCallBatchProtocol.assistantMessage(modelContent, nativeToolCalls));

                                        // 先对整批调用做 admission，再一次性持久为 pending 或 error Part。
                                        // Provider 原始 tool_calls 是 transcript 事实；可执行参数则必须先通过类型和 schema 门禁。
                                        for (NativeToolAdmission nativeAdmission : nativeAdmissions) {
                                            AgentModelTurnExecutor.NativeToolCall call = nativeAdmission.call();
                                            JsonObject publicArguments = nativeAdmission.publicArguments();
                                            String eventSummary = nativeAdmission.allowed()
                                                    ? this.toolNarrator.visibleActionSummary(call.toolName(), publicArguments, visibleLanguage)
                                                    : this.localText(visibleLanguage, "工具调用已拒绝", "Tool call rejected");
                                            String eventContent = nativeAdmission.allowed()
                                                    ? this.toolNarrator.visibleActionDetail(call.toolName(), publicArguments, visibleLanguage)
                                                    : nativeAdmission.rejection().getContent();
                                            this.sendEvent(sse, conv, "TOOL_CALL", Map.of(
                                                    "iteration", i,
                                                    "tool", call.toolName(),
                                                    "arguments", publicArguments,
                                                    "summary", eventSummary,
                                                    "content", eventContent,
                                                    "taskId", task.getTaskId(),
                                                    "toolCallId", call.toolCallId(),
                                                    "toolCallIndex", call.toolCallIndex()));
                                            if (nativeAdmission.allowed()) {
                                                this.journalToolPending(task.getTaskId(), call.toolCallId(), call.toolName(),
                                                        publicArguments, i);
                                            } else {
                                                this.journalToolFinished(task.getTaskId(), call.toolCallId(), call.toolName(),
                                                        publicArguments, i, nativeAdmission.rejection());
                                            }
                                        }

                                        for (int batchIndex = 0; batchIndex < nativeAdmissions.size(); batchIndex++) {
                                            NativeToolAdmission nativeAdmission = nativeAdmissions.get(batchIndex);
                                            AgentModelTurnExecutor.NativeToolCall call = nativeAdmission.call();
                                            String tn = call.toolName();
                                            JsonObject ta = nativeAdmission.arguments();
                                            JsonObject publicArgs = nativeAdmission.publicArguments();
                                            String toolCallId = call.toolCallId();
                                            this.appendRunLog(runLog, "\n### Tool call " + (batchIndex + 1) + "/" + nativeAdmissions.size()
                                                    + "\n\n- Tool: `" + this.safeLogText(tn) + "`\n- Args:\n\n```json\n"
                                                    + GSON.toJson((JsonElement) publicArgs) + "\n```\n");
                                            if (!nativeAdmission.allowed()) {
                                                ToolResult rejected = nativeAdmission.rejection();
                                                String rejectionDetail = rejected.getContent() == null ? "" : rejected.getContent();
                                                this.appendRunLog(runLog, "\n- Native input rejected: `"
                                                        + this.safeLogText(nativeAdmission.reasonCode()) + "`\n");
                                                log.warn("Iteration {}: rejected native tool input tool={} toolCallId={} reasonCode={} round={}/{}",
                                                        i, tn, toolCallId, nativeAdmission.reasonCode(),
                                                        nativeToolInputFailureRounds, MAX_NATIVE_TOOL_INPUT_FAILURE_ROUNDS);
                                                this.sendThought(sse, conv, i,
                                                        this.localText(visibleLanguage, "拒绝非法原生工具调用",
                                                                "Rejected invalid native tool call"),
                                                        rejectionDetail, task.getTaskId());
                                                this.appendToolResult(runLog, rejected);
                                                this.writeAgentCheckpoint(project, request, task, ctx,
                                                        "native_tool_input_rejected", rejectionDetail, tn, "", runLog);
                                                this.sendObserve(sse, conv, i, tn, rejected, task.getTaskId());
                                                this.appendProviderMessage(task.getTaskId(), transcriptEpoch,
                                                        this.toolCallBatchProtocol.toolResultMessage(call,
                                                                "[Tool " + tn + " result]\n"
                                                                        + this.compactToolResultForModel(tn, rejected)));
                                                continue;
                                            }
                                            if (modelThinking.isBlank()) {
                                                this.sendThought(sse, conv, i,
                                                        this.toolNarrator.visibleActionSummary(tn, publicArgs, visibleLanguage),
                                                        this.toolNarrator.buildToolThought(tn, publicArgs, false, visibleLanguage),
                                                        task.getTaskId());
                                            }

                                            AgentLoopGuard.ToolDecision loopDecision = loopGuard.beforeToolCall(tn, ta);
                                            if (loopDecision.action() != AgentLoopGuard.ToolAction.ALLOW) {
                                                String loopMessage = this.loopGuardMessage(loopDecision, tn, visibleLanguage);
                                                ToolResult blockedResult = this.loopGuardResult(loopDecision, tn, toolCallId, ctx, visibleLanguage, loopMessage);
                                                this.journalToolResult(task.getTaskId(), toolCallId, tn, publicArgs, i, blockedResult);
                                                this.appendProviderMessage(task.getTaskId(), transcriptEpoch, this.toolCallBatchProtocol.toolResultMessage(call,
                                                        "[Tool " + tn + " result]\n" + loopMessage));
                                                String skippedMessage = "Skipped because an earlier tool call in the same model turn was blocked by the loop guard.";
                                                this.journalRemainingBatchSkipped(task.getTaskId(), nativeAdmissions, batchIndex + 1, i, skippedMessage);
                                                this.appendRemainingBatchToolResults(task.getTaskId(), transcriptEpoch,
                                                        nativeAdmissions, batchIndex + 1, skippedMessage);
                                                String visibleSignature = tn + ":" + this.toolNarrator.toolTarget(this.safeTool(tn), publicArgs);
                                                this.appendRunLog(runLog, "\n- " + loopMessage + "\n");
                                                this.sendThought(sse, conv, i, this.localText(visibleLanguage, "\u68c0\u6d4b\u5230\u91cd\u590d\u64cd\u4f5c", "Loop detected"), loopMessage, task.getTaskId());
                                                this.sendEvent(sse, conv, "LOOP_GUARD", Map.of(
                                                        "iteration", i, "tool", tn, "signature", visibleSignature,
                                                        "action", loopDecision.action().name().toLowerCase(Locale.ROOT),
                                                        "cycleLength", loopDecision.cycleLength(), "message", loopMessage));
                                                this.metricsService.recordLoopGuard(ctx, visibleSignature, i);
                                                if (blockedResult.isInteractionRequired()) {
                                                    AgentInteractionPauser.Pause pause = this.interactionPauser.pause(
                                                            task.getTaskId(), blockedResult, visibleLanguage);
                                                    this.publishUserQuestion(sse, conv, blockedResult);
                                                    this.writeAgentCheckpoint(project, request, task, ctx, pause.state(),
                                                            pause.detail(), tn, this.compactToolResultForCheckpoint(tn, blockedResult), runLog);
                                                    this.publishInteractionPause(sse, conv, task.getTaskId(), blockedResult, pause, i);
                                                    emitter.complete();
                                                    return;
                                                }
                                                this.writeAgentCheckpoint(project, request, task, ctx, "loop_guard_strategy_switch", loopMessage, tn, "", runLog);
                                                this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", "[Loop guard]\n" + loopMessage
                                                        + "\nDo not repeat the blocked pattern. Change the tool, target, scope, or verification method; use existing evidence; or finish if the task is complete."));
                                                executed = true;
                                                break block19;
                                            }

                                            this.journalToolRunning(task.getTaskId(), toolCallId, tn, publicArgs, i);
                                            ToolResult res = this.execTool(tn, ta, ctx, sse, conv, visibleLanguage, toolCallId, runLog);
                                            loopGuard.recordToolResult(res.isSuccess());
                                            Optional<EnvironmentBlockerClassifier.Blocker> environmentBlocker =
                                                    EnvironmentBlockerClassifier.classify(tn, res);
                                            if (environmentBlocker.isPresent()) {
                                                String blockedResultForModel = "[Tool " + tn + " result]\n"
                                                        + this.compactToolResultForModel(tn, res);
                                                this.journalToolBlocked(task.getTaskId(), toolCallId, tn, publicArgs, i, res.getContent());
                                                this.appendProviderMessage(task.getTaskId(), transcriptEpoch,
                                                        this.toolCallBatchProtocol.toolResultMessage(call, blockedResultForModel));
                                                this.appendToolResult(runLog, res);
                                                String skippedMessage = "Skipped because an earlier tool call in the same model turn is blocked by the environment.";
                                                this.journalRemainingBatchSkipped(task.getTaskId(), nativeAdmissions, batchIndex + 1, i,
                                                        skippedMessage);
                                                this.appendRemainingBatchToolResults(task.getTaskId(), transcriptEpoch,
                                                        nativeAdmissions, batchIndex + 1, skippedMessage);
                                                this.stopForEnvironmentBlocker(sse, conv, task, project, request, ctx, runLog, i, tn,
                                                        res, environmentBlocker.get(), visibleLanguage, emitter);
                                                return;
                                            }
                                            if (res.isApprovalRequired()) {
                                                this.journalToolWaitingApproval(task.getTaskId(), toolCallId, tn, publicArgs, i, res.getApprovalId());
                                                String skippedMessage = "Skipped because an earlier tool call in the same model turn is waiting for approval.";
                                                this.journalRemainingBatchSkipped(task.getTaskId(), nativeAdmissions, batchIndex + 1, i,
                                                        skippedMessage);
                                                this.appendRemainingBatchToolResults(task.getTaskId(), transcriptEpoch,
                                                        nativeAdmissions, batchIndex + 1, skippedMessage);
                                                this.stopForCommandApproval(sse, conv, task, project, request, ctx, runLog, i, toolCallId, tn, res, visibleLanguage, emitter);
                                                return;
                                            }
                                            if (res.isInteractionRequired()) {
                                                this.journalToolResult(task.getTaskId(), toolCallId, tn, publicArgs, i, res);
                                            } else {
                                                this.journalToolFinished(task.getTaskId(), toolCallId, tn, publicArgs, i, res);
                                            }
                                            this.appendToolResult(runLog, res);
                                            this.writeAgentCheckpoint(project, request, task, ctx,
                                                    res.isInteractionRequired() ? this.interactionWaitingState(res) : (res.isSuccess() ? "tool_success" : "tool_failed"),
                                                    "Tool `" + this.safeLogText(tn) + "` returned.", tn,
                                                    this.compactToolResultForCheckpoint(tn, res), runLog);

                                            this.sendObserve(sse, conv, i, tn, res, task.getTaskId());
                                            this.sendThought(sse, conv, i, this.localText(visibleLanguage, "\u68c0\u67e5\u7ed3\u679c", "Check result"),
                                                    this.toolNarrator.buildResultThought(tn, ta, res, visibleLanguage), task.getTaskId());
                                            if (res.isInteractionRequired()) {
                                                this.journalRemainingBatchSkipped(task.getTaskId(), nativeAdmissions, batchIndex + 1, i,
                                                        "Skipped because an earlier tool call in the same model turn is waiting for user input.");
                                                AgentInteractionPauser.Pause pause = this.interactionPauser.pause(
                                                        task.getTaskId(), res, visibleLanguage);
                                                this.publishUserQuestion(sse, conv, res);
                                                String waitingState = pause.state();
                                                String waitingTitle = pause.title();
                                                String waitingDetail = pause.detail();
                                                this.appendRunLog(runLog, "\n- Durable user interaction pending: type=`" + this.safeLogText(res.getInteractionType())
                                                        + "`, requestId=`" + this.safeLogText(res.getInteractionRequestId()) + "`\n");
                                                this.writeAgentCheckpoint(project, request, task, ctx, waitingState, waitingDetail, tn,
                                                        this.compactToolResultForCheckpoint(tn, res), runLog);
                                                this.publishInteractionPause(sse, conv, task.getTaskId(), res, pause, i);
                                                emitter.complete();
                                                return;
                                            }
                                            String planStatus = ctx.getPlanSummary();
                                            String planNote = planStatus.isEmpty() ? "" : "\nCurrent plan progress:\n" + planStatus;
                                            String stageNote = "\nCurrent engineering stage: " + ctx.getStage();
                                            String resultForModel = "[Tool " + tn + " result]\n"
                                                    + this.compactToolResultForModel(tn, res) + planNote + stageNote
                                                    + "\nContinue using tools when needed. Only output the final summary after all plan tasks are complete and verified.";
                                            this.appendProviderMessage(task.getTaskId(), transcriptEpoch, this.toolCallBatchProtocol.toolResultMessage(call, resultForModel));
                                            if (isMissingPlanCompletion(tn, ta, res)) {
                                                String skippedMessage = "Skipped because completing a plan requires an existing plan.";
                                                this.journalRemainingBatchSkipped(task.getTaskId(), nativeAdmissions, batchIndex + 1, i, skippedMessage);
                                                this.appendRemainingBatchToolResults(task.getTaskId(), transcriptEpoch,
                                                        nativeAdmissions, batchIndex + 1, skippedMessage);
                                                this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content",
                                                        "No execution plan exists. Create a plan before completing plan items; do not submit more complete actions in the same turn."));
                                                executed = true;
                                                break block19;
                                            }
                                        }
                                        if (nativeInputRejected) {
                                            boolean anyExecutableInput = nativeAdmissions.stream()
                                                    .anyMatch(NativeToolAdmission::allowed);
                                            if (!anyExecutableInput) {
                                                loopGuard.recordModelNoProgress();
                                            }
                                            if (nativeToolInputFailureRounds >= MAX_NATIVE_TOOL_INPUT_FAILURE_ROUNDS) {
                                                String failureTitle = this.localText(visibleLanguage,
                                                        "原生工具调用参数连续无效",
                                                        "Native tool-call arguments repeatedly invalid");
                                                String failureReason = this.localText(visibleLanguage,
                                                        "模型连续返回无法安全解析或不符合 schema 的原生工具参数，运行已停止，避免误执行或无限重试。",
                                                        "The model repeatedly returned native tool arguments that could not be parsed or did not match the exposed schema. The run stopped to prevent unsafe execution or an infinite retry.");
                                                this.sendEvent(sse, conv, "ERROR", Map.of(
                                                        "message", failureTitle,
                                                        "iteration", i,
                                                        "reasonCode", "native_tool_input_recovery_exhausted"));
                                                this.streamFinal(sse, conv,
                                                        this.buildStopFinal(failureTitle, failureReason, project, runLog, visibleLanguage),
                                                        visibleLanguage);
                                                this.failTaskAndProject(sse, conv, task, failureTitle, failureReason);
                                                this.writeAgentCheckpoint(project, request, task, ctx,
                                                        "failed_native_tool_input_recovery", failureReason, "", "", runLog);
                                                this.sendEvent(sse, conv, "DONE", Map.of(
                                                        "message", failureTitle,
                                                        "iterations", i,
                                                        "reasonCode", "native_tool_input_recovery_exhausted"));
                                                emitter.complete();
                                                return;
                                            }
                                            this.appendProviderMessage(task.getTaskId(), transcriptEpoch,
                                                    Map.of("role", "user", "content",
                                                            "One or more native tool calls were rejected before execution because their arguments were missing, malformed, not a JSON object, unavailable in this turn, or incompatible with the exposed schema. "
                                                                    + "Retry with complete JSON object arguments that exactly match the selected tool schema. Do not repeat rejected arguments."));
                                        }
                                        executed = true;
                                        break block19;
                                    }
                                    if (!"text".equals(type)) break block21;
                                    content = lr.get("content") != null ? lr.get("content").toString() : "";
                                    String modelThinkingFromLr = lr.get("thinking") != null ? this.cleanModelOutput(lr.get("thinking").toString()) : "";
                                    ToolCallExtractor.Extraction recoveredTextCall = ToolCallExtractor.extract(content);
                                    if (recoveredTextCall.status() == ToolCallExtractor.Status.NONE) break block22;
                                    executed = true;
                                    String invTool = recoveredTextCall.toolName();
                                    JsonObject parsedArgs = recoveredTextCall.arguments();
                                    String recoveredRejection = "";
                                    if (!recoveredTextCall.executable()) {
                                        recoveredRejection = this.localText(visibleLanguage,
                                                "文本工具调用信封无效（" + recoveredTextCall.status().name().toLowerCase(Locale.ROOT)
                                                        + "），已拒绝执行。",
                                                "The text tool-call envelope is "
                                                        + recoveredTextCall.status().name().toLowerCase(Locale.ROOT)
                                                        + " and was rejected.");
                                    } else {
                                        AgentToolTurnExecutor.ToolResolution recoveredResolution =
                                                this.toolTurnExecutor.resolveRecovered(ctx, invTool, parsedArgs, visibleLanguage);
                                        if (!recoveredResolution.allowed()) {
                                            recoveredRejection = recoveredResolution.rejection().getContent();
                                        }
                                    }
                                    if (!recoveredRejection.isBlank()) {
                                        textToolCallRecoveryFailures++;
                                        String recoveryReason = recoveredTextCall.reason().isBlank()
                                                ? recoveredRejection : recoveredTextCall.reason();
                                        String recoverySummary = this.localText(visibleLanguage,
                                                "拒绝不安全的文本工具调用", "Rejected unsafe text tool call");
                                        this.appendRunLog(runLog,
                                                "\n### Rejected text tool-call recovery\n\n- Status: `"
                                                        + recoveredTextCall.status().name().toLowerCase(Locale.ROOT)
                                                        + "`\n- Format: `" + this.safeLogText(recoveredTextCall.format())
                                                        + "`\n- Reason: " + this.safeLogText(recoveryReason)
                                                        + "\n- Recovery attempt: " + textToolCallRecoveryFailures + "/"
                                                        + MAX_TEXT_TOOL_CALL_RECOVERY_FAILURES + "\n");
                                        log.warn("Iteration {}: rejected text tool-call recovery status={} format={} attempt={}/{}",
                                                i, recoveredTextCall.status(), recoveredTextCall.format(),
                                                textToolCallRecoveryFailures, MAX_TEXT_TOOL_CALL_RECOVERY_FAILURES);
                                        this.sendThought(sse, conv, i, recoverySummary, recoveredRejection, task.getTaskId());
                                        this.writeAgentCheckpoint(project, request, task, ctx,
                                                "text_tool_call_rejected", recoveredRejection, invTool, "", runLog);
                                        loopGuard.recordModelNoProgress();
                                        this.appendProviderMessage(task.getTaskId(), transcriptEpoch,
                                                Map.of("role", "assistant", "content", content));
                                        if (textToolCallRecoveryFailures >= MAX_TEXT_TOOL_CALL_RECOVERY_FAILURES) {
                                            String stopReason = this.localText(visibleLanguage,
                                                    "模型连续返回无法安全执行的文本工具调用。系统已经停止，避免误执行或无限重试。请切换支持原生 tool call 的模型，或调整 Provider 兼容配置。",
                                                    "The model repeatedly returned text tool calls that could not be executed safely. The run stopped to prevent unsafe execution or an infinite retry. Use a model with native tool calling or correct the provider compatibility settings.");
                                            this.sendEvent(sse, conv, "ERROR", Map.of(
                                                    "message", recoverySummary + ": " + recoveredRejection,
                                                    "iteration", i,
                                                    "reasonCode", "text_tool_call_recovery_exhausted"));
                                            this.streamFinal(sse, conv,
                                                    this.buildStopFinal(recoverySummary, stopReason, project, runLog, visibleLanguage),
                                                    visibleLanguage);
                                            this.failTaskAndProject(sse, conv, task, recoverySummary, stopReason);
                                            this.writeAgentCheckpoint(project, request, task, ctx,
                                                    "failed_text_tool_call_recovery", stopReason, invTool, "", runLog);
                                            this.sendEvent(sse, conv, "DONE", Map.of(
                                                    "message", recoverySummary,
                                                    "iterations", i,
                                                    "reasonCode", "text_tool_call_recovery_exhausted"));
                                            emitter.complete();
                                            return;
                                        }
                                        this.appendProviderMessage(task.getTaskId(), transcriptEpoch,
                                                Map.of("role", "user", "content",
                                                        "Your previous text tool call was rejected because it was incomplete, ambiguous, invalid, unknown, not exposed in this turn, or did not match the tool schema. "
                                                                + "Retry once using the provider's native structured tool-call protocol. Do not describe or embed a tool call in ordinary text."));
                                        break block19;
                                    }
                                    JsonObject publicArgs = this.publicToolArguments(invTool, parsedArgs);
                                    this.appendRunLog(runLog, "\n### Recovered tool call from explicit text envelope\n\n- Tool: `"
                                            + this.safeLogText(invTool) + "`\n- Format: `"
                                            + this.safeLogText(recoveredTextCall.format())
                                            + "`\n- Public args:\n\n```json\n" + GSON.toJson((JsonElement)publicArgs) + "\n```\n");
                                    // Send local thinking only if streaming didn't already cover it
                                    if (modelThinkingFromLr == null || modelThinkingFromLr.isBlank()) {
                                        this.sendThought(sse, conv, i, this.toolNarrator.visibleActionSummary(invTool, publicArgs, visibleLanguage), this.toolNarrator.buildToolThought(invTool, publicArgs, true, visibleLanguage), task.getTaskId());
                                    }
                                    String recoveredToolCallId = this.recoveredToolCallId(ctx, i, invTool, parsedArgs);
                                    this.sendEvent(sse, conv, "TOOL_CALL", Map.of("iteration", i, "tool", invTool, "arguments", publicArgs, "summary", this.toolNarrator.visibleActionSummary(invTool, publicArgs, visibleLanguage), "content", this.toolNarrator.visibleActionDetail(invTool, publicArgs, visibleLanguage), "taskId", task.getTaskId(), "toolCallId", recoveredToolCallId));
                                    this.journalToolPending(task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i);
                                    AgentLoopGuard.ToolDecision recoveredLoopDecision = loopGuard.beforeToolCall(invTool, parsedArgs);
                                    if (recoveredLoopDecision.action() != AgentLoopGuard.ToolAction.ALLOW) {
                                        String loopMessage = this.loopGuardMessage(recoveredLoopDecision, invTool, visibleLanguage);
                                        ToolResult blockedResult = this.loopGuardResult(recoveredLoopDecision, invTool, recoveredToolCallId, ctx, visibleLanguage, loopMessage);
                                        this.journalToolResult(task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i, blockedResult);
                                        String visibleSignature = invTool + ":" + this.toolNarrator.toolTarget(this.safeTool(invTool), publicArgs);
                                        this.appendRunLog(runLog, "\n- " + loopMessage + "\n");
                                        this.sendThought(sse, conv, i, this.localText(visibleLanguage, "\u68c0\u6d4b\u5230\u91cd\u590d\u64cd\u4f5c", "Loop detected"), loopMessage, task.getTaskId());
                                        this.sendEvent(sse, conv, "LOOP_GUARD", Map.of(
                                                "iteration", i, "tool", invTool, "signature", visibleSignature,
                                                "action", recoveredLoopDecision.action().name().toLowerCase(Locale.ROOT),
                                                "cycleLength", recoveredLoopDecision.cycleLength(), "message", loopMessage));
                                        this.metricsService.recordLoopGuard(ctx, visibleSignature, i);
                                        if (blockedResult.isInteractionRequired()) {
                                            AgentInteractionPauser.Pause pause = this.interactionPauser.pause(
                                                    task.getTaskId(), blockedResult, visibleLanguage);
                                            this.publishUserQuestion(sse, conv, blockedResult);
                                            this.writeAgentCheckpoint(project, request, task, ctx, pause.state(),
                                                    pause.detail(), invTool, this.compactToolResultForCheckpoint(invTool, blockedResult), runLog);
                                            this.publishInteractionPause(sse, conv, task.getTaskId(), blockedResult, pause, i);
                                            emitter.complete();
                                            return;
                                        }
                                        this.writeAgentCheckpoint(project, request, task, ctx, "loop_guard_strategy_switch", loopMessage, invTool, "", runLog);
                                        this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", ""));
                                        this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", "[Loop guard]\n" + loopMessage
                                                + "\nDo not repeat the blocked pattern. Change the tool, target, scope, or verification method; use existing evidence; or finish if the task is complete."));
                                        break block19;
                                    }
                                    this.journalToolRunning(task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i);
                                    ToolResult res = this.execTool(invTool, parsedArgs, ctx, sse, conv, visibleLanguage, recoveredToolCallId, runLog);
                                    loopGuard.recordToolResult(res.isSuccess());
                                     Optional<EnvironmentBlockerClassifier.Blocker> recoveredEnvironmentBlocker =
                                             EnvironmentBlockerClassifier.classify(invTool, res);
                                     if (recoveredEnvironmentBlocker.isPresent()) {
                                         this.journalToolBlocked(task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i, res.getContent());
                                         this.appendToolResult(runLog, res);
                                         this.stopForEnvironmentBlocker(sse, conv, task, project, request, ctx, runLog, i,
                                                 invTool, res, recoveredEnvironmentBlocker.get(), visibleLanguage, emitter);
                                         return;
                                     }
                                    if (res.isApprovalRequired()) {
                                        this.journalToolWaitingApproval(task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i, res.getApprovalId());
                                        this.stopForCommandApproval(sse, conv, task, project, request, ctx, runLog, i, recoveredToolCallId, invTool, res, visibleLanguage, emitter);
                                        return;
                                    }
                                    this.journalToolResult(task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i, res);
                                    this.appendToolResult(runLog, res);
                                    this.writeAgentCheckpoint(project, request, task, ctx, res.isInteractionRequired() ? this.interactionWaitingState(res) : (res.isSuccess() ? "tool_success" : "tool_failed"), "Recovered and executed tool `" + this.safeLogText(invTool) + "`.", invTool, this.compactToolResultForCheckpoint(invTool, res), runLog);
                                    this.sendObserve(sse, conv, i, invTool, res, task.getTaskId());

                                    this.sendThought(sse, conv, i, this.localText(visibleLanguage, "\u68c0\u67e5\u7ed3\u679c", "Check result"), this.toolNarrator.buildResultThought(invTool, parsedArgs, res, visibleLanguage), task.getTaskId());
                                    if (res.isInteractionRequired()) {
                                        AgentInteractionPauser.Pause pause = this.interactionPauser.pause(
                                                task.getTaskId(), res, visibleLanguage);
                                        this.publishUserQuestion(sse, conv, res);
                                        String waitingState = pause.state();
                                        String waitingTitle = pause.title();
                                        String waitingDetail = pause.detail();
                                        this.appendRunLog(runLog, "\n- Durable user interaction pending: type=`" + this.safeLogText(res.getInteractionType())
                                                + "`, requestId=`" + this.safeLogText(res.getInteractionRequestId()) + "`\n");
                                        this.writeAgentCheckpoint(project, request, task, ctx, waitingState, waitingDetail, invTool,
                                                this.compactToolResultForCheckpoint(invTool, res), runLog);
                                        this.publishInteractionPause(sse, conv, task.getTaskId(), res, pause, i);
                                        emitter.complete();
                                        return;
                                    }
                                    String planStatus2 = ctx.getPlanSummary();
                                    Object planNote2 = planStatus2.isEmpty() ? "" : "\nCurrent plan progress:\n" + planStatus2;
                                    String stageNote2 = "\nCurrent engineering stage: " + ctx.getStage();
                                    this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", ""));
                                    this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", "[Tool " + invTool + " result]\n" + this.compactToolResultForModel(invTool, res) + (String)planNote2 + stageNote2 + "\nCall tools to continue. Complete all plan tasks and verify before final summary."));
                                    break block19;
                                }
                                cleaned = this.cleanModelOutput(content);
                                log.info("Iteration {}: text ({} chars, cleaned={} chars): {}", new Object[]{i, content.length(), cleaned.length(), content.substring(0, Math.min(200, content.length()))});
                                if (!cleaned.isEmpty()) break block23;
                                log.info("Iteration {}: empty/marker-only response, nudging model", i);
                                loopGuard.recordModelNoProgress();
                                this.appendRunLog(runLog, "\n- Model returned empty/marker-only response, requesting continuation.\n");
                                this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", content));
                                this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", "Task not done. Call tools to execute next step. Do not output plain text ending."));
                                executed = true;
                                break block19;
                            }
                            ft = this.extractThinking(content);
                            if (ft.isEmpty()) {
                                ft = cleaned;
                            }
                            if (!this.isPrematureFinal(ft, ctx)) break block24;
                            loopGuard.recordModelNoProgress();
                            this.appendRunLog(runLog, "\n- Model returned mid-placeholder text, rejecting as final, continuing: `" + this.safeLogText(ft) + "`\n");
            this.writeAgentCheckpoint(project, request, task, ctx, "continuing_after_premature_final", "Model returned mid-placeholder text, rejected as final.", "", ft, runLog);
                            this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", ft));
                            this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", this.buildContinuationInstruction(ctx)));
                            executed = true;
                            break block19;
                        }
                        if (this.hasOpenPlan(ctx)) {
                            loopGuard.recordModelNoProgress();
                            this.appendRunLog(runLog, "\n- Plan has unfinished tasks, rejecting premature end. Remaining: " + ctx.getPlanSummary() + "\n");
                            this.writeAgentCheckpoint(project, request, task, ctx, "continuing_open_plan", "Plan has unfinished tasks, rejecting premature end.", "", ft, runLog);
                            this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", ft));
                            String planMsg = "Your plan has unfinished tasks. Cannot end yet. Continue execution:\n" + ctx.getPlanSummary() + "\nUse create_plan complete to mark done items, then continue next item.";
                            this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", planMsg));
                            executed = true;
                            break block19;
                        } else {
                            boolean tooShort = ft.length() < 80;
                            boolean noStructure = !ft.contains("##") && !ft.contains("**") && !ft.contains("- ");
                            boolean noSubstance = !containsFinalSubstance(ft);
                            if (shouldRejectFinalReply(request.getMessage(), ft)) {
                                loopGuard.recordModelNoProgress();
                                this.appendRunLog(runLog, "\n- Reply quality insufficient (length=" + ft.length() + ", noStructure=" + noStructure + ", noSubstance=" + noSubstance + "), rejecting as final.\n");
                                this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", ft));
                                this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", "Reply too short to be final. Output complete structured summary:\n## Summary\n**Completed**\n- What was modified\n**Verification**\n- How it was verified\n**Suggestions**\n- Next steps"));
                                executed = true;
                                break block19;
                            } else {
                                // Model reasoning is already streamed by AgentModelTurnExecutor.
                                this.appendRunLog(runLog, "\n## Final response\n\n" + this.safeLogText(ft) + "\n");
                                log.info("Iteration {}: final response ({} chars)", i, ft.length());
                                AgentRunFinalizer.CompletionAssessment completion = this.runFinalizer.assess(
                                        task.getTaskId(), studentId, projectId, ctx.hasTrustedVerification());
                                if (completion != null) {
                                    this.sendEvent(sse, conv, "COMPLETION_EVIDENCE", completion.evidence().toPayload());
                                    if (!completion.allowed()) {
                                        loopGuard.recordModelNoProgress();
                                        this.appendRunLog(runLog, "\n- Server completion evidence rejected the model final response.\n");
                                        this.writeAgentCheckpoint(project, request, task, ctx, "continuing_missing_completion_evidence",
                                                completion.guidance(), "", this.limitForContext(ft, 2000), runLog);
                                        this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", ft));
                                        this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", completion.guidance()));
                                        executed = true;
                                        break block19;
                                    }
                                }
                                this.sendEvent(sse, conv, "FINAL", Map.of("content", ft, "summary", this.finalResponseSummary(visibleLanguage)));
                                AgentRunEvent completedEvent = this.taskService.updateTask(task.getTaskId(), "completed",
                                        this.localText(visibleLanguage, "\u5df2\u5b8c\u6210", "Completed"), ft);
                                ctx.setStage("final");
                                this.writeAgentCheckpoint(project, request, task, ctx, "completed", "Task completed with final response.", "", this.limitForContext(ft, 2000), runLog);
                                this.sendPersistedEvent(sse, conv, completedEvent);
                                this.sendEvent(sse, conv, "DONE", Map.of("message", this.localText(visibleLanguage, "\u5b8c\u6210", "Done"), "iterations", i));
                                emitter.complete();
                                return;
                            }
                        }
                    }
                    if ("error".equals(type)) {
                        String errMsg = (String)lr.get("message");
                        log.warn("Iteration {} API error: {}", i, errMsg);
                        this.appendRunLog(runLog, "\n### Model error\n\n" + this.safeLogText(errMsg) + "\n");
                        // 上下文溢出只允许有限策略切换：先压缩，再减少工具 schema，之后明确停止。
                        if (this.isContextOverflowError(errMsg)) {
                            List<Map<String, Object>> overflowMessagesBefore =
                                    this.providerMessagesForBudget(task.getTaskId());
                            int tokensBeforeRecovery = this.estimateProviderRequestTokens(
                                    sysPrompt, tools, overflowMessagesBefore, modelConfig);
                            String overflowStrategy = "NONE";
                            boolean recovered = false;
                            ContextOverflowRecoveryPolicy.Action recoveryAction = overflowRecoveryPolicy.next(
                                    this.canReduceToolSchema(tools));
                            if (recoveryAction == ContextOverflowRecoveryPolicy.Action.COMPACT) {
                                log.warn("Context overflow at iteration {}, running durable compaction", i);
                                this.appendRunLog(runLog, "\n### Context overflow, running durable compaction\n");
                                try {
                                    int keepRecentTurns = contextWindowPolicy == null
                                            ? ConversationCheckpointCompactor.DEFAULT_KEEP_RECENT_TURNS
                                            : contextWindowPolicy.tailTurns();
                                    int preserveRecentTokens = contextWindowPolicy == null
                                            ? 8_000 : contextWindowPolicy.preserveRecentTokens();
                                    ContextManagementResult compaction = this.compactContextWithFallback(sysPrompt, tools,
                                            request.getMessage(), ctx, sse, conv, modelConfig, studentId, cancellationToken,
                                            keepRecentTurns, preserveRecentTokens, tokensBeforeRecovery, "provider_overflow",
                                            transcriptEpoch);
                                    overflowStrategy = compaction.strategy();
                                    int afterCompaction = this.estimateProviderRequestTokens(sysPrompt, tools,
                                            this.providerMessagesForBudget(task.getTaskId()), modelConfig);
                                    recovered = compaction.changed()
                                            && hasContextCompactionProgress(tokensBeforeRecovery, afterCompaction);
                                } catch (Exception compactErr) {
                                    log.warn("Durable context compaction failed: {}", compactErr.getMessage());
                                }
                                if (!recovered) {
                                    recoveryAction = overflowRecoveryPolicy.next(this.canReduceToolSchema(tools));
                                }
                            }
                            if (!recovered && recoveryAction == ContextOverflowRecoveryPolicy.Action.REDUCE_TOOL_SCHEMA) {
                                int toolCountBefore = tools.size();
                                List<Map<String, Object>> overflowMessagesBeforeToolReduction =
                                        this.providerMessagesForBudget(task.getTaskId());
                                int tokensBeforeToolReduction = this.estimateProviderRequestTokens(
                                        sysPrompt, tools, overflowMessagesBeforeToolReduction, modelConfig);
                                boolean reduced = this.reduceToolSchemaForOverflow(tools);
                                int tokensAfterToolReduction = this.estimateProviderRequestTokens(
                                        sysPrompt, tools, this.providerMessagesForBudget(task.getTaskId()), modelConfig);
                                recovered = reduced && hasContextCompactionProgress(
                                        tokensBeforeToolReduction, tokensAfterToolReduction);
                                overflowStrategy = "REDUCED_TOOL_SCHEMA";
                                if (reduced) {
                                    this.sendEvent(sse, conv, "CONTEXT_TOOL_SCHEMA_REDUCED",
                                            contextEvent("tool_schema_reduction", tokensBeforeToolReduction,
                                                    tokensAfterToolReduction, Map.of(
                                                            "toolCountBefore", toolCountBefore,
                                                            "toolCountAfter", tools.size(),
                                                            "recoveryAttempt", overflowRecoveryPolicy.attempts())));
                                }
                            }
                            List<Map<String, Object>> overflowMessagesAfter =
                                    this.providerMessagesForBudget(task.getTaskId());
                            int tokensAfterRecovery = this.estimateProviderRequestTokens(
                                    sysPrompt, tools, overflowMessagesAfter, modelConfig);
                            this.publishContextStatus(sse, conv, request, llmProvider, llmConfig, modelConfig,
                                    sysPrompt, tools, contextPrompt, overflowMessagesAfter, overflowStrategy);
                            if (!recovered || !hasContextCompactionProgress(tokensBeforeRecovery, tokensAfterRecovery)) {
                                String modelFailTitle = this.localText(visibleLanguage, "上下文窗口超限", "Context window exceeded");
                                String modelFailReason = this.localText(visibleLanguage,
                                        "压缩和工具 schema 缩减策略已经耗尽，系统已停止重复请求。请配置正确的模型窗口、切换更大上下文模型或新建会话。",
                                        "Compaction and tool-schema reduction strategies were exhausted, so repeated provider requests were stopped. Configure the model window, use a larger-context model, or start a new conversation.");
                                this.appendRunLog(runLog, "\n### Context overflow recovery exhausted\n\n- estimated_tokens_before: "
                                        + tokensBeforeRecovery + "\n- estimated_tokens_after: " + tokensAfterRecovery
                                        + "\n- recovery_attempts: " + overflowRecoveryPolicy.attempts() + "\n");
                                this.sendEvent(sse, conv, "ERROR", Map.of("message", modelFailTitle + ": " + errMsg,
                                        "iteration", i, "reasonCode", "context_overflow_recovery_exhausted"));
                                this.streamFinal(sse, conv, this.buildStopFinal(modelFailTitle, modelFailReason, project, runLog, visibleLanguage), visibleLanguage);
                                this.failTaskAndProject(sse, conv, task, modelFailTitle, errMsg);
                                this.writeAgentCheckpoint(project, request, task, ctx, "failed_context_overflow", modelFailReason, "", errMsg, runLog);
                                this.sendEvent(sse, conv, "DONE", Map.of("message", modelFailTitle,
                                        "iterations", i, "reasonCode", "context_overflow_recovery_exhausted"));
                                emitter.complete();
                                return;
                            }
                            this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content",
                                    "Context recovery changed the request safely. Continue from where you left off. "
                                            + "Re-read files with read_file or grep when exact output is needed."));
                            executed = true;
                            break block19;
                        }
                        if (this.isModelTimeoutError(errMsg)) {
                            String modelFailTitle = this.localText(visibleLanguage, "模型响应超时", "Model response timed out");
                            String modelFailReason = this.localText(visibleLanguage,
                                    "模型服务未及时返回首个响应，本次运行已停止。请检查模型服务、代理配置或更换模型。",
                                    "The model service did not return an initial response in time, so this run was stopped. Check the provider, proxy, or try another model.");
                            this.sendEvent(sse, conv, "ERROR", Map.of("message", modelFailTitle + ": " + errMsg, "iteration", i));
                            this.streamFinal(sse, conv, this.buildStopFinal(modelFailTitle, modelFailReason, project, runLog, visibleLanguage), visibleLanguage);
                            this.failTaskAndProject(sse, conv, task, modelFailTitle, errMsg);
                            this.writeAgentCheckpoint(project, request, task, ctx, "failed_model_timeout", modelFailReason, "", errMsg, runLog);
                            this.sendEvent(sse, conv, "DONE", Map.of("message", modelFailTitle, "iterations", i));
                            emitter.complete();
                            return;
                        }
                        if (this.isRecoverableModelError(errMsg)) {
                            int nextAttempt = (task.getRetryAttempts() == null ? 0 : task.getRetryAttempts()) + 1;
                            long delay = Math.min(4000L, 1000L * (1L << Math.min(nextAttempt, 2)));
                            AgentTaskService.ModelRetrySchedule retry = this.taskService.scheduleModelRetry(
                                    task.getTaskId(), 2, delay, this.limitForThought(errMsg, 240));
                            if (retry != null) {
                                String guidance = this.buildRecoverableErrorGuidance(
                                        errMsg, retry.attempt(), retry.delayMs());
                                this.appendRunLog(runLog, "\n- Recoverable error, retry " + retry.attempt()
                                        + ", scheduled for " + retry.nextRetryAt()
                                        + "\n- Guidance: " + this.safeLogText(guidance) + "\n");
                                this.writeAgentCheckpoint(project, request, task, ctx, "retrying",
                                        "Recoverable model connection error; retry " + retry.attempt() + " is scheduled.",
                                        "", errMsg, runLog);
                                this.sendPersistedEvent(sse, conv, retry.event());
                                this.sendThought(sse, conv, i,
                                        this.localText(visibleLanguage, "Network retry", "Network retry"), guidance,
                                        task.getTaskId());
                                emitter.complete();
                                return;
                            }
                        }
                        {
                            String modelFailTitle = this.localText(visibleLanguage, "模型 API 调用失败", "Model API failed");
                            String modelFailReason = this.localText(visibleLanguage,
                                    "模型连接连续失败，任务已暂停。请检查 API Key、模型服务或稍后重试。",
                                    "Model connection failed continuously. Task paused. Check API Key config or retry later.");
                            this.sendEvent(sse, conv, "ERROR", Map.of("message", modelFailTitle + ": " + errMsg, "iteration", i));
                            this.streamFinal(sse, conv, this.buildStopFinal(modelFailTitle, modelFailReason, project, runLog, visibleLanguage), visibleLanguage);
                            this.failTaskAndProject(sse, conv, task, modelFailTitle, errMsg);
                            this.writeAgentCheckpoint(project, request, task, ctx, "failed_model_error", "Model connection failed continuously. Task paused.", "", errMsg, runLog);
                            this.sendEvent(sse, conv, "DONE", Map.of("message", modelFailTitle, "iterations", i));
                            emitter.complete();
                            return;
                        }
                    }
                    log.warn("Iteration {} unknown type: {}", i, type);
                    loopGuard.recordModelNoProgress();
                    this.appendRunLog(runLog, "\n- Unknown response type: `" + this.safeLogText(type) + "`\n");
                    this.sendEvent(sse, conv, "ERROR", Map.of("message", "Unknown response type: " + type));
                    executed = true;
                }
                ++i;
            }
        }
        catch (Exception e) {
            log.error("Agent loop error", (Throwable)e);
            try {
                this.appendRunLog(runLog, "\n## Runtime exception\n\n```text\n" + this.safeLogText(e.toString()) + "\n```\n");
                if (task == null) {
                    this.reportStartupFailure(sse, visibleLanguage, e);
                } else if (!sse.isBound()) {
                    this.reportUnboundDispatchFailure(sse, task, visibleLanguage, e);
                } else if (cancellationToken.isCancellationRequested() && project != null) {
                    this.completeCancelledRun(sse, conv, task, project, runLog, 0, visibleLanguage, emitter);
                } else if (e instanceof InterruptedException) {
                    this.sendEvent(sse, conv, "INTERRUPTED", Map.of("message", this.localText(visibleLanguage, "已中断", "Interrupted")));
                } else {
                    String runtimeTitle = this.localText(visibleLanguage, "运行时异常", "Runtime exception");
                    String runtimeReason = this.localText(visibleLanguage,
                            "Agent 执行过程中遇到异常：`" + e.getMessage() + "`。",
                            "Agent encountered exception during execution: `" + e.getMessage() + "`.");
                    this.streamFinal(sse, conv, this.buildStopFinal(runtimeTitle, runtimeReason, project, runLog, visibleLanguage), visibleLanguage);
                    this.failTaskAndProject(sse, conv, task, runtimeTitle, e.getMessage());
                    this.writeAgentCheckpoint(project, request, task, ctx, "failed_exception", "Agent encountered exception during execution.", "", e.toString(), runLog);
                    this.sendEvent(sse, conv, "DONE", Map.of("message", runtimeTitle));
                }
            } catch (Exception ignored) {
                log.error("Error in exception handler", ignored);
            }
            emitter.complete();
            return;
        } finally {
            try {
                if (task != null) {
                    this.taskService.finishTimingIfTerminal(task.getTaskId());
                }
            } catch (Exception timingFailure) {
                log.error("Unable to finalize agent task timing for taskId={}", task == null ? null : task.getTaskId(), timingFailure);
            } finally {
                if (task != null) {
                    this.diffService.awaitDeferredSnapshots(task.getTaskId(), "agent_terminal_cleanup");
                }
                if (checkoutLease != null && this.projectCheckoutLeaseHeartbeatService != null) {
                    this.projectCheckoutLeaseHeartbeatService.untrack(checkoutLease);
                }
                if (checkoutLease != null && this.projectCheckoutLeaseService != null) {
                    this.projectCheckoutLeaseService.release(checkoutLease);
                }
                if (executionLease != null && this.leaseHeartbeatService != null) {
                    this.leaseHeartbeatService.untrack(executionLease);
                }
                if (executionLease != null && this.executionLeaseService != null) {
                    this.executionLeaseService.release(executionLease);
                }
                if (task != null && this.workspaceLeaseService != null) {
                    int released = this.workspaceLeaseService.releaseAllOwnedBy("task:" + task.getTaskId());
                    if (released > 0) {
                        log.warn("Released {} leaked workspace lease(s) during terminal cleanup for taskId={}",
                                released, task.getTaskId());
                    }
                }
                this.cancellationRegistry.complete(activeCancellation);
            }
        }
    }

    private Path checkoutWorkspace(StudentProject project, AgentTask task) {
        Path projectWorkspace = ProjectWorkspace.paths(project).workspaceRoot();
        if (task == null || task.getBackgroundWorktree() == null || task.getBackgroundWorktree().isBlank()) {
            return projectWorkspace;
        }
        return BackgroundRunWorkspaceResolver.resolve(projectWorkspace, task.getBackgroundWorktree());
    }

    private void stopForEnvironmentBlocker(AgentSsePublisher sse, AgentConversation conv, AgentTask task,
                                            StudentProject project, AgentStreamRequest request, AgentContext ctx,
                                            Path runLog, int iteration, String toolName, ToolResult result,
                                            EnvironmentBlockerClassifier.Blocker blocker, String visibleLanguage,
                                            SseEmitter emitter) throws Exception {
        String summary = this.localText(visibleLanguage, "\u7b49\u5f85\u73af\u5883\u6062\u590d", "Waiting for environment recovery");
        String detail = this.localText(visibleLanguage,
                "\u68c0\u6d4b\u5230\u5916\u90e8\u4f9d\u8d56\u73af\u5883\u6545\u969c\uff08" + blocker.code()
                        + "\uff09\uff0c\u4efb\u52a1\u5df2\u6682\u505c\u3002\u8bf7\u6062\u590d DNS \u6216\u7f51\u7edc\u540e\u624b\u52a8\u91cd\u8bd5\uff1b\u4e0d\u4f1a\u4fee\u6539\u9879\u76ee\u4f9d\u8d56\u914d\u7f6e\u6765\u63a9\u76d6\u8be5\u95ee\u9898\u3002",
                "An external dependency environment failure (" + blocker.code()
                        + ") was detected. The task was paused. Restore DNS or network access and retry manually; project dependency configuration was not modified to mask the failure.");
        this.taskService.waitForEnvironment(task.getTaskId(), summary, detail, blocker.code());
        this.appendRunLog(runLog, "\n- Environment blocked: code=`" + this.safeLogText(blocker.code())
                + "`, tool=`" + this.safeLogText(toolName) + "`\n");
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("taskId", task.getTaskId());
        event.put("tool", toolName);
        event.put("blockerCode", blocker.code());
        event.put("detail", blocker.detail());
        event.put("taskStatus", "waiting_environment");
        event.put("retryable", true);
        event.put("manualRetryRequired", true);
        event.put("result", this.compactToolResultForCheckpoint(toolName, result));
        this.sendEvent(sse, conv, "ENVIRONMENT_BLOCKED", event);
        this.writeAgentCheckpoint(project, request, task, ctx, "waiting_environment", detail, toolName,
                this.compactToolResultForCheckpoint(toolName, result), runLog);
        this.sendEvent(sse, conv, "TASK_PAUSED", Map.of(
                "message", summary,
                "detail", detail,
                "iterations", iteration,
                "taskId", task.getTaskId(),
                "taskStatus", "waiting_environment",
                "reason", "environment",
                "resumeAgentLoop", false,
                "manualRetryRequired", true));
        // 只关闭当前 HTTP 传输；可恢复任务不得发送 FINAL/DONE 终态事件。
        emitter.complete();
    }

    private void waitForProjectCheckout(AgentSsePublisher sse, AgentConversation conv, AgentTask task,
                                         StudentProject project, AgentStreamRequest request, Path runLog, Path workspace,
                                         ProjectCheckoutLeaseService.AcquireResult admission,
                                         String visibleLanguage, SseEmitter emitter) throws Exception {
        String summary = this.localText(visibleLanguage, "\u7b49\u5f85\u9879\u76ee\u5de5\u4f5c\u533a", "Waiting for project workspace");
        String detail = this.localText(visibleLanguage,
                "\u53e6\u4e00\u4e2a Agent \u4efb\u52a1\u6b63\u5728\u4f7f\u7528\u540c\u4e00\u5de5\u4f5c\u533a\uff1b\u672c\u4efb\u52a1\u5c1a\u672a\u8c03\u7528\u6a21\u578b\uff0c\u5df2\u6392\u961f\u5e76\u4f1a\u5728\u5de5\u4f5c\u533a\u53ef\u7528\u540e\u6062\u590d\u3002",
                "Another Agent task is using this checkout. This task has not called the model; it was queued and will resume when the checkout is available.");
        this.taskService.waitForWorkspace(task.getTaskId(), summary, detail, admission.blockingTaskId());
        this.appendRunLog(runLog, "\n- Workspace admission deferred. checkout=`"
                + this.safeLogText(String.valueOf(workspace)) + "`, blockingTaskId=`"
                + this.safeLogText(String.valueOf(admission.blockingTaskId())) + "`\n");
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("taskId", task.getTaskId());
        event.put("workspace", String.valueOf(workspace));
        event.put("blockingTaskId", admission.blockingTaskId() == null ? "" : admission.blockingTaskId());
        event.put("resumeAutomatically", true);
        event.put("message", detail);
        this.sendEvent(sse, conv, "WORKSPACE_WAITING", event);
        this.sendEvent(sse, conv, "TASK_PAUSED", Map.of(
                "taskId", task.getTaskId(),
                "taskStatus", "waiting_workspace",
                "reason", "workspace_checkout",
                "message", summary,
                "detail", detail,
                "resumeAgentLoop", true));
        // Close only the current HTTP transport; a recoverable task must not emit terminal FINAL/DONE events.
        emitter.complete();
    }

    void reportStartupFailure(AgentSsePublisher sse, String visibleLanguage, Exception failure) {
        String title = this.localText(visibleLanguage, "Agent 启动失败", "Agent startup failed");
        String detail = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? this.localText(visibleLanguage, "未提供错误详情", "No error details were provided")
                : failure.getMessage();
        try {
            if (sse.isBound()) {
                sse.send("ERROR", Map.of("message", title + ": " + detail));
            } else {
                sse.sendTransient("ERROR", Map.of("message", title + ": " + detail));
            }
        } catch (Exception sendFailure) {
            log.warn("Unable to send agent startup error event: {}", sendFailure.getMessage());
        }
        try {
            if (sse.isBound()) {
                sse.send("DONE", Map.of("message", title));
            } else {
                sse.sendTransient("DONE", Map.of("message", title));
            }
        } catch (Exception sendFailure) {
            log.warn("Unable to send agent startup completion event: {}", sendFailure.getMessage());
        }
    }

    /**
     * 取得 task 之前失败可以作为 startup failure；取得 task 但尚未拥有 execution lease 时则不能篡改 task 状态。
     * 此时只向当前连接发送瞬时错误，等待持久化恢复调度器处理原有 run。
     */
    private void reportUnboundDispatchFailure(AgentSsePublisher sse, AgentTask task,
                                              String visibleLanguage, Exception failure) {
        String detail = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? this.localText(visibleLanguage, "执行领取失败", "Execution dispatch was not claimed")
                : failure.getMessage();
        log.warn("AGENT_RUN_DISPATCH_UNBOUND taskId={} reason={}", task == null ? null : task.getTaskId(), detail);
        try {
            sse.sendTransient("ERROR", Map.of("message", this.localText(visibleLanguage,
                    "任务尚由其他执行器持有，已保留等待恢复状态：" + detail,
                    "The task is still owned by another executor; its durable waiting state was preserved: " + detail)));
        } catch (Exception sendFailure) {
            log.warn("Unable to send unbound agent dispatch error: {}", sendFailure.getMessage());
        }
    }

    private void completeCancelledRun(AgentSsePublisher sse, AgentConversation conv, AgentTask task,
                                      StudentProject project, Path runLog, int iteration,
                                      String visibleLanguage, SseEmitter emitter) throws Exception {
        String step = this.localText(visibleLanguage, "用户已取消", "User cancelled");
        String reason = this.localText(visibleLanguage, "用户取消了本次执行", "User cancelled execution");
        AgentTaskService.CancellationFinalization finalization = this.taskService.finalizeCancellationWithEvent(
                task.getTaskId(), step, reason);
        if (!finalization.finalized()) {
            log.warn("Cancellation finalization was skipped because task {} changed state concurrently", task.getTaskId());
        } else if (finalization.event() != null) {
            this.sendPersistedEvent(sse, conv, finalization.event());
        }
        this.appendRunLog(runLog, "\n- Stop reason: user cancelled.\n");
        this.sendEvent(sse, conv, "INTERRUPTED", Map.of("message", reason, "iteration", iteration));
        this.streamFinal(sse, conv, this.buildStopFinal(
                step,
                this.localText(visibleLanguage, "用户主动取消了本次 Agent 运行。", "User actively cancelled this run."),
                project,
                runLog,
                visibleLanguage), visibleLanguage);
        emitter.complete();
    }

    private ToolResult execTool(String name, JsonObject args, AgentContext ctx, AgentSsePublisher sse,
                                AgentConversation conv, String visibleLanguage, String toolCallId, Path runLog) {
        // 工具权限检查：plan/explore 模式下不允许使用编辑类工具
        AgentToolTurnExecutor.ToolResolution resolution = this.toolTurnExecutor.resolve(ctx, name, visibleLanguage);
        if (!resolution.allowed()) return resolution.rejection();
        AgentTool t = resolution.tool();
        if ("question".equals(this.safeTool(name))) {
            return this.askUserQuestion(args, ctx, toolCallId, visibleLanguage);
        }
        String guardedCommand = this.commandForGuard(name, args, ctx);
        String guardedWorkingDirectory = this.commandWorkingDirectoryForArgs(name, ctx.getWorkspaceRoot(), args);
        boolean approvedOfflineRetry = this.isCommandPolicyTool(name)
                && this.networkAccessService != null
                && this.networkAccessService.hasApprovedOfflineRetryGrant(ctx.getTaskId(), guardedCommand);
        boolean networkRequested = this.networkRequested(args) || approvedOfflineRetry;
        boolean networkEnabledForTool = false;
        if (this.isCommandPolicyTool(name)) {
            String networkCommand = this.commandForGuard(name, args, ctx);
            CommandClassification classification = this.commandClassification(name, args, ctx);
            if (classification != null && classification.decision() != CommandDecision.BLOCK
                    && networkRequested && this.networkAccessService != null
                    && !this.networkAccessService.hasApprovedGrant(ctx.getTaskId(), networkCommand)) {
                return this.createNetworkApproval(ctx, name, networkCommand, toolCallId,
                        "explicit_command", false, toolCallId, this.networkSummary());
            }
            if (classification != null) {
                int timeout = this.commandTimeout(name, args);
                if (classification.decision() == CommandDecision.BLOCK) {
                    return ToolResult.failed("command blocked by restricted command policy: "
                            + classification.reasonCode().name().toLowerCase(Locale.ROOT));
                }
                CommandFailureGuard.Decision retryDecision = this.commandFailureGuard.before(
                        ctx.getTaskId(), guardedCommand, guardedWorkingDirectory);
                if (!retryDecision.allowed()) {
                    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
                    payload.put("taskId", ctx.getTaskId());
                    payload.put("tool", name);
                    payload.put("command", CommandRedactor.redact(guardedCommand));
                    payload.put("code", retryDecision.code());
                    payload.put("blockerCode", retryDecision.code());
                    payload.put("attempts", retryDecision.attempts());
                    payload.put("retryable", false);
                    payload.put("detail", retryDecision.message());
                    try {
                        this.sendEvent(sse, conv, "ENVIRONMENT_BLOCKED", payload);
                    } catch (Exception ignored) {
                        log.debug("Unable to publish command recovery event", ignored);
                    }
                    return ToolResult.failed("failure_code=" + retryDecision.code() + "\nretryable=false\n" + retryDecision.message());
                }
                if (classification.requiresApproval()
                        && !approvedOfflineRetry
                        && !(acceptanceAutoApproveVerification && "run_tests".equals(this.safeTool(name)))) {
                    return this.createCommandApproval(ctx, classification, timeout, toolCallId);
                }
            }
        }
        try {
            String mode = ctx.getMode();
            List<PermissionRule> defaultRules = DefaultPermissionRuleset.getRulesForAgent(mode);
            String inputStr = this.permissionInput(name, args);
            PermissionService.PermissionEvaluation eval = permissionService.evaluate(name, inputStr, defaultRules, ctx.getSessionId(), ctx.getProject().getProjectId());
            switch (eval.getAction()) {
                case DENY:
                    PermissionRule matched = eval.getMatchedRule();
                    String denyMsg = this.localText(visibleLanguage,
                            mode + " 模式权限规则拒绝工具 `" + name + "`：匹配规则（" + (matched != null ? matched.getPermission() + "=" + matched.getAction() : "default deny") + "）。如需写入，请切换到 build 模式。",
                            "Permission denied by " + mode + " agent rules: tool '" + name
                                    + "' matching rule (" + (matched != null ? matched.getPermission() + "=" + matched.getAction() : "default deny")
                                    + "). Switch to build mode for write operations.");
                    log.warn(denyMsg);
                    return ToolResult.failed(denyMsg);
                case ASK:
                    PermissionRule askRule = eval.getMatchedRule();
                    return this.requestToolApproval(
                            ctx,
                            name,
                            inputStr,
                            this.toolNarrator.visibleActionSummary(name, args, visibleLanguage),
                            askRule != null ? askRule.getPermission() : "*",
                            askRule != null ? askRule.getPattern() : "*",
                            toolCallId,
                            visibleLanguage
                    );
                case ALLOW:
                    break;
            }
        } catch (Exception e) {
            log.warn("Permission check failed: {}", e.getMessage());
            return ToolResult.failed(this.localText(visibleLanguage, "权限检查失败：" + e.getMessage(), "Permission check failed: " + e.getMessage()));
        }
        if (networkRequested) {
            if (!this.isCommandPolicyTool(name) || this.networkAccessService == null) {
                return ToolResult.failed("\u5f53\u524d\u5de5\u5177\u4e0d\u652f\u6301\u53d7\u63a7\u7f51\u7edc\u8bbf\u95ee");
            }
            if (!this.networkAccessService.consumeGrant(ctx.getStudentId(), ctx.getProject().getProjectId(),
                    ctx.getTaskId(), guardedCommand)) {
                return this.createNetworkApproval(ctx, name, guardedCommand, toolCallId,
                        "explicit_command", false, toolCallId, this.networkSummary());
            }
            ctx.setNetworkEnabled(true);
            networkEnabledForTool = true;
        }

        long totalStartedNanos = System.nanoTime();
        long delegateElapsedMs = 0L;
        long beforeSnapshotElapsedMs = 0L;
        long afterSnapshotElapsedMs = 0L;
        long snapshotDiffElapsedMs = 0L;
        long postEditElapsedMs = 0L;
        long contextElapsedMs = 0L;
        long metricsElapsedMs = 0L;
        String phase = "tool_delegate";
        this.appendToolExecutionStart(runLog, ctx, name, toolCallId, args);
        this.sendToolExecutionEvent(sse, conv, "TOOL_EXECUTION_STARTED", ctx, name, toolCallId,
                "tool_delegate", 0L, Map.of());
        log.info("AGENT_TOOL_EXEC_START taskId={} projectId={} conversationId={} tool={} toolCallId={} argsChars={}",
                ctx.getTaskId(), ctx.getProject().getProjectId(), ctx.getConversationId(), name, toolCallId,
                args == null ? 0 : args.toString().length());
        try {
            this.diffService.clearLastApplyTelemetry();
            this.diffService.awaitDeferredSnapshots(ctx.getTaskId(), "before_tool:" + this.safeTool(name));
            GitSnapshotService.Snapshot beforeSnapshot = null;
            boolean snapshotCommand = this.shouldSnapshotCommandTool(name);
            if (snapshotCommand) {
                phase = "snapshot_before_command";
                this.sendToolExecutionEvent(sse, conv, "TOOL_PHASE_CHANGED", ctx, name, toolCallId,
                        phase, elapsedMs(totalStartedNanos), Map.of());
                long snapshotStartedNanos = System.nanoTime();
                beforeSnapshot = this.gitSnapshotService.capture(ctx.getProject(), "before " + name + " task " + ctx.getTaskId());
                beforeSnapshotElapsedMs = elapsedMs(snapshotStartedNanos);
            }

            phase = "tool_delegate";
            long delegateStartedNanos = System.nanoTime();
            ToolResult result = this.toolTurnExecutor.execute(t, ctx, args, name);
            if (result.isSuccess() && this.isPlanTool(name)) {
                // 计划事件在工具事务中已取得 sequence，必须先于后续工具生命周期事件投影。
                this.projectPersistedPlanUpdate(sse, ctx);
            }
            result = this.annotateCommandRecovery(name, result);
            result = this.maybeRequestNetworkAfterFailure(ctx, name, args, toolCallId, result);
            delegateElapsedMs = elapsedMs(delegateStartedNanos);
            DiffService.ApplyTelemetry diffTelemetry = this.diffService.consumeLastApplyTelemetry();
            if (!diffTelemetry.timingMs().isEmpty()) {
                log.info("AGENT_TOOL_DIFF_TELEMETRY taskId={} projectId={} tool={} phase={} timings={}",
                        ctx.getTaskId(), ctx.getProject().getProjectId(), name, diffTelemetry.phase(), diffTelemetry.timingMs());
                this.appendDiffApplyTelemetry(runLog, diffTelemetry);
            }
            if (result.isSuccess() && result.getPendingChangeId() != null && !result.getPendingChangeId().isBlank()) {
                this.diffService.scheduleDeferredSnapshot(result.getPendingChangeId());
            }

            if (shouldRecordSnapshotDiff(snapshotCommand, result)) {
                phase = "snapshot_after_command";
                this.sendToolExecutionEvent(sse, conv, "TOOL_PHASE_CHANGED", ctx, name, toolCallId,
                        phase, elapsedMs(totalStartedNanos), Map.of());
                long snapshotStartedNanos = System.nanoTime();
                GitSnapshotService.Snapshot afterSnapshot = this.gitSnapshotService.capture(ctx.getProject(),
                        "after " + name + " task " + ctx.getTaskId());
                afterSnapshotElapsedMs = elapsedMs(snapshotStartedNanos);

                phase = "record_snapshot_diff";
                this.sendToolExecutionEvent(sse, conv, "TOOL_PHASE_CHANGED", ctx, name, toolCallId,
                        phase, elapsedMs(totalStartedNanos), Map.of());
                long diffStartedNanos = System.nanoTime();
                List<PendingChange> changes = this.diffService.recordSnapshotDiff(ctx.getStudentId(), ctx.getProject(),
                        ctx.getConversationId(), ctx.getTaskId(), name, beforeSnapshot, afterSnapshot);
                snapshotDiffElapsedMs = elapsedMs(diffStartedNanos);
                if (!changes.isEmpty()) {
                    this.attachSnapshotChanges(result, changes);
                }
            }

            phase = "post_edit_hook";
            this.sendToolExecutionEvent(sse, conv, "TOOL_PHASE_CHANGED", ctx, name, toolCallId,
                    phase, elapsedMs(totalStartedNanos), Map.of());
            long postEditStartedNanos = System.nanoTime();
            AgentPostEditHookService.HookReport hookReport = this.postEditHookService.afterTool(ctx, name, args, result);
            postEditElapsedMs = elapsedMs(postEditStartedNanos);
            if (hookReport.content() != null && !hookReport.content().isBlank()) {
                result.setContent((result.getContent() == null ? "" : result.getContent()) + hookReport.content());
            }
            if (this.isCommandPolicyTool(name) && !result.isSuccess() && !result.isApprovalRequired()) {
                this.commandFailureGuard.record(ctx.getTaskId(), name, guardedCommand, guardedWorkingDirectory, result.getContent());
            }
            if (!result.isSuccess() && !result.isApprovalRequired()) {
                this.recordToolFailure(ctx, name, toolCallId, result.getContent());
            }

            phase = "context_orchestration";
            this.sendToolExecutionEvent(sse, conv, "TOOL_PHASE_CHANGED", ctx, name, toolCallId,
                    phase, elapsedMs(totalStartedNanos), Map.of());
            long contextStartedNanos = System.nanoTime();
            this.contextOrchestrator.afterTool(ctx, name, args, result);
            contextElapsedMs = elapsedMs(contextStartedNanos);

            phase = "metrics_persistence";
            this.sendToolExecutionEvent(sse, conv, "TOOL_PHASE_CHANGED", ctx, name, toolCallId,
                    phase, elapsedMs(totalStartedNanos), Map.of());
            long metricsStartedNanos = System.nanoTime();
            this.metricsService.recordTool(ctx, name, args, result, elapsedMs(totalStartedNanos), hookReport);
            metricsElapsedMs = elapsedMs(metricsStartedNanos);
            long totalElapsedMs = elapsedMs(totalStartedNanos);
            log.info("AGENT_TOOL_EXEC_COMPLETE taskId={} projectId={} tool={} toolCallId={} success={} totalMs={} delegateMs={} beforeSnapshotMs={} afterSnapshotMs={} snapshotDiffMs={} postEditMs={} contextMs={} metricsMs={} pendingChangeId={}",
                    ctx.getTaskId(), ctx.getProject().getProjectId(), name, toolCallId, result.isSuccess(), totalElapsedMs,
                    delegateElapsedMs, beforeSnapshotElapsedMs, afterSnapshotElapsedMs, snapshotDiffElapsedMs,
                    postEditElapsedMs, contextElapsedMs, metricsElapsedMs, result.getPendingChangeId());
            this.appendToolExecutionComplete(runLog, name, toolCallId, result, totalElapsedMs, delegateElapsedMs,
                    beforeSnapshotElapsedMs, afterSnapshotElapsedMs, snapshotDiffElapsedMs, postEditElapsedMs,
                    contextElapsedMs, metricsElapsedMs);
            this.sendToolExecutionEvent(sse, conv, "TOOL_EXECUTION_COMPLETED", ctx, name, toolCallId, phase,
                    totalElapsedMs, toolTimingPayload(delegateElapsedMs, beforeSnapshotElapsedMs, afterSnapshotElapsedMs,
                            snapshotDiffElapsedMs, postEditElapsedMs, contextElapsedMs));
            return result;
        }
        catch (AgentToolTurnExecutor.ToolTimedOutException timeout) {
            long totalElapsedMs = elapsedMs(totalStartedNanos);
            ToolResult failed = ToolResult.failed(this.localText(visibleLanguage,
                    "\u5de5\u5177\u6267\u884c\u8d85\u65f6\uff08\u9884\u7b97 " + timeout.budgetMs() + " ms\uff09\uff0c\u5df2\u8bf7\u6c42\u505c\u6b62\u6267\u884c\u3002",
                    "Tool execution timed out after " + timeout.budgetMs() + " ms; cancellation was requested."));
            LinkedHashMap<String, Object> timing = toolTimingPayload(delegateElapsedMs, beforeSnapshotElapsedMs,
                    afterSnapshotElapsedMs, snapshotDiffElapsedMs, postEditElapsedMs, contextElapsedMs);
            timing.put("timedOut", true);
            timing.put("budgetMs", timeout.budgetMs());
            timing.put("error", timeout.getMessage());
            this.appendToolExecutionFailed(runLog, name, toolCallId, phase, totalElapsedMs, timeout);
            this.sendToolExecutionEvent(sse, conv, "TOOL_TIMED_OUT", ctx, name, toolCallId, phase,
                    totalElapsedMs, timing);
            this.recordToolFailure(ctx, name, toolCallId, failed.getContent());
            this.contextOrchestrator.afterTool(ctx, name, args, failed);
            this.metricsService.recordTool(ctx, name, args, failed, totalElapsedMs, AgentPostEditHookService.HookReport.empty());
            return failed;
        }
        catch (Exception e) {
            long totalElapsedMs = elapsedMs(totalStartedNanos);
            log.warn("AGENT_TOOL_EXEC_FAILED taskId={} projectId={} tool={} toolCallId={} phase={} totalMs={} delegateMs={} beforeSnapshotMs={} afterSnapshotMs={} snapshotDiffMs={} postEditMs={} contextMs={} metricsMs={} errorType={} error={}",
                    ctx.getTaskId(), ctx.getProject().getProjectId(), name, toolCallId, phase, totalElapsedMs,
                    delegateElapsedMs, beforeSnapshotElapsedMs, afterSnapshotElapsedMs, snapshotDiffElapsedMs,
                    postEditElapsedMs, contextElapsedMs, metricsElapsedMs, e.getClass().getSimpleName(), e.getMessage());
            ToolResult failed = ToolResult.failed((String)e.getMessage());
            this.appendToolExecutionFailed(runLog, name, toolCallId, phase, totalElapsedMs, e);
            LinkedHashMap<String, Object> timing = toolTimingPayload(delegateElapsedMs, beforeSnapshotElapsedMs,
                    afterSnapshotElapsedMs, snapshotDiffElapsedMs, postEditElapsedMs, contextElapsedMs);
            timing.put("error", this.limitForThought(e.getMessage(), 240));
            this.sendToolExecutionEvent(sse, conv, "TOOL_EXECUTION_FAILED", ctx, name, toolCallId, phase,
                    totalElapsedMs, timing);
            this.recordToolFailure(ctx, name, toolCallId, failed.getContent());
            this.contextOrchestrator.afterTool(ctx, name, args, failed);
            this.metricsService.recordTool(ctx, name, args, failed, totalElapsedMs, AgentPostEditHookService.HookReport.empty());
            return failed;
        }
        finally {
            if (networkEnabledForTool) {
                ctx.setNetworkEnabled(false);
            }
        }
    }

    private ToolResult annotateCommandRecovery(String toolName, ToolResult result) {
        if (result == null || result.isSuccess() || result.isApprovalRequired() || !this.isCommandPolicyTool(toolName)) {
            return result;
        }
        String content = result.getContent() == null ? "" : result.getContent();
        EnvironmentBlockerClassifier.Blocker blocker = EnvironmentBlockerClassifier.classify(toolName, result).orElse(null);
        String prefix;
        if (blocker != null) {
            prefix = "failure_code=" + blocker.code()
                    + "\nretryable=false\nrecovery_action=restore_environment_or_switch_verification_strategy\n"
                    + "recovery_detail=" + blocker.detail();
        } else if (this.recoveryProperties.isAutoRepairEnabled()) {
            prefix = "failure_code=COMMAND_FAILED\nretryable=true\nrecovery_action=inspect_output_edit_then_retry\n"
                    + "max_automatic_repairs=" + this.recoveryProperties.getMaxAutomaticRepairs();
        } else {
            prefix = "failure_code=COMMAND_FAILED\nretryable=false\nrecovery_action=manual_intervention_required";
        }
        result.setContent(prefix + "\n" + content);
        return result;
    }
    private void recordToolFailure(AgentContext context, String toolName, String toolCallId, String content) {
        if (context == null || context.getTaskId() == null) {
            return;
        }
        try {
            String safeContent = CommandRedactor.redact(content == null ? "" : content);
            artifactService.record(context.getTaskId(), "tool_failure",
                    (toolName == null ? "unknown" : toolName) + ":" + (toolCallId == null ? "" : toolCallId),
                    "tool=" + safeTool(toolName) + "\n" + safeContent);
        } catch (Exception ignored) {
            // 工具失败记录不能掩盖原始工具结果。
        }
    }

    private LinkedHashMap<String, Object> toolTimingPayload(long delegateMs, long beforeSnapshotMs,
                                                              long afterSnapshotMs, long snapshotDiffMs,
                                                              long postEditMs, long contextMs) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("delegateMs", delegateMs);
        payload.put("beforeSnapshotMs", beforeSnapshotMs);
        payload.put("afterSnapshotMs", afterSnapshotMs);
        payload.put("snapshotDiffMs", snapshotDiffMs);
        payload.put("postEditMs", postEditMs);
        payload.put("contextMs", contextMs);
        return payload;
    }

    private void sendToolExecutionEvent(AgentSsePublisher sse, AgentConversation conv, String type,
                                        AgentContext ctx, String tool, String toolCallId, String phase,
                                        long elapsedMs, Map<String, Object> details) {
        try {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", ctx.getTaskId());
            payload.put("tool", this.safeTool(tool));
            payload.put("toolCallId", toolCallId == null ? "" : toolCallId);
            payload.put("phase", phase);
            payload.put("elapsedMs", elapsedMs);
            if (details != null) payload.putAll(details);
            this.sendEvent(sse, conv, type, payload);
        } catch (Exception eventFailure) {
            log.warn("Unable to publish tool execution event tool={} type={}: {}", tool, type, eventFailure.getMessage());
        }
    }

    static boolean isMissingPlanCompletion(String toolName, JsonObject arguments, ToolResult result) {
        String normalized = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        if (!("create_plan".equals(normalized) || "plan".equals(normalized))) return false;
        String action = arguments != null && arguments.has("action")
                ? arguments.get("action").getAsString() : "";
        if (!"complete".equalsIgnoreCase(action) || result == null || result.isSuccess()) return false;
        String content = result.getContent() == null ? "" : result.getContent();
        return content.contains("failure_code=PLAN_MISSING")
                || content.contains("\u5f53\u524d\u6ca1\u6709\u6267\u884c\u8ba1\u5212");
    }

    private String iterationPolicyDescription() {
        int hardMax = loopProperties == null ? 0 : loopProperties.getHardMaxIterations();
        return hardMax > 0
                ? "repeat/cycle guard with configurable hard fuse=" + hardMax
                : "repeat/cycle guard; no fixed total iteration cap";
    }

    private String loopGuardMessage(AgentLoopGuard.ToolDecision decision, String toolName, String visibleLanguage) {
        String safeName = this.safeTool(toolName);
        if (decision.action() == AgentLoopGuard.ToolAction.REQUEST_USER) {
            return this.localText(visibleLanguage,
                    "Agent \u5728\u5207\u6362\u7b56\u7565\u540e\u4ecd\u91cd\u590d\u4e86\u5de5\u5177 `" + safeName + "` \u7684\u76f8\u540c\u8c03\u7528\u6a21\u5f0f\uff0c\u5df2\u6682\u505c\u5e76\u8bf7\u6c42\u7528\u6237\u51b3\u5b9a\u3002",
                    "The agent repeated the same `" + safeName + "` tool-call pattern after a strategy switch, so the run paused for a user decision.");
        }
        String pattern = decision.cycleLength() <= 1
                ? this.localText(visibleLanguage, "\u5b8c\u5168\u76f8\u540c\u7684\u8c03\u7528", "an identical call")
                : this.localText(visibleLanguage, decision.cycleLength() + " \u6b65\u5faa\u73af\u8c03\u7528",
                        "a " + decision.cycleLength() + "-step call cycle");
        return this.localText(visibleLanguage,
                "\u5faa\u73af\u4fdd\u62a4\u68c0\u6d4b\u5230\u5de5\u5177 `" + safeName + "` \u51fa\u73b0" + pattern + "\u3002\u672c\u6b21\u8c03\u7528\u5df2\u62e6\u622a\uff1bAgent \u5fc5\u987b\u6539\u7528\u4e0d\u540c\u5de5\u5177\u3001\u76ee\u6807\u3001\u8303\u56f4\u6216\u9a8c\u8bc1\u7b56\u7565\u3002",
                "Loop protection detected " + pattern + " involving tool `" + safeName + "`. This call was blocked; the agent must change the tool, target, scope, or verification strategy.");
    }

    private ToolResult loopGuardResult(AgentLoopGuard.ToolDecision decision, String toolName, String toolCallId,
                                       AgentContext ctx, String visibleLanguage, String loopMessage) {
        if (decision.action() != AgentLoopGuard.ToolAction.REQUEST_USER) {
            return ToolResult.failed(loopMessage);
        }
        JsonObject question = new JsonObject();
        question.addProperty("summary", this.localText(visibleLanguage, "\u68c0\u6d4b\u5230\u5de5\u5177\u8c03\u7528\u6b7b\u5faa\u73af", "Tool-call loop detected"));
        question.addProperty("question", this.localText(visibleLanguage,
                "Agent \u5df2\u7ecf\u81ea\u52a8\u5c1d\u8bd5\u5207\u6362\u7b56\u7565\uff0c\u4f46\u4ecd\u91cd\u590d\u8c03\u7528\u5de5\u5177 `" + this.safeTool(toolName) + "`\u3002\u8bf7\u9009\u62e9\u4e0b\u4e00\u6b65\uff1b\u4e5f\u53ef\u4ee5\u76f4\u63a5\u8f93\u5165\u65b0\u7684\u5904\u7406\u8981\u6c42\u3002",
                "The agent already tried to switch strategy but still repeated tool `" + this.safeTool(toolName) + "`. Choose the next step, or enter new instructions."));
        com.google.gson.JsonArray options = new com.google.gson.JsonArray();
        options.add(this.localText(visibleLanguage, "\u7ee7\u7eed\uff0c\u4f46\u5fc5\u987b\u66f4\u6362\u7b56\u7565", "Continue with a different strategy"));
        options.add(this.localText(visibleLanguage, "\u5141\u8bb8\u91cd\u65b0\u5c1d\u8bd5\u8be5\u8c03\u7528", "Allow this call to be retried"));
        options.add(this.localText(visibleLanguage, "\u505c\u6b62\u5e76\u603b\u7ed3\u5f53\u524d\u8fdb\u5ea6", "Stop and summarize current progress"));
        question.add("options", options);
        return this.askUserQuestion(question, ctx, toolCallId, visibleLanguage);
    }

    private ToolResult askUserQuestion(JsonObject args, AgentContext ctx, String toolCallId, String visibleLanguage) {
        String question = ToolSupport.stringArg(args, "question", "").trim();
        if (question.isBlank()) {
            return ToolResult.failed(this.localText(visibleLanguage, "question 参数不能为空", "question is required"));
        }
        String summary = ToolSupport.stringArg(args, "summary", "").trim();
        if (summary.isBlank()) {
            summary = this.localText(visibleLanguage, "等待用户输入", "Waiting for user input");
        }
        try {
            List<String> options = this.questionOptions(args);
            AgentInteractionService.UserQuestionRequest request = this.interactionService.beginQuestion(
                    ctx.getProject().getProjectId(),
                    ctx.getStudentId(),
                    ctx.getSessionId(),
                    ctx.getTaskId(),
                    ctx.getConversationId(),
                    toolCallId,
                    question,
                    summary,
                    options
            );
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("requestId", request.requestId());
            payload.put("interactionType", "question");
            payload.put("question", request.question());
            payload.put("summary", request.summary());
            payload.put("options", request.options());
            payload.put("projectId", request.projectId());
            payload.put("sessionId", request.sessionId());
            payload.put("taskId", request.taskId());
            payload.put("conversationId", request.conversationId());
            payload.put("toolCallId", request.toolCallId() == null ? "" : request.toolCallId());
            payload.put("createdAt", request.createdAt());
            return ToolResult.interactionRequired(
                    this.localText(visibleLanguage, "\u6b63\u5728\u7b49\u5f85\u7528\u6237\u8f93\u5165\u3002", "Waiting for user input."),
                    request.requestId(),
                    "question").withInteractionPayload(payload);
        } catch (Exception e) {
            return ToolResult.failed(this.localText(visibleLanguage, "用户问题处理失败：" + e.getMessage(), "User question failed: " + e.getMessage()));
        }
    }

    private List<String> questionOptions(JsonObject args) {
        if (args == null || !args.has("options") || args.get("options").isJsonNull()) {
            return List.of();
        }
        JsonElement raw = args.get("options");
        ArrayList<String> options = new ArrayList<>();
        if (raw.isJsonArray()) {
            raw.getAsJsonArray().forEach(item -> {
                if (!item.isJsonNull()) {
                    String value = item.getAsString().trim();
                    if (!value.isBlank()) {
                        options.add(value);
                    }
                }
            });
            return options;
        }
        if (raw.isJsonPrimitive()) {
            String text = raw.getAsString();
            try {
                JsonElement parsed = JsonParser.parseString(text);
                if (parsed.isJsonArray()) {
                    parsed.getAsJsonArray().forEach(item -> {
                        if (!item.isJsonNull()) {
                            String value = item.getAsString().trim();
                            if (!value.isBlank()) {
                                options.add(value);
                            }
                        }
                    });
                    return options;
                }
            } catch (Exception ignored) {
                // Fall back to newline/comma splitting below.
            }
            for (String piece : text.split("[\\r\\n,]+")) {
                String value = piece.trim();
                if (!value.isBlank()) {
                    options.add(value);
                }
            }
        }
        return options;
    }

    private ToolResult requestToolApproval(AgentContext ctx, String toolName, String input, String summary,
                                           String permission, String pattern, String toolCallId,
                                           String visibleLanguage) throws Exception {
        PermissionApprovalRequest approval = permissionService.beginApproval(
                ctx.getProject().getProjectId(),
                ctx.getStudentId(),
                ctx.getTaskId(),
                ctx.getConversationId(),
                ctx.getSessionId(),
                toolName,
                input,
                summary,
                permission,
                pattern,
                toolCallId
        );
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", approval.getRequestId());
        payload.put("interactionType", "permission");
        payload.put("taskId", ctx.getTaskId());
        payload.put("projectId", ctx.getProject().getProjectId());
        payload.put("conversationId", ctx.getConversationId());
        payload.put("sessionId", approval.getSessionId());
        payload.put("toolName", approval.getToolName());
        payload.put("input", approval.getInput());
        payload.put("summary", approval.getSummary());
        payload.put("matchedRulePermission", approval.getMatchedRulePermission());
        payload.put("matchedRulePattern", approval.getMatchedRulePattern());
        payload.put("toolCallId", toolCallId == null ? "" : toolCallId);
        payload.put("createdAt", approval.getCreatedAt());
        return ToolResult.interactionRequired(
                this.localText(visibleLanguage, "正在等待用户审批。", "Waiting for user approval."),
                approval.getRequestId(), "permission").withInteractionPayload(payload);
    }

    static String commandApprovalIdempotencyKey(AgentContext context, String toolCallId) {
        return "command-approval:v1:" + context.getTaskId() + ":" + context.getSessionId()
                + ":agent_shell:" + toolCallId;
    }

    static String recoveredToolCallIdentity(Long taskId, int iteration, String toolName, String canonicalArguments) {
        return "recovered:v1:" + taskId + ":" + iteration + ":" + toolName + ":"
                + sha256Static(canonicalArguments == null ? "{}" : canonicalArguments);
    }

    private String nativeToolCallId(Map<String, Object> toolCallResult) {
        Object value = toolCallResult.get("toolCallId");
        if (value == null) {
            return null;
        }
        String toolCallId = value.toString().trim();
        return toolCallId.isEmpty() ? null : toolCallId;
    }

    private String recoveredToolCallId(AgentContext context, int iteration, String toolName, JsonObject arguments) {
        String canonicalArguments = arguments == null ? "{}" : GSON.toJson(arguments);
        return recoveredToolCallIdentity(context.getTaskId(), iteration, toolName, canonicalArguments);
    }

    private static String sha256Static(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String sha256(String value) {
        return sha256Static(value);
    }

    private void stopForMissingToolCallIdentity(AgentSsePublisher sse, AgentConversation conv, AgentTask task,
                                                StudentProject project, AgentStreamRequest request, AgentContext ctx,
                                                Path runLog, int iteration, String toolName, String visibleLanguage,
                                                SseEmitter emitter) throws Exception {
        String summary = this.localText(visibleLanguage, "工具调用标识不可用", "Tool call identity unavailable");
        String detail = this.localText(visibleLanguage,
                "服务提供方未返回可用于一次性审批绑定的工具调用标识，因此本次执行已安全停止。",
                "The provider did not return a tool-call identity suitable for one-time approval binding, so execution stopped safely.");
        this.failTaskAndProject(sse, conv, task, summary, detail);
        this.appendRunLog(runLog, "\n- " + detail + " Tool=`" + this.safeLogText(toolName) + "`\n");
        this.writeAgentCheckpoint(project, request, task, ctx, "tool_call_identity_missing", detail, toolName, "", runLog);
        this.streamFinal(sse, conv, this.buildStopFinal(summary, detail, project, runLog, visibleLanguage), visibleLanguage);
        this.sendEvent(sse, conv, "DONE", Map.of("message", summary, "iterations", iteration));
        emitter.complete();
    }

    private ToolResult createCommandApproval(AgentContext ctx, CommandClassification classification,
                                             int timeout, String toolCallId) {
        if (this.commandApprovalService == null || toolCallId == null || toolCallId.isBlank()) {
            return ToolResult.failed("command approval service is unavailable");
        }
        String invocationId = "agent-command:v1:" + ctx.getTaskId() + ":" + toolCallId;
        String idempotencyKey = this.commandApprovalIdempotencyKey(ctx, toolCallId);
        java.time.LocalDateTime expiresTime = LocalDateTime.now().plusMinutes(10);
        try {
            com.labex.entity.CommandApproval approval = this.commandApprovalService.createOrGet(
                    new CommandApprovalService.CreateRequest(
                            UUID.randomUUID().toString(),
                            idempotencyKey,
                            ctx.getStudentId(),
                            ctx.getProject().getProjectId(),
                            ctx.getTaskId(),
                            ctx.getConversationId(),
                            ctx.getSessionId(),
                            "agent_shell",
                            invocationId,
                            toolCallId,
                            classification.normalizedCommand().digest(),
                            classification.normalizedCommand().canonicalCommand(),
                            classification.normalizedCommand().displayCommand(),
                            classification.normalizedCommand().canonicalWorkingDirectory(),
                            "direct",
                            "timeout=" + timeout + ";longRunning=false;network="
                                    + (this.networkAccessService != null
                                    && this.networkAccessService.hasApprovedGrant(ctx.getTaskId(),
                                    classification.normalizedCommand().canonicalCommand())),
                            classification.decision().name(),
                            classification.policyVersion(),
                            expiresTime));
            return ToolResult.commandApprovalRequired(
                    "command requires a server-owned one-time approval",
                    approval.getApprovalId(),
                    approval.getDisplayCommand(),
                    classification.riskClass().name(),
                    classification.reasonCode().name(),
                    approval.getExpiresTime().toString());
        } catch (Exception exception) {
            log.warn("Unable to create command approval for task {}: {}", ctx.getTaskId(), exception.getMessage());
            return ToolResult.failed("command approval is unavailable");
        }
    }

    private boolean networkRequested(JsonObject args) {
        if (args == null || !args.has("network") || args.get("network").isJsonNull()) return false;
        try {
            return args.get("network").getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private ToolResult maybeRequestNetworkAfterFailure(AgentContext ctx, String toolName, JsonObject args,
                                                        String toolCallId, ToolResult result) {
        if (result == null || result.isSuccess() || result.isApprovalRequired()
                || result.isInteractionRequired() || !this.isCommandPolicyTool(toolName)
                || this.networkAccessService == null || this.networkRequested(args)) {
            return result;
        }
        String content = result.getContent() == null ? "" : result.getContent();
        if (!this.looksLikeNetworkFailure(content)
                || this.networkAccessService.hasOfflineRetryAttempt(ctx.getTaskId(), this.commandForGuard(toolName, args, ctx))) {
            return result;
        }
        String command = this.commandForGuard(toolName, args, ctx);
        return this.createNetworkApproval(ctx, toolName, command, toolCallId,
                "offline_failure_retry", true, toolCallId,
                "\u68c0\u6d4b\u5230\u547d\u4ee4\u5728\u79bb\u7ebf\u7f51\u7edc\u73af\u5883\u4e0b\u5931\u8d25\uff1b\u5141\u8bb8\u540e\u5c06\u4ec5\u91cd\u8bd5\u8fd9\u6761\u5b8c\u5168\u76f8\u540c\u7684\u547d\u4ee4\u4e00\u6b21\u3002\n\u5931\u8d25\u6458\u8981\uff1a"
                        + this.limitForThought(content.replaceAll("\\s+", " "), 500));
    }

    private boolean looksLikeNetworkFailure(String content) {
        String lower = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (lower.isBlank()) return false;
        String[] markers = {
                "could not resolve", "temporary failure in name resolution", "name resolution",
                "unknown host", "no such host", "getaddrinfo", "network is unreachable",
                "connection timed out", "connect timed out", "failed to connect",
                "connection reset", "unable to access", "failed to download",
                "could not download", "download failed", "proxy connect", "tls handshake timeout",
                "network is disabled", "internet is disabled"
        };
        for (String marker : markers) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private ToolResult createNetworkApproval(AgentContext ctx, String toolName, String request, String toolCallId,
                                             String requestKind, boolean retryable, String attemptKey, String summary) {
        if (this.networkAccessService == null || ctx.getTaskId() == null) {
            return ToolResult.failed("network approval service is unavailable");
        }
        try {
            NetworkAccessService.NetworkAccessRequest approval = this.networkAccessService.begin(
                    ctx.getStudentId(), ctx.getProject().getProjectId(), ctx.getTaskId(), ctx.getConversationId(),
                    ctx.getSessionId(), toolName, request, summary, requestKind, retryable, attemptKey,
                    toolCallId, this.networkAccessService.domainsFor(toolName, request));
            LinkedHashMap<String, Object> event = new LinkedHashMap<>(approval.payload());
            event.put("requestId", approval.requestId());
            event.put("taskId", ctx.getTaskId());
            event.put("sessionId", ctx.getSessionId());
            event.put("toolCallId", toolCallId);
            return ToolResult.interactionRequired("\u7b49\u5f85\u7528\u6237\u6279\u51c6\u7f51\u7edc\u8bbf\u95ee", approval.requestId(), "network")
                    .withInteractionPayload(event);
        } catch (Exception exception) {
            log.warn("Unable to create network approval for task {}: {}", ctx.getTaskId(), exception.getMessage());
            return ToolResult.failed("network approval is unavailable");
        }
    }

    private String networkSummary() {
        return "Agent \u8bf7\u6c42\u8bbf\u95ee\u7f51\u7edc\u4ee5\u5b8c\u6210\u5f53\u524d\u547d\u4ee4";
    }

    private CommandClassification commandClassification(String toolName, JsonObject args, AgentContext context) {
        String command = this.commandForGuard(toolName, args, context);
        String workingDirectory = this.commandWorkingDirectoryForArgs(toolName, context.getWorkspaceRoot(), args);
        if (command.isBlank()) return null;
        int timeout = this.commandTimeout(toolName, args);
        return this.commandClassifier.classify(new CommandRequest(
                command, "direct", workingDirectory, timeout, false, false, "agent-worker"));
    }

    private String commandForGuard(String toolName, JsonObject args, AgentContext context) {
        if ("run_tests".equals(toolName)) {
            VerificationStrategy strategy = context.isEnvironmentRecovery()
                    ? this.recoveryProperties.getVerificationStrategy()
                    : this.verificationStrategy(args);
            return String.join(" ", TestCommandResolver.resolveProject(context.getWorkspaceRoot(),
                    strategy, this.recoveryProperties.getFallbackVerificationStrategy()).command());
        }
        return this.permissionInput(toolName, args);
    }

    private VerificationStrategy verificationStrategy(JsonObject args) {
        String requested = args != null && args.has("strategy") ? args.get("strategy").getAsString() : null;
        return VerificationStrategy.parse(requested, this.recoveryProperties.getVerificationStrategy());
    }

    private String commandWorkingDirectoryForArgs(String toolName, Path workspaceRoot, JsonObject args) {
        return commandWorkingDirectory(toolName, workspaceRoot, args,
                this.recoveryProperties.getVerificationStrategy(), this.recoveryProperties.getFallbackVerificationStrategy());
    }
    /** 将固定解析出的验证项目目录绑定到审批和失败熔断指纹。 */
    static String commandWorkingDirectory(String toolName, Path workspaceRoot) {
        return commandWorkingDirectory(toolName, workspaceRoot, null);
    }

    static String commandWorkingDirectory(String toolName, Path workspaceRoot, JsonObject args) {
        return commandWorkingDirectory(toolName, workspaceRoot, args, VerificationStrategy.AUTO, null);
    }

    private static String commandWorkingDirectory(String toolName, Path workspaceRoot, JsonObject args,
                                                   VerificationStrategy defaultStrategy,
                                                   VerificationStrategy fallbackStrategy) {
        if (!"run_tests".equals(toolName) || workspaceRoot == null) return ".";
        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(
                workspaceRoot, VerificationStrategy.parse(
                        args != null && args.has("strategy") ? args.get("strategy").getAsString() : null,
                        defaultStrategy), fallbackStrategy);
        if (resolved.workingDirectory() == null || resolved.command().isEmpty()) return ".";
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path workingDirectory = resolved.workingDirectory().toAbsolutePath().normalize();
        if (!workingDirectory.startsWith(root)) return ".";
        String relative = root.relativize(workingDirectory).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private int commandTimeout(String toolName, JsonObject args) {
        int defaultTimeout = "run_tests".equals(toolName) ? 120 : 60;
        try {
            int requested = args != null && args.has("timeout_seconds")
                    ? args.get("timeout_seconds").getAsInt() : defaultTimeout;
            return Math.min(600, Math.max(1, requested));
        } catch (RuntimeException exception) {
            return defaultTimeout;
        }
    }

    private boolean isCommandPolicyTool(String name) {
        return this.isShellTool(name) || "run_tests".equals(name);
    }

    private int shellTimeout(JsonObject args) {
        try {
            int requested = args != null && args.has("timeout_seconds")
                    ? args.get("timeout_seconds").getAsInt() : 60;
            return Math.min(600, Math.max(1, requested));
        } catch (RuntimeException exception) {
            return 60;
        }
    }

    private boolean isShellTool(String name) {
        return "shell".equals(name) || "bash".equals(name);
    }

    private boolean shouldSnapshotCommandTool(String name) {
        return this.isShellTool(name);
    }

    static boolean shouldRecordSnapshotDiff(boolean snapshotCommand, ToolResult result) {
        return snapshotCommand && result != null && !result.isApprovalRequired();
    }

    private void attachSnapshotChanges(ToolResult result, List<PendingChange> changes) {
        StringBuilder diff = new StringBuilder();
        int shown = 0;
        for (PendingChange change : changes) {
            if (change.getDiff() != null && !change.getDiff().isBlank() && shown++ < 20) {
                diff.append(change.getDiff()).append('\n');
            }
        }
        if (result.getPendingChangeId() == null) {
            result.setPendingChangeId(changes.get(0).getId());
        }
        if (result.getDiff() == null || result.getDiff().isBlank()) {
            result.setDiff(this.limitForContext(diff.toString(), 60000));
        }
        String content = result.getContent() == null ? "" : result.getContent();
        result.setContent(content + "\n\n[Workspace snapshot] Recorded " + changes.size() + " file change(s). You can undo them in the Changes panel.");
    }

    private String permissionInput(String name, JsonObject args) {
        if (args == null) {
            return "";
        }
        String direct = this.firstString(args,
                "command", "cmd", "file_path", "path", "url", "query", "pattern", "name", "server");
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }
        return args.toString();
    }

    private String firstString(JsonObject args, String... keys) {
        for (String key : keys) {
            if (args.has(key) && !args.get(key).isJsonNull()) {
                String value = args.get(key).getAsString();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    static LinkedHashMap<String, Object> commandApprovalRequiredEvent(Long taskId, String sessionId,
                                                                      String toolCallId, String toolName,
                                                                      ToolResult result, String publicDisplay) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("Command approval requires a stable toolCallId");
        }
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("approvalId", result.getApprovalId());
        event.put("taskId", taskId);
        event.put("sessionId", sessionId);
        event.put("toolCallId", toolCallId);
        event.put("tool", toolName);
        event.put("displayCommand", publicDisplay);
        event.put("riskLevel", result.getApprovalRiskLevel());
        event.put("reasonCode", result.getApprovalReasonCode());
        event.put("expiresTime", result.getApprovalExpiresTime());
        event.put("resumeAgentLoop", true);
        return event;
    }
    private void stopForCommandApproval(AgentSsePublisher sse, AgentConversation conv, AgentTask task,
                                        StudentProject project, AgentStreamRequest request, AgentContext ctx,
                                        Path runLog, int iteration, String toolCallId, String toolName, ToolResult result,
                                        String visibleLanguage, SseEmitter emitter) throws Exception {
        String displayCommand = result.getApprovalDisplayCommand() == null ? "<redacted>"
                : result.getApprovalDisplayCommand();
        String summary = this.localText(visibleLanguage, "等待一次性命令批准", "Awaiting one-time command approval");
        String detail = this.localText(visibleLanguage,
                "命令已安全保存，批准后只会执行一次；命令结果会作为上下文恢复当前 Agent 任务。",
                "The command is stored safely and will execute at most once after approval; its result will resume the current Agent task as durable context.");
        this.taskService.updateTask(task.getTaskId(), "waiting_approval", summary, detail,
                AgentRunTransitionKey.forPause(task.getTaskId(), "command", result.getApprovalId(),
                        "waiting_approval", summary, detail));
        String publicDisplay = CommandRedactor.redact(displayCommand);
        this.appendRunLog(runLog, "\n## Command approval state\n\n- Task status: `waiting_approval`\n- Approval ID: `"
                + this.safeLogText(result.getApprovalId()) + "`\n- Display: `"
                + this.safeLogText(publicDisplay) + "`\n- Original SSE: `completed while task remains durable`\n- Continuation: `approval decision -> one-time execution -> same task resume`\n");
        LinkedHashMap<String, Object> event = commandApprovalRequiredEvent(
                task.getTaskId(), ctx.getSessionId(), toolCallId, toolName, result, publicDisplay);
        this.sendEvent(sse, conv, "COMMAND_APPROVAL_REQUIRED", event);
        this.sendEvent(sse, conv, "TASK_PAUSED", Map.of(
                "taskId", task.getTaskId(),
                "taskStatus", "waiting_approval",
                "reason", "command_approval",
                "message", summary,
                "detail", detail,
                "resumeAgentLoop", true));
        this.writeAgentCheckpoint(project, request, task, ctx, "waiting_approval", detail, toolName,
                "approvalId=" + result.getApprovalId() + "; displayCommand=" + publicDisplay, runLog);
        // The request transport closes here, but the durable task is paused rather than terminal.
        // Do not send FINAL/DONE: those events are rendered as a completed conversation by the client.
        emitter.complete();
    }

    private JsonObject publicToolArguments(String toolName, JsonObject arguments) {
        if (!this.isCommandPolicyTool(toolName)) {
            return arguments;
        }
        JsonObject publicArguments = new JsonObject();
        publicArguments.addProperty("command", "<redacted; approval required for mutating commands>");
        if ("run_tests".equals(this.safeTool(toolName)) && arguments != null && arguments.has("strategy")) {
            publicArguments.add("strategy", arguments.get("strategy"));
        }
        if (arguments != null && arguments.has("timeout_seconds")) {
            publicArguments.add("timeout_seconds", arguments.get("timeout_seconds"));
        }
        return publicArguments;
    }

    private void sendObserve(AgentSsePublisher sse, AgentConversation conv, int i, String tn, ToolResult r, Long tid) throws Exception {
        LinkedHashMap<String, Object> o = new LinkedHashMap<String, Object>();
        if (r == null) {
            r = ToolResult.failed("Tool returned no result");
        }
        String rawResult = r.getContent() == null ? "" : r.getContent();
        String content = this.contextManager.summarizeObservation(tn, rawResult, r.isSuccess());
        String modelProjection = this.compactToolResultForModel(tn, r);
        o.put("iteration", i);
        o.put("tool", tn);
        o.put("success", r.isSuccess());
        o.put("content", content);
        o.put("resultChars", rawResult.length());
        o.put("modelProjectionChars", modelProjection.length());
        o.put("modelProjectionTruncated", modelProjection.length() < rawResult.length());
        if ("create_plan".equals(tn) || "plan".equals(tn)) {
            o.put("plan", content);
        }
        if (r.getDiff() != null) {
            o.put("diff", r.getDiff());
        }
        if (r.getPendingChangeId() != null) {
            o.put("pendingChangeId", r.getPendingChangeId());
        }
        o.put("taskId", tid);
        o.put("summary", r.isSuccess() ? "Observed result from " + tn : "Tool failed: " + tn);
        this.sendEvent(sse, conv, "OBSERVE", o);
    }

    private String cleanModelOutput(String content) {
        String safe = InternalReasoningBoundary.stripVisible(content);
        safe = safe.replaceAll("</?end_turn\s*/?>", "");
        safe = safe.replaceAll("(?s)<(?:minimax:)?invoke[^>]*>.*?</(?:minimax:)?invoke>", "");
        safe = safe.replaceAll("<(?:minimax:)?invoke[^>]*/?>", "");
        safe = safe.replaceAll("(?s)<(?:minimax:)?tool_call[^>]*>.*", "");
        return safe.trim();
    }

    private boolean isModelTimeoutError(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("read timed out")
                || lower.contains("response timed out")
                || lower.contains("model service timed out")
                || lower.contains("读取超时")
                || lower.contains("响应超时");
    }

    private boolean isRecoverableModelError(String message) {
        if (message == null || isModelTimeoutError(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("http 429") || lower.contains("rate limit") || lower.contains("too many requests")) {
            return false;
        }
        return lower.contains("temporarily unavailable") || lower.contains("connection reset") || lower.contains("handshake") || lower.contains("remote host terminated") || lower.contains("connection aborted") || lower.contains("connection closed") || lower.contains("stream ended before terminal event") || lower.contains("ssl") || lower.contains("tls") || lower.contains("eof") || lower.contains("503") || lower.contains("502") || lower.contains("504");
    }

    private String extractThinking(String c) {
        return this.cleanModelOutput(c);
    }

    private boolean isPrematureFinal(String text, AgentContext ctx) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String normalized = text.trim().replaceAll("\\s+", "");
        if (normalized.matches("^(next step )?operation complete[.!?]*$")) { return true; }
        if (normalized.matches("^task complete[.!?]*$")) { return true; }
        if (normalized.matches("^done[.!?]*$")) { return true; }
        if (normalized.matches("^(ok|okay|OK|good|great|done|finished).*")) { return true; }
        if (normalized.length() < 50 && ctx != null && this.hasOpenPlan(ctx)) {
            return true;
        }
        if (ctx != null && this.hasOpenPlan(ctx)) {
            if (!(text.contains("##") || text.contains("complete") || text.contains("modified") || text.contains("file") || text.contains("Summary"))) {
                return true;
            }
            if (text.length() < 80 && !text.contains("## Summary")) {
                return true;
            }
        }
        return false;
    }

    static boolean shouldRejectFinalReply(String userRequest, String finalText) {
        if (explicitlyRequestsShortReply(userRequest)) {
            return false;
        }
        if (finalText == null || finalText.isBlank()) {
            return true;
        }
        boolean tooShort = finalText.length() < 80;
        boolean noStructure = !finalText.contains("##") && !finalText.contains("**") && !finalText.contains("- ");
        boolean noSubstance = !containsFinalSubstance(finalText);
        return (tooShort && noSubstance) || (noStructure && noSubstance);
    }

    private static boolean explicitlyRequestsShortReply(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) {
            return false;
        }
        String lower = userRequest.toLowerCase(Locale.ROOT);
        return List.of(
                "\u53ea\u56de\u590d", "\u4ec5\u56de\u590d", "\u53ea\u56de\u7b54", "\u4ec5\u56de\u7b54",
                "\u7b80\u77ed\u56de\u590d", "\u4e00\u53e5\u8bdd\u56de\u590d",
                "reply only", "respond only", "answer only",
                "only reply", "only respond", "only answer", "one-word", "one word")
                .stream()
                .anyMatch(lower::contains);
    }

    private static boolean containsFinalSubstance(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : List.of(
                "edit", "complete", "file", "verify", "passed", "result", "modified", "summary",
                "完成", "已", "修改", "创建", "文件", "验证", "校验", "通过", "结果", "总结", "风险")) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOpenPlan(AgentContext ctx) {
        if (ctx == null || ctx.getTaskId() == null) {
            return false;
        }
        this.requireRunPlanService().load(ctx.getTaskId()).applyTo(ctx);
        if (ctx.getPlan() == null || ctx.getPlan().isEmpty()) {
            return false;
        }
        return ctx.getPlan().stream().anyMatch(item -> !item.isCompleted());
    }

    private String buildContinuationInstruction(AgentContext ctx) {
        String plan;
        String string = plan = ctx == null ? "" : ctx.getPlanSummary();
        if (plan == null || plan.isBlank()) {
            return "Task not started! Do not output plain text ending. Create a plan with create_plan first, then execute step by step with tools.";
        }
        return "Task not done! Do not output plain text ending. Current plan:\n" + plan + "\nCall tools to execute next step. Only output final summary after all tasks complete and verified.";
    }

    private String buildRecoverableErrorGuidance(String errMsg, int retryCount, long delayMs) {
        return "Model connection recoverable error (attempt " + retryCount + "): " + this.limitForThought(errMsg, 180) + ". This usually means cloud handshake, proxy, TLS or temporary link interruption, not project code failure. Will wait " + delayMs + "ms then continue from existing conversation, task plan and this round's log. Will first confirm which tool steps succeeded, then resume from next unfinished action. If same step fails again, will narrow tool scope or re-read project state.";
    }

    private List<ToolDefinition> selectToolDefinitions(Integer studentId, String mode, AgentModelConfig modelConfig) {
        boolean imageInputEnabled = modelConfig != null && Integer.valueOf(1).equals(modelConfig.getImageInputEnabled());
        boolean mcpEnabled = this.toolRegistry.getDynamicToolCount() > 0;
        if (!mcpEnabled && this.mcpServerService != null) {
            try {
                mcpEnabled = !this.mcpServerService.listEnabled(studentId).isEmpty();
            } catch (RuntimeException lookupFailure) {
                log.warn("Unable to resolve MCP capability for student {}: {}", studentId, lookupFailure.getMessage());
            }
        }
        // Web Search / Fetch 保持现有启用行为；这里只收敛未配置的图片和 MCP 能力。
        ToolSelectionPolicy.Capabilities capabilities = new ToolSelectionPolicy.Capabilities(
                imageInputEnabled, mcpEnabled, true, true);
        return this.toolSelectionPolicy.select(this.toolRegistry, mode, capabilities);
    }

    private String buildToolDefinitions(Collection<ToolDefinition> definitions) {
        if (definitions == null) return "";
        return definitions.stream()
                .map(definition -> "- " + definition.getName() + ": " + definition.getDescription())
                .collect(Collectors.joining("\n"));
    }

    // ==================== 上下文窗口管理（参考 OpenCode 的 compaction 机制） ====================

    /** 检测是否是上下文溢出错误 */
    private boolean isContextOverflowError(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("prompt is too long")
            || lower.contains("exceeds the context window")
            || lower.contains("context_length_exceeded")
            || lower.contains("context window")
            || lower.contains("maximum context length")
            || lower.contains("token limit")
            || (lower.contains("400") && lower.contains("too long"));
    }

    /** 估算文本的 token 数 */
    static boolean hasContextCompactionProgress(int tokensBeforeCompaction, int tokensAfterCompaction) {
        return tokensAfterCompaction < tokensBeforeCompaction;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 3);
    }

    /** 估算消息列表的总 token 数 */
    private int estimateMessagesTokens(List<Map<String, Object>> messages, String sysPrompt) {
        int total = estimateTokens(sysPrompt);
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (content instanceof String s) total += estimateTokens(s);
        }
        return total;
    }

    /** Provider 请求、预算与 admission 只读取 durable transcript，缺失运行身份时明确失败。 */
    List<Map<String, Object>> providerMessagesForBudget(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalStateException("Provider projection requires a positive durable taskId");
        }
        return this.requireTranscriptProjectionService().loadProviderMessages(taskId);
    }

    private AgentCompactionService requireCompactionService() {
        if (this.compactionService == null) {
            throw new IllegalStateException("Durable compaction service is unavailable");
        }
        return this.compactionService;
    }

    private AgentRunPlanService requireRunPlanService() {
        return requireRuntimeDependency(this.runPlanService, "runPlanService");
    }

    private AgentRunTranscriptService requireTranscriptService() {
        if (this.transcriptService == null) {
            throw new IllegalStateException("Durable Provider transcript service is unavailable");
        }
        return this.transcriptService;
    }

    private AgentTranscriptProjectionService requireTranscriptProjectionService() {
        if (this.transcriptProjectionService == null) {
            throw new IllegalStateException("Durable Provider transcript projector is unavailable");
        }
        return this.transcriptProjectionService;
    }

    ContextAdmissionDecision evaluateContextAdmission(AgentModelConfig modelConfig,
                                                        String systemPrompt,
                                                        List<Map<String, Object>> tools,
                                                        ContextUsageEstimator.PromptContext promptContext,
                                                        List<Map<String, Object>> messages) {
        if (modelConfig == null) return null;
        if (modelConfig.getContextWindowTokens() == null || modelConfig.getContextWindowTokens() <= 0) {
            ContextBudgetBreakdown missing = this.contextAdmissionService.breakdown(
                    Map.of(), 0, Math.max(0, modelConfig.getMaxTokens() == null ? 0 : modelConfig.getMaxTokens()), 0);
            return new ContextAdmissionDecision(ContextAdmissionDecision.Action.BLOCK_STATIC_OVERFLOW, false,
                    "context_window_unconfigured",
                    "当前模型没有配置上下文窗口，系统不会使用宽松默认值发送不可控请求。",
                    missing, List.of("在模型配置中填写真实 contextWindowTokens", "切换到已配置上下文窗口的模型"));
        }
        if (modelConfig.getMaxTokens() == null || modelConfig.getMaxTokens() <= 0
                || modelConfig.getMaxTokens() >= modelConfig.getContextWindowTokens()) {
            ContextBudgetBreakdown invalid = this.contextAdmissionService.breakdown(
                    Map.of(), modelConfig.getContextWindowTokens(),
                    Math.max(0, modelConfig.getMaxTokens() == null ? 0 : modelConfig.getMaxTokens()), 0);
            return new ContextAdmissionDecision(ContextAdmissionDecision.Action.BLOCK_STATIC_OVERFLOW, false,
                    "output_reserve_invalid",
                    "当前模型的输出 token 配置无效，无法计算安全输入容量。",
                    invalid, List.of("将 maxTokens 配置为小于 contextWindowTokens 的正整数"));
        }
        if (this.contextUsageEstimator == null) return null;
        Optional<ContextWindowPolicy> policy = ContextWindowPolicy.from(modelConfig);
        if (policy.isEmpty()) return null;
        Map<String, Integer> categories = this.contextUsageEstimator.estimateCategories(
                systemPrompt, tools, promptContext, messages);
        ContextWindowPolicy value = policy.orElseThrow();
        ContextBudgetBreakdown breakdown = this.contextAdmissionService.breakdown(
                categories, modelConfig.getContextWindowTokens(), modelConfig.getMaxTokens(), value.softLimitTokens());
        // 可裁剪和自动压缩已经由 manageContextBeforeModel 执行；这里是 Provider 前的最终硬门禁。
        return this.contextAdmissionService.decide(breakdown, false, false);
    }

    private void stopForContextLimit(AgentSsePublisher sse,
                                     AgentConversation conversation,
                                     AgentTask task,
                                     StudentProject project,
                                     AgentStreamRequest request,
                                     AgentContext context,
                                     Path runLog,
                                     ContextAdmissionDecision decision,
                                     int iteration,
                                     String visibleLanguage,
                                     SseEmitter emitter) throws Exception {
        String title = this.localText(visibleLanguage, "上下文配置阻塞", "Context configuration blocked");
        String detail = decision.message();
        this.taskService.waitForEnvironment(task.getTaskId(), title, detail, decision.reasonCode());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getTaskId());
        payload.put("iteration", iteration);
        payload.put("action", decision.action().name());
        payload.put("reasonCode", decision.reasonCode());
        payload.put("message", detail);
        payload.put("taskStatus", "waiting_environment");
        payload.put("manualRetryRequired", true);
        payload.put("remediation", decision.remediation());
        payload.put("budget", decision.breakdown().toPayload());
        this.appendRunLog(runLog, "\n- Context admission blocked provider invocation: `"
                + this.safeLogText(decision.reasonCode()) + "`\n");
        this.sendEvent(sse, conversation, "CONTEXT_LIMIT_BLOCKED", payload);
        this.writeAgentCheckpoint(project, request, task, context, "waiting_environment", detail, "", "", runLog);
        this.sendEvent(sse, conversation, "TASK_PAUSED", Map.of(
                "message", title,
                "detail", detail,
                "iterations", iteration,
                "taskId", task.getTaskId(),
                "taskStatus", "waiting_environment",
                "reason", "context_limit",
                "resumeAgentLoop", false,
                "manualRetryRequired", true));
        // 只关闭当前 HTTP 传输；上下文阻塞任务保持可恢复等待态。
        emitter.complete();
    }

    private void publishContextStatus(AgentSsePublisher sse, AgentConversation conversation,
                                      AgentStreamRequest request, LlmProvider provider,
                                      LlmProvider.LlmConfig config, AgentModelConfig modelConfig,
                                      String systemPrompt, List<Map<String, Object>> tools,
                                      ContextUsageEstimator.PromptContext promptContext,
                                      List<Map<String, Object>> messages, String trimState) {
        if (contextUsageEstimator == null || contextUsageRegistry == null || conversation == null) return;
        ContextUsageSnapshot snapshot = contextUsageEstimator.estimate(
                conversation.getConversationId(), request.getSessionId(), provider.getProviderId(), config.modelName(),
                modelConfig.getContextWindowTokens(), systemPrompt, tools, promptContext, messages, trimState);
        ContextAdmissionDecision admission = this.evaluateContextAdmission(
                modelConfig, systemPrompt, tools, promptContext, messages);
        if (admission != null) snapshot.withBudgetBreakdown(admission.breakdown());
        contextUsageRegistry.save(snapshot);
        try {
            this.sendEvent(sse, conversation, "CONTEXT_STATUS", snapshot.toPayload());
        } catch (Exception e) {
            log.debug("Unable to publish context status: {}", e.getMessage());
        }
    }

    private Map<String, Object> tokenUsagePayload(Map<String, Object> usageMap,
                                                   CacheTelemetryStatus cacheStatus,
                                                   Double cacheHitRate,
                                                   int iteration,
                                                   int totalTokens,
                                                   String conversationId,
                                                   boolean estimated) {
        Map<String, Object> payload = new LinkedHashMap<>();
        CacheTelemetryStatus effectiveStatus = cacheStatus == null
                ? CacheTelemetryStatus.NOT_REPORTED
                : cacheStatus;
        payload.put("iteration", iteration);
        payload.put("promptTokens", intUsage(usageMap, "prompt_tokens"));
        payload.put("completionTokens", intUsage(usageMap, "completion_tokens"));
        payload.put("totalTokens", totalTokens);
        payload.put("conversationTotal", this.tokenTracker.getTotalTokensByConversation(conversationId));
        payload.put("cachedTokens", intUsage(usageMap, "cached_tokens"));
        payload.put("cacheWriteTokens", intUsage(usageMap, "cache_write_tokens"));
        payload.put("cacheStatus", effectiveStatus.value());
        payload.put("cacheTelemetryReported", effectiveStatus == CacheTelemetryStatus.HIT
                || effectiveStatus == CacheTelemetryStatus.MISS
                || effectiveStatus == CacheTelemetryStatus.WRITE_ONLY);
        payload.put("cacheHitRate", cacheHitRate);
        payload.put("estimated", estimated);
        return payload;
    }

    private int intUsage(Map<String, Object> usageMap, String key) {
        if (usageMap == null || !usageMap.containsKey(key)) return 0;
        Object value = usageMap.get(key);
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private ContextManagementResult manageContextBeforeModel(String sysPrompt,
                                                             List<Map<String, Object>> tools,
                                                             ContextWindowPolicy policy,
                                                             String userRequest,
                                                             AgentContext context,
                                                             AgentSsePublisher sse,
                                                             AgentConversation conversation,
                                                             AgentModelConfig activeModelConfig,
                                                             Integer studentId,
                                                             CancellationToken cancellationToken,
                                                              long executionEpoch) throws Exception {
        if (policy == null || !policy.autoCompactionEnabled()) {
            return ContextManagementResult.none();
        }
        this.requireTranscriptProjectionService();
        List<Map<String, Object>> budgetMessages = this.providerMessagesForBudget(context == null ? null : context.getTaskId());
        int estimatedTokens = this.requestTokenEstimator.estimate(sysPrompt, tools, budgetMessages,
                activeModelConfig.getContextWindowTokens(), activeModelConfig.getMaxTokens()).inputTokens();
        // Provider 预算与压缩选择都从同一个 durable projection 读取。
        ContextWindowSupervisor.Decision decision = new ContextWindowSupervisor().decide(
                policy, estimatedTokens, false);
        if (decision.action() == ContextWindowSupervisor.Action.NONE) {
            return ContextManagementResult.none();
        }
        return this.compactContextWithFallback(sysPrompt, tools, userRequest, context, sse, conversation,
                activeModelConfig, studentId, cancellationToken, policy.tailTurns(), policy.preserveRecentTokens(),
                estimatedTokens, "proactive", executionEpoch);
    }

    private ContextManagementResult compactContextWithFallback(String sysPrompt,
                                                                List<Map<String, Object>> tools,
                                                                String userRequest,
                                                                AgentContext context,
                                                                AgentSsePublisher sse,
                                                                AgentConversation conversation,
                                                                AgentModelConfig activeModelConfig,
                                                                Integer studentId,
                                                                CancellationToken cancellationToken,
                                                                int keepRecentTurns,
                                                                int preserveRecentTokens,
                                                                int tokensBefore,
                                                                String trigger,
                                                                 long executionEpoch) throws Exception {
        if (context == null || context.getTaskId() == null || context.getProject() == null) {
            throw new IllegalStateException("Durable compaction requires task, project, and execution context");
        }
        Long taskId = context.getTaskId();
        CompactionSelection selection = this.selectDurableCompaction(
                taskId, keepRecentTurns, preserveRecentTokens);
        if (!selection.changed()) {
            return ContextManagementResult.none();
        }
        AgentCompactionService compactionService = this.requireCompactionService();
        AgentRunTranscriptService transcriptService = this.requireTranscriptService();
        String previousSummary = compactionService.previousSummary(taskId);
        List<Map<String, Object>> headForSummary = this.compactionHead(selection.compactedHead(), previousSummary);
        long sourceMaxSequence = transcriptService.nextSequence(taskId) - 1L;
        AgentCompactionRecord compactionRecord = compactionService.start(new AgentCompactionService.StartRequest(
                taskId, context.getConversationId(), context.getStudentId(), context.getProject().getProjectId(),
                executionEpoch, trigger, previousSummary, selection, sourceMaxSequence, tokensBefore,
                activeModelConfig == null || activeModelConfig.getContextWindowTokens() == null
                        ? 0 : activeModelConfig.getContextWindowTokens(),
                activeModelConfig == null || activeModelConfig.getMaxTokens() == null
                        ? 0 : activeModelConfig.getMaxTokens()));
        boolean compactionTerminalized = false;
        try {
            Map<String, Object> startDetails = new LinkedHashMap<>();
            startDetails.put("keepRecentTurns", Math.max(1, keepRecentTurns));
            startDetails.put("retainedTurns", selection.retainedTurns());
            startDetails.put("tailStartIndex", selection.tailStartIndex());
            startDetails.put("sourceMaxSequence", sourceMaxSequence);
            startDetails.put("compactionEpoch", compactionRecord.getCompactionEpoch());
            this.sendEvent(sse, conversation, "COMPACTION_STARTED",
                    contextEvent(trigger, tokensBefore, tokensBefore, startDetails));

            CompactionAgent.Result modelResult = this.compactionAgent.compact(studentId, activeModelConfig,
                    headForSummary, userRequest, context, cancellationToken);
            if (this.isCompactionCancelled(modelResult, cancellationToken)) {
                compactionService.fail(compactionRecord, "Compaction cancelled");
                compactionTerminalized = true;
                InterruptedException cancellation = new InterruptedException("Compaction cancelled");
                Map<String, Object> cancellationDetails = new LinkedHashMap<>();
                cancellationDetails.put("reason", "Compaction cancelled");
                cancellationDetails.put("compactionEpoch", compactionRecord.getCompactionEpoch());
                try {
                    this.sendEvent(sse, conversation, "COMPACTION_FAILED",
                            contextEvent("cancelled", tokensBefore, tokensBefore, cancellationDetails));
                } catch (Exception projectionFailure) {
                    cancellation.addSuppressed(projectionFailure);
                }
                throw cancellation;
            }
            if (modelResult.success()) {
                List<Map<String, Object>> projected = selection.projectedWithSummary(modelResult.checkpoint());
                int afterTokens = this.estimateProviderRequestTokens(sysPrompt, tools, projected, activeModelConfig);
                if (afterTokens < tokensBefore) {
                    compactionService.complete(compactionRecord, modelResult.checkpoint(), afterTokens);
                    compactionTerminalized = true;
                    Map<String, Object> details = compactionModelDetails(modelResult);
                    details.put("compactionEpoch", compactionRecord.getCompactionEpoch());
                    this.sendCompactionSummary(sse, conversation, modelResult.checkpoint(),
                            contextEvent("model", tokensBefore, afterTokens, details));
                    this.sendEvent(sse, conversation, "COMPACTION_COMPLETED",
                            contextEvent("model", tokensBefore, afterTokens, details));
                    return new ContextManagementResult(true, "MODEL_CHECKPOINT");
                }
                modelResult = CompactionAgent.Result.failure("Model checkpoint did not reduce historical context");
            }
            Map<String, Object> modelFailure = new LinkedHashMap<>();
            modelFailure.put("reason", modelResult.reason());
            modelFailure.put("compactionEpoch", compactionRecord.getCompactionEpoch());
            this.sendEvent(sse, conversation, "COMPACTION_FAILED",
                    contextEvent("model", tokensBefore, tokensBefore, modelFailure));

            String deterministicCheckpoint = new ConversationCheckpointCompactor()
                    .checkpointForHistory(headForSummary, userRequest, context);
            if (!deterministicCheckpoint.isBlank()) {
                List<Map<String, Object>> projected = selection.projectedWithSummary(deterministicCheckpoint);
                int afterTokens = this.estimateProviderRequestTokens(sysPrompt, tools, projected, activeModelConfig);
                if (afterTokens < tokensBefore) {
                    compactionService.complete(compactionRecord, deterministicCheckpoint, afterTokens);
                    compactionTerminalized = true;
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("reason", modelResult.reason());
                    details.put("compactionEpoch", compactionRecord.getCompactionEpoch());
                    this.sendCompactionSummary(sse, conversation, deterministicCheckpoint,
                            contextEvent("deterministic_fallback", tokensBefore, afterTokens, details));
                    this.sendEvent(sse, conversation, "COMPACTION_COMPLETED",
                            contextEvent("deterministic_fallback", tokensBefore, afterTokens, details));
                    return new ContextManagementResult(true, "DETERMINISTIC_CHECKPOINT");
                }
            }
            compactionService.fail(compactionRecord, modelResult.reason());
            compactionTerminalized = true;
            return ContextManagementResult.none();
        } catch (Exception failure) {
            if (!compactionTerminalized && this.isRunningCompaction(compactionRecord)) {
                String failureReason = this.compactionExecutionFailureReason(failure);
                try {
                    compactionService.fail(compactionRecord, failureReason);
                    compactionTerminalized = true;
                    Map<String, Object> failureDetails = new LinkedHashMap<>();
                    failureDetails.put("reason", failureReason);
                    failureDetails.put("compactionEpoch", compactionRecord.getCompactionEpoch());
                    try {
                        this.sendEvent(sse, conversation, "COMPACTION_FAILED",
                                contextEvent("execution_error", tokensBefore, tokensBefore, failureDetails));
                    } catch (Exception projectionFailure) {
                        failure.addSuppressed(projectionFailure);
                    }
                } catch (Exception finalizationFailure) {
                    failure.addSuppressed(finalizationFailure);
                }
            }
            throw failure;
        }
    }

    private boolean isCompactionCancelled(CompactionAgent.Result result,
                                          CancellationToken cancellationToken) {
        return (cancellationToken != null && cancellationToken.isCancellationRequested())
                || (result != null && "Compaction cancelled".equalsIgnoreCase(result.reason()));
    }

    private boolean isRunningCompaction(AgentCompactionRecord record) {
        return record != null && "running".equalsIgnoreCase(record.getStatus());
    }

    private String compactionExecutionFailureReason(Exception failure) {
        String type = failure == null ? "Exception" : failure.getClass().getSimpleName();
        String message = failure == null ? "" : CommandRedactor.redact(failure.getMessage());
        message = message == null ? "" : message.replace('\r', ' ').replace('\n', ' ').trim();
        if (message.length() > 240) {
            message = message.substring(0, 240);
        }
        return "Compaction execution failed: " + type + (message.isBlank() ? "" : ": " + message);
    }

    CompactionSelection selectDurableCompaction(Long taskId, int keepRecentTurns,
                                                int preserveRecentTokens) {
        List<Map<String, Object>> durableMessages = this.providerMessagesForBudget(taskId);
        return CompactionSelection.select(durableMessages, keepRecentTurns,
                preserveRecentTokens, this.requestTokenEstimator);
    }

    private int estimateProviderRequestTokens(String sysPrompt,
                                              List<Map<String, Object>> tools,
                                              List<Map<String, Object>> messages,
                                              AgentModelConfig modelConfig) {
        if (modelConfig == null || modelConfig.getContextWindowTokens() == null
                || modelConfig.getMaxTokens() == null) {
            return estimateMessagesTokens(messages, sysPrompt) + estimateTokens(GSON.toJson(tools));
        }
        return this.requestTokenEstimator.estimate(sysPrompt, tools, messages,
                modelConfig.getContextWindowTokens(), modelConfig.getMaxTokens()).inputTokens();
    }

    private List<Map<String, Object>> compactionHead(List<Map<String, Object>> head,
                                                     String previousSummary) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (previousSummary != null && !previousSummary.isBlank()) {
            result.add(Map.of("role", "user", "content", previousSummary));
        }
        for (Map<String, Object> message : head == null ? List.<Map<String, Object>>of() : head) {
            Object content = message.get("content");
            String text = content instanceof String value ? value : "";
            if (!text.isBlank() && (text.equals(previousSummary)
                    || text.stripLeading().startsWith("<conversation-checkpoint>"))) {
                continue;
            }
            result.add(this.providerMessageProjector.copyMessage(message));
        }
        return result;
    }

    private boolean canReduceToolSchema(List<Map<String, Object>> tools) {
        return tools != null && tools.size() > 12;
    }

    /** 保留完成代码任务所需的核心能力，再按原顺序补足最多 12 个工具。 */
    private boolean reduceToolSchemaForOverflow(List<Map<String, Object>> tools) {
        if (!this.canReduceToolSchema(tools)) {
            return false;
        }
        List<String> priority = List.of(
                "read_file", "grep", "glob", "list_files", "search_code", "repo_map",
                "write_file", "edit_file", "apply_patch", "run_tests", "bash", "question");
        List<Map<String, Object>> reduced = new ArrayList<>();
        for (String name : priority) {
            for (Map<String, Object> tool : tools) {
                if (name.equals(this.toolSchemaName(tool)) && !reduced.contains(tool)) {
                    reduced.add(tool);
                    break;
                }
            }
        }
        for (Map<String, Object> tool : tools) {
            if (reduced.size() >= 12) break;
            if (!reduced.contains(tool)) reduced.add(tool);
        }
        if (reduced.size() >= tools.size()) {
            return false;
        }
        tools.clear();
        tools.addAll(reduced);
        return true;
    }

    private String toolSchemaName(Map<String, Object> tool) {
        if (tool == null) return "";
        Object function = tool.get("function");
        if (function instanceof Map<?, ?> functionMap) {
            Object name = functionMap.get("name");
            return name == null ? "" : String.valueOf(name);
        }
        Object name = tool.get("name");
        return name == null ? "" : String.valueOf(name);
    }
    private Map<String, Object> compactionModelDetails(CompactionAgent.Result result) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("modelConfigId", result.modelConfigId());
        details.put("modelName", result.modelName());
        details.put("dedicatedModel", result.dedicatedModelSelected());
        return details;
    }

    private Map<String, Object> contextEvent(String strategy, int beforeTokens, int afterTokens,
                                             Map<String, Object> details) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("strategy", strategy);
        event.put("tokensBefore", beforeTokens);
        event.put("tokensAfter", afterTokens);
        if (details != null) {
            event.putAll(details);
        }
        return event;
    }

    private void sendCompactionSummary(AgentSsePublisher sse, AgentConversation conversation,
                                       String checkpoint, Map<String, Object> data) throws Exception {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>(data == null ? Map.of() : data);
        payload.put("content", checkpoint);
        sse.send("COMPACTION_SUMMARY", payload);
        if (conversation != null) {
            this.conversationService.markCompacted(conversation);
        }
    }

    private record NativeToolAdmission(AgentModelTurnExecutor.NativeToolCall call,
                                       AgentToolTurnExecutor.ToolInputResolution input,
                                       JsonObject publicArguments) {
        boolean allowed() {
            return input != null && input.allowed();
        }

        JsonObject arguments() {
            return input == null ? new JsonObject() : input.arguments();
        }

        ToolResult rejection() {
            return input == null ? ToolResult.failed("Tool input was rejected.") : input.rejection();
        }

        String reasonCode() {
            return input == null ? "unknown" : input.reasonCode();
        }
    }

    private record ContextManagementResult(boolean changed, String strategy) {
        private static ContextManagementResult none() {
            return new ContextManagementResult(false, "NONE");
        }
    }

    private String readActiveFile(Integer studentId, Integer projectId, String activePath) {
        if (activePath == null || activePath.isBlank()) {
            return "";
        }
        try {
            return this.studentProjectService.readProjectFile(studentId, projectId, activePath);
        }
        catch (Exception e) {
            return "Unable to read active file: " + e.getMessage();
        }
    }

    private void sendThought(AgentSsePublisher sse, AgentConversation conv, int iteration, String summary, String content, Long taskId) throws Exception {
        String messageId = "think-" + iteration + "-" + String.valueOf(UUID.randomUUID());
        LinkedHashMap<String, Object> start = new LinkedHashMap<String, Object>();
        start.put("messageId", messageId);
        start.put("iteration", iteration);
        start.put("summary", summary);
        start.put("taskId", taskId);
        sse.send("THINK_START", start);
        String safeContent = content == null ? "" : content.trim();
        for (String delta : this.chunkThought(safeContent)) {
            sse.send("THINK_DELTA", Map.of("messageId", messageId, "delta", delta));
            Thread.sleep(35L);
        }
        LinkedHashMap<String, Object> done = new LinkedHashMap<String, Object>(start);
        done.put("content", safeContent);
        done.put("streaming", false);
        this.sendEvent(sse, conv, "THINK", done);
    }

    private List<String> chunkThought(String content) {
        ArrayList<String> chunks = new ArrayList<String>();
        if (content == null || content.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + 96);
            int sentence = -1;
            for (int i = start + 36; i < end; ++i) {
                char ch = content.charAt(i);
                if (ch != '.' && ch != ',' && ch != '\n' && ch != ';' && ch != ':' && ch != '!' && ch != '?') continue;
                sentence = i + 1;
            }
            if (sentence > start) {
                end = sentence;
            }
            chunks.add(content.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private String safeTool(String toolName) {
        return toolName == null ? "" : toolName.trim();
    }

    private Path createRunLog(StudentProject project, AgentStreamRequest request) {
        try {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String session = request.getSessionId() == null ? UUID.randomUUID().toString() : request.getSessionId();
            String safeSession = session.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path log = ProjectWorkspace.paths(project)
                    .resolveForCreate(".labex/agent-logs/" + stamp + "-" + safeSession + ".md");
            Files.createDirectories(log.getParent());
            return log;
        }
        catch (Exception e) {
            log.warn("Unable to create agent run log: {}", e.getMessage());
            return null;
        }
    }

    private void appendRunLog(Path path, String text) {
        if (path == null || text == null) {
            return;
        }
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e) {
            log.warn("Unable to append agent run log {}: {}", path, e.getMessage());
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private void appendToolExecutionStart(Path path, AgentContext context, String toolName, String toolCallId,
                                          JsonObject args) {
        this.appendRunLog(path, "\n#### Tool execution telemetry\n"
                + "- Tool: `" + this.safeLogText(toolName) + "`\n"
                + "- Tool call ID: `" + this.safeLogText(toolCallId) + "`\n"
                + "- Started at: `" + LocalDateTime.now() + "`\n"
                + "- Task: `" + context.getTaskId() + "`\n"
                + "- Arguments chars: `" + (args == null ? 0 : args.toString().length()) + "`\n"
                + "- Current phase: `tool_delegate`\n");
    }

    private void appendDiffApplyTelemetry(Path path, DiffService.ApplyTelemetry telemetry) {
        this.appendRunLog(path, "\n#### Diff apply telemetry\n\n- Phase: `"
                + this.safeLogText(telemetry.phase()) + "`\n- Timing ms: `" + telemetry.timingMs() + "`\n");
    }

    private void appendToolExecutionComplete(Path path, String toolName, String toolCallId, ToolResult result,
                                             long totalMs, long delegateMs, long beforeSnapshotMs,
                                             long afterSnapshotMs, long snapshotDiffMs, long postEditMs,
                                             long contextMs, long metricsMs) {
        this.appendRunLog(path, "- Completed at: `" + LocalDateTime.now() + "`\n"
                + "- Status: `" + (result.isSuccess() ? "success" : "failed") + "`\n"
                + "- Timing ms: total=`" + totalMs + "`, delegate=`" + delegateMs
                + "`, snapshotBefore=`" + beforeSnapshotMs + "`, snapshotAfter=`" + afterSnapshotMs
                + "`, snapshotDiff=`" + snapshotDiffMs + "`, postEdit=`" + postEditMs
                + "`, context=`" + contextMs + "`, metrics=`" + metricsMs + "`\n");
    }

    private void appendToolExecutionFailed(Path path, String toolName, String toolCallId, String phase,
                                           long totalMs, Exception failure) {
        this.appendRunLog(path, "- Failed at: `" + LocalDateTime.now() + "`\n"
                + "- Failure phase: `" + this.safeLogText(phase) + "`\n"
                + "- Total elapsed ms: `" + totalMs + "`\n"
                + "- Error type: `" + this.safeLogText(failure.getClass().getSimpleName()) + "`\n"
                + "- Error: `" + this.safeLogText(failure.getMessage()) + "`\n");
    }

    private void appendToolResult(Path path, ToolResult result) {
        if (result == null) {
            this.appendRunLog(path, "\n### Tool result\n\n- Status: no result\n");
            return;
        }
        String approvalDetail = result.isApprovalRequired()
                ? "- Approval required: `" + this.safeLogText(CommandRedactor.redact(
                        result.getApprovalDisplayCommand() == null ? "<redacted>" : result.getApprovalDisplayCommand())) + "`\n"
                : "";
        String resultStatus = result.isInteractionRequired() ? "waiting_user"
                : (result.isApprovalRequired() ? "waiting_approval" : (result.isSuccess() ? "success" : "failed"));
        this.appendRunLog(path, "\n### Tool result\n\n- Status: `" + resultStatus
                + "`\n" + approvalDetail
                + (result.getPendingChangeId() != null ? "- Change record: `" + this.safeLogText(result.getPendingChangeId()) + "`\n" : "")
                + "\n```text\n" + this.safeLogText(this.limitForContext(result.getContent(), 6000)) + "\n```\n");
    }

    private String workspaceRelativeLogPath(StudentProject project, Path logPath) {
        if (project == null || logPath == null) {
            return "";
        }
        try {
            Path root = ProjectWorkspace.paths(project).workspaceRoot();
            Path normalizedLog = logPath.toAbsolutePath().normalize();
            if (!normalizedLog.startsWith(root)) {
                return "";
            }
            return root.relativize(normalizedLog).toString().replace("\\", "/");
        }
        catch (Exception e) {
            return logPath.toString();
        }
    }

    private String safeLogText(String text) {
        return text == null ? "" : text.replace("\r", "");
    }

    private String buildStopFinal(String status, String reason, StudentProject project, Path logPath) {
        return this.buildStopFinal(status, reason, project, logPath, "en");
    }

    private String buildStopFinal(String status, String reason, StudentProject project, Path logPath, String visibleLanguage) {
        String logRef = project == null ? (logPath == null ? "" : logPath.toString()) : this.workspaceRelativeLogPath(project, logPath);
        if (this.isChineseLanguage(visibleLanguage)) {
            return "## 状态\n" + status
                    + "\n\n## 停止原因\n" + reason
                    + "\n\n## 调试日志\n"
                    + (logRef.isBlank()
                            ? "本次运行没有写入日志文件。"
                            : "已保存到 `" + logRef + "`，包含完整思考过程、工具调用、观察结果和错误信息。");
        }
        return "## Status\n" + status + "\n\n## Stop Reason\n" + reason + "\n\n## Debug Log\n" + (logRef.isBlank() ? "No log file written this run." : "Saved to `" + logRef + "`, contains full thinking process, tool calls, observations and errors.");
    }

    private String limitForThought(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String compact = text.trim().replaceAll("\\s+", " ");
        return compact.length() <= max ? compact : compact.substring(0, max) + "...";
    }

    private List<Map<String, Object>> buildToolsList(Collection<ToolDefinition> definitions) {
        if (definitions == null) return List.of();
        return definitions.stream().map(d -> {
            LinkedHashMap<String, Object> tool = new LinkedHashMap<String, Object>();
            tool.put("type", "function");
            LinkedHashMap<String, Object> fn = new LinkedHashMap<String, Object>();
            fn.put("name", d.getName());
            fn.put("description", d.getDescription());
            fn.put("parameters", d.getInputSchema());
            tool.put("function", fn);
            return tool;
        }).collect(Collectors.toList());
    }

    private void appendRemainingBatchToolResults(Long taskId,
                                                 long transcriptEpoch,
                                                 List<NativeToolAdmission> admissions,
                                                 int startIndex, String skippedReason) {
        if (admissions == null || startIndex >= admissions.size()) return;
        for (int index = Math.max(0, startIndex); index < admissions.size(); index++) {
            NativeToolAdmission nativeAdmission = admissions.get(index);
            AgentModelTurnExecutor.NativeToolCall call = nativeAdmission.call();
            String content = nativeAdmission.allowed()
                    ? skippedReason
                    : this.compactToolResultForModel(call.toolName(), nativeAdmission.rejection());
            this.appendProviderMessage(taskId, transcriptEpoch,
                    this.toolCallBatchProtocol.toolResultMessage(call,
                            "[Tool " + call.toolName() + " result]\n" + content));
        }
    }

    private void journalRemainingBatchSkipped(Long taskId,
                                              List<NativeToolAdmission> admissions,
                                              int startIndex, int iteration, String reason) {
        if (admissions == null || startIndex >= admissions.size()) return;
        for (int index = Math.max(0, startIndex); index < admissions.size(); index++) {
            NativeToolAdmission nativeAdmission = admissions.get(index);
            if (!nativeAdmission.allowed()) {
                // admission 阶段已经持久化 error Part，不能用 skipped 覆盖真实失败。
                continue;
            }
            AgentModelTurnExecutor.NativeToolCall call = nativeAdmission.call();
            this.toolCallJournalService.skipped(taskId, call.toolCallId(), call.toolName(),
                    nativeAdmission.publicArguments(), iteration, reason);
        }
    }

    private void journalToolPending(Long taskId, String toolCallId, String toolName, Object arguments, int iteration) {
        this.toolCallJournalService.pending(taskId, toolCallId, toolName, arguments, iteration);
    }

    private void journalToolRunning(Long taskId, String toolCallId, String toolName, Object arguments, int iteration) {
        this.toolCallJournalService.running(taskId, toolCallId, toolName, arguments, iteration);
    }

    private void journalToolWaitingApproval(Long taskId, String toolCallId, String toolName, Object arguments,
                                            int iteration, String approvalId) {
        this.toolCallJournalService.waitingApproval(taskId, toolCallId, toolName, arguments, iteration, approvalId);
    }

    private void publishUserQuestion(AgentSsePublisher sse, AgentConversation conv, ToolResult result) throws Exception {
        if (result == null || !result.isInteractionRequired() || result.getInteractionPayload() == null
                || result.getInteractionPayload().isEmpty()) {
            return;
        }
        String eventType = switch (String.valueOf(result.getInteractionType())) {
            case "permission" -> "PERMISSION_ASK";
            case "network" -> "NETWORK_ACCESS_ASK";
            default -> "USER_QUESTION";
        };
        this.sendEvent(sse, conv, eventType, result.getInteractionPayload());
    }

    /** 持久化交互暂停时不发送 FINAL/DONE，由前端保留 task/cursor 等待恢复。 */
    private void publishInteractionPause(AgentSsePublisher sse, AgentConversation conv, Long taskId,
                                         ToolResult result, AgentInteractionPauser.Pause pause,
                                         int iteration) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("state", pause.state());
        payload.put("reason", result == null ? "interaction" : String.valueOf(result.getInteractionType()));
        payload.put("requestId", result == null ? "" : String.valueOf(result.getInteractionRequestId()));
        payload.put("message", pause.title());
        payload.put("detail", pause.detail());
        payload.put("iterations", iteration);
        payload.put("resumeAgentLoop", true);
        this.sendEvent(sse, conv, "TASK_PAUSED", payload);
    }

    private void journalToolResult(Long taskId, String toolCallId, String toolName, Object arguments,
                                   int iteration, ToolResult result) {
        if (result != null && result.isInteractionRequired()) {
            this.journalToolWaitingInteraction(taskId, toolCallId, toolName, arguments, iteration,
                    result.getInteractionRequestId(), result.getInteractionType(), result.getContent(),
                    result.getInteractionPayload());
            return;
        }
        this.journalToolFinished(taskId, toolCallId, toolName, arguments, iteration, result);
    }

    private void journalToolWaitingInteraction(Long taskId, String toolCallId, String toolName, Object arguments,
                                                int iteration, String requestId, String interactionType, String detail,
                                                Map<String, Object> interactionPayload) {
        this.toolCallJournalService.waitingInteraction(taskId, toolCallId, toolName, arguments, iteration,
                requestId, interactionType, detail, interactionPayload);
    }

    private String interactionWaitingState(ToolResult result) {
        String type = result == null ? "" : String.valueOf(result.getInteractionType());
        return "permission".equals(type) || "network".equals(type) ? "waiting_approval" : "waiting_user";
    }

    private void journalToolBlocked(Long taskId, String toolCallId, String toolName, Object arguments,
                                    int iteration, String detail) {
        this.toolCallJournalService.blocked(taskId, toolCallId, toolName, arguments, iteration, detail);
    }

    private void journalToolFinished(Long taskId, String toolCallId, String toolName, Object arguments,
                                     int iteration, ToolResult result) {
        String detail = result == null ? "" : result.getContent();
        if (result != null && result.isSuccess()) {
            this.toolCallJournalService.completed(taskId, toolCallId, toolName, arguments, iteration, detail);
        } else {
            this.toolCallJournalService.failed(taskId, toolCallId, toolName, arguments, iteration, detail);
        }
    }

    private void sendEvent(AgentSsePublisher sse, AgentConversation conv, String type, Object data) throws Exception {
        sse.send(type, data);
    }

    /** 计划服务已经提交事件；当前连接只能发送对应 sequence，不能再次追加事件。 */
    private void projectPersistedPlanUpdate(AgentSsePublisher sse, AgentContext context) {
        Long sequence = context == null ? null : context.consumePlanEventSequence();
        if (sequence == null || sequence <= 0L) {
            return;
        }
        try {
            sse.sendPersisted(sequence, "PLAN_UPDATE", context.getPlanEventPayload());
        } catch (IOException ignored) {
            // SSE 断开不影响已经在同一事务中提交的计划与事件。
        }
    }

    private boolean isPlanTool(String toolName) {
        return "create_plan".equals(toolName) || "plan".equals(toolName)
                || "todo_write".equals(toolName) || "todowrite".equals(toolName);
    }

    /** 将生命周期已持久化的事件投影到当前连接，不能再次追加同名运行事件。 */
    private void failTaskAndProject(AgentSsePublisher sse, AgentConversation conv, AgentTask task,
                                    String currentStep, String summary) throws Exception {
        AgentRunEvent failedEvent = this.taskService.updateTask(
                task.getTaskId(), "failed", currentStep, summary);
        this.sendPersistedEvent(sse, conv, failedEvent);
    }

    private void sendPersistedEvent(AgentSsePublisher sse, AgentConversation conv, AgentRunEvent event) throws Exception {
        if (event == null || event.getSequenceNumber() == null || event.getEventType() == null) {
            throw new IllegalStateException("A persisted agent run event is required for live projection");
        }
        Object data = event.getPayload() == null || event.getPayload().isBlank()
                ? Map.of()
                : GSON.fromJson(event.getPayload(), Object.class);
        try {
            sse.sendPersisted(event.getSequenceNumber(), event.getEventType(), data);
        } catch (java.io.IOException ignored) {
            // 浏览器断线只影响观察者，不能把已经提交的终态重新解释成执行失败。
        }
    }

    private void streamFinal(AgentSsePublisher sse, AgentConversation conv, String text) throws Exception {
        this.streamFinal(sse, conv, text, "en");
    }

    private void streamFinal(AgentSsePublisher sse, AgentConversation conv, String text, String visibleLanguage) throws Exception {
        String visibleText = InternalReasoningBoundary.stripVisible(text);
        if (!visibleText.isEmpty()) {
            sse.sendTransient("FINAL_DELTA", Map.of("delta", visibleText));
        }
        this.sendEvent(sse, conv, "FINAL", Map.of("content", visibleText, "summary", this.finalResponseSummary(visibleLanguage)));
    }


    private String visibleLanguage(String userMessage) {
        return this.visibleLanguage(userMessage, null);
    }

    private String visibleLanguage(String userMessage, String previousContext) {
        String previousLanguage = VisibleLanguageResolver.resolve(previousContext, null).code();
        return VisibleLanguageResolver.resolve(userMessage, previousLanguage).code();
    }

    private boolean isChineseLanguage(String visibleLanguage) {
        return VisibleLanguageResolver.isChinese(visibleLanguage);
    }

    private String localText(String visibleLanguage, String zh, String en) {
        return this.isChineseLanguage(visibleLanguage) ? zh : en;
    }

    private String finalResponseSummary(String visibleLanguage) {
        return this.localText(visibleLanguage, "已生成最终回答", "Generated final response");
    }

    private String buildVisibleLanguagePolicy(String visibleLanguage) {
        VisibleLanguageResolver.Language language = VisibleLanguageResolver.language(visibleLanguage);
        return """
<response_language>
Detected user-visible language for this turn: %s.
Use %s for all user-visible thinking, tool summaries, status updates, clarifying questions, option labels, and final answers.
Keep code, file paths, commands, package names, API names, and raw error text unchanged when needed.
</response_language>""".formatted(language.displayName(), language.displayName());
    }


    private void applyBackgroundWorkspace(AgentContext context, StudentProject project, AgentTask task) {
        if (task == null || task.getBackgroundWorktree() == null || task.getBackgroundWorktree().isBlank()) return;
        context.setWorkspaceRoot(BackgroundRunWorkspaceResolver.resolve(
                ProjectWorkspace.paths(project).workspaceRoot(), task.getBackgroundWorktree()));
    }

    private String buildModePolicy(String mode) {
        if ("plan".equals(mode)) {
            return """
<agent_mode name="plan">
You are in planning mode. Do not edit files, write files, apply patches, or run shell commands.
Use read/search/project overview tools to understand the workspace, then produce a concrete implementation plan.
</agent_mode>""";
        }
        if ("explore".equals(mode)) {
            return """
<agent_mode name="explore">
You are in exploration mode. Prefer fast read, grep, glob, project overview, web search, and diagnostics tools.
Do not modify workspace files. Return findings, options, and exact file references.
</agent_mode>""";
        }
        return """
<agent_mode name="build">
You are in build mode. You may modify files when needed, but ask for approval when a permission prompt is raised.
Keep changes scoped, verify with available checks, and report remaining risk clearly.
</agent_mode>""";
    }

    private String buildContextMessage(String projectRules, String memoryContext, String sessionContext, String recentRunLog, String checkpoint, String globalSkills, String mcpContext) {
        StringBuilder builder = new StringBuilder();
        if (projectRules != null && !projectRules.isBlank()) {
            builder.append("<project_rules file=\"Labex.md\">\n").append(this.limitForContext(projectRules, 10000)).append("\n</project_rules>\n\n");
        }
        if (globalSkills != null && !globalSkills.isBlank()) {
            builder.append(this.limitForContext(globalSkills, 16000)).append("\n");
        }
        if (mcpContext != null && !mcpContext.isBlank()) {
            builder.append(this.limitForContext(mcpContext, 12000)).append("\n");
        }
        if (memoryContext != null && !memoryContext.isBlank()) {
            builder.append("<conversation_memory isolated=\"true\">\n").append(this.limitForContext(memoryContext, 16000)).append("\n</conversation_memory>\n\n");
        }
        if (recentRunLog != null && !recentRunLog.isBlank()) {
            builder.append("<latest_agent_run_log purpose=\"resume_previous_work\">\n").append(this.limitForContext(recentRunLog, 12000)).append("\n</latest_agent_run_log>\n\n");
        }
        if (checkpoint != null && !checkpoint.isBlank()) {
            builder.append("<agent_checkpoint purpose=\"resume_after_disconnect_or_failure\">\n").append(this.limitForContext(checkpoint, 12000)).append("\n</agent_checkpoint>\n\n");
        }
        builder.append("<session_context>\n").append(this.limitForContext(sessionContext, 60000)).append("\n</session_context>");
        return builder.toString();
    }

    private String readProjectRules(Integer studentId, Integer projectId) {
        try {
            return this.studentProjectService.readProjectFile(studentId, projectId, "Labex.md");
        }
        catch (Exception ignored) {
            return "";
        }
    }

    private String readProjectIndex(Integer studentId, Integer projectId) {
        try {
            return this.studentProjectService.readProjectFile(studentId, projectId, ".labex/project-index.md");
        }
        catch (Exception ignored) {
            return "";
        }
    }

    private void writeAgentCheckpoint(StudentProject project, AgentStreamRequest request, AgentTask task,
                                      AgentContext context, String status, String note, String lastTool,
                                      String lastResult, Path runLog) {
        try {
            this.checkpointStore.save(project, request, task, context, status, note, lastTool, lastResult, runLog);
        } catch (Exception exception) {
            log.warn("Unable to write task-scoped agent checkpoint: {}", exception.getMessage());
        }
    }

    private String compactToolResultForCheckpoint(String toolName, ToolResult result) {
        if (result == null) {
            return "Tool returned no result.";
        }
        String content = result.getContent() == null ? "" : result.getContent();
        String approval = result.isApprovalRequired()
                ? "\napprovalId=" + (result.getApprovalId() == null ? "" : result.getApprovalId())
                + "\ndisplayCommand=" + CommandRedactor.redact(
                        result.getApprovalDisplayCommand() == null ? "<redacted>" : result.getApprovalDisplayCommand())
                : "";
        return "success=" + result.isSuccess()
                + (result.getPendingChangeId() == null ? "" : "\npendingChangeId=" + result.getPendingChangeId())
                + approval + "\n"
                + this.contextManager.compactCheckpointResult(this.safeTool(toolName), content, result.isSuccess());
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private String limitForContext(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n...context truncated...";
    }

    private String compactToolResultForModel(String toolName, ToolResult result) {
        if (result == null) {
            return "";
        }
        String content = result.getContent() == null ? "" : result.getContent();
        String compact = String.valueOf(this.contextManager.compactToolResult(this.safeTool(toolName), content, result.isSuccess()));
        if (result.getPendingChangeId() != null) {
            compact = compact + "\n\nFile changes applied automatically, can revert via Changes panel.";
        }
        return compact;
    }

    private String extractToolNameFromContent(String content) {
        if (content == null) {
            return null;
        }
        String s = "name=\"";
        int i = content.indexOf(s);
        if (i < 0) {
            return null;
        }
        int e = content.indexOf("\"", i += 6);
        return e > i ? content.substring(i, e) : null;
    }

    private String extractToolArgsFromContent(String content) {
        int ne;
        int ni;
        int ps;
        if (content == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        int pos = 0;
        String endTag = "</parameter>";
        while ((ps = content.indexOf("<parameter", pos)) >= 0 && (ni = content.indexOf("name=\"", ps)) >= 0 && (ne = content.indexOf("\"", ni += 6)) >= 0) {
            String pn = content.substring(ni, ne);
            int gt = content.indexOf(">", ne);
            if (gt < 0) break;
            int vs = gt + 1;
            int ve = content.indexOf(endTag, vs);
            if (ve < 0) {
                ve = content.length();
            }
            String pv = content.substring(vs, ve).trim();
            if (!first) {
                sb.append(",");
            }
            sb.append("\"").append(this.escapeJson(pn)).append("\":\"").append(this.escapeJson(pv)).append("\"");
            first = false;
            pos = ve + endTag.length();
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
