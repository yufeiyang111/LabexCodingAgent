package com.labex.labexagent.runtime;

import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfileResolver;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.entity.AgentRunConfigSnapshot;
import com.labex.labexagent.projectconfig.AgentRunConfigSnapshotService;
import com.labex.labexagent.attachment.AgentInputAttachmentService;
import com.labex.labexagent.context.AgentCompactionRecord;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.context.AgentRequestTokenEstimator;
import com.labex.labexagent.context.CompactionSelection;
import com.labex.labexagent.context.ContextOverflowRecoveryPolicy;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.commandsecurity.CommandApprovalService;
import com.labex.labexagent.commandsecurity.CommandClassification;
import com.labex.labexagent.commandsecurity.CommandReasonCode;
import com.labex.labexagent.commandsecurity.CommandClassifier;
import com.labex.labexagent.commandsecurity.CommandDecision;
import com.labex.labexagent.commandsecurity.CommandRedactor;
import com.labex.labexagent.commandsecurity.CommandRequest;
import com.labex.labexagent.commandsecurity.DirectCommandWorkingDirectory;
import com.labex.labexagent.commandsecurity.TestCommandResolver;
import com.labex.labexagent.commandsecurity.VerificationStrategy;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.diff.GitSnapshotService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.migration.AgentLegacyCheckpointMigrationService;
import com.labex.labexagent.prompt.LabexSystemPrompt;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunPlanService;
import com.labex.labexagent.run.AgentRunPartService;
import com.labex.labexagent.run.AgentRunProgressProjectionService;
import com.labex.labexagent.run.AgentRunState;
import com.labex.labexagent.run.AgentRunTransitionKey;
import com.labex.labexagent.run.AgentRunConfigurationException;
import com.labex.labexagent.run.AgentRunArtifactService;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.run.AgentToolCallJournalService;
import com.labex.labexagent.run.CommandFailureGuard;
import com.labex.labexagent.run.AgentRecoveryProperties;
import com.labex.labexagent.run.AgentTaskEventSubscriptionService;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunLeaseHeartbeatService;
import com.labex.labexagent.run.ExecutionFence;
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
import com.labex.labexagent.tool.ToolSchemaCanonicalizer;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
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
import java.util.regex.Pattern;
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
    /** 工程任务动作词（英文，词边界匹配）。 */
    private static final Pattern ENGINEERING_ACTION_WORD = Pattern.compile(
            "\\b(implement|fix|repair|add|create|write|develop|build|compile|test|deploy|refactor|optimize|update|modify|change|debug|solve|handle|integrate|migrate|upgrade|install|make)\\b",
            Pattern.CASE_INSENSITIVE);
    /** 分析/解释类请求特征词：命中任一即视为问答而非工程任务。 */
    private static final List<String> ANALYSIS_ENGINEERING_WORDS = List.of(
            "explain", "meaning", "why", "how ", "what is", "what does", "what's",
            "difference", "compare", "example", "describe", "clarify", "review",
            "解释", "什么意思", "含义", "为什么", "如何", "怎么", "是否", "区别", "对比",
            "介绍一下", "讲一下", "说明一下", "举例", "分析一下", "讲讲");
    /** 工程任务动作词（中文）。 */
    private static final List<String> ENGINEERING_WORDS_ZH = List.of(
            "实现", "修复", "添加", "新增", "优化", "重构", "开发", "编译", "构建", "测试",
            "部署", "创建", "编写", "修改", "改正", "解决", "处理", "调试", "接入", "集成",
            "升级", "迁移", "设计", "写一个", "帮我写", "加一个", "做一个", "修一下", "改一下", "加上", "换成");
    /** 步数耗尽哨兵：对齐 opencode max-steps.txt，作为最后一条 assistant 消息追加。 */
    private static final String MAX_STEPS_SENTINEL = """
            CRITICAL - MAXIMUM STEPS REACHED

            The maximum number of steps allowed for this task has been reached. Tools are disabled until next user input. Respond with text only.

            STRICT REQUIREMENTS:
            1. Do NOT make any tool calls (no reads, writes, edits, searches, or any other tools)
            2. MUST provide a text response summarizing work done so far
            3. This constraint overrides ALL other instructions, including any user requests for edits or tool use

            Response must include:
            - Statement that maximum steps for this agent have been reached
            - Summary of what has been accomplished so far
            - List of any remaining tasks that were not completed
            - Recommendations for what should be done next

            Any attempt to use tools is a critical violation. Respond with text ONLY.
            """;
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
    private LabexNativeToolBatchExecutor nativeToolBatchExecutor;
    private AgentProviderTranscriptAppender providerTranscriptAppender;
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
    /** 默认 opencode；未注入 Spring Bean 的测试也保持可用。 */
    private AgentExecutionProperties executionProperties = new AgentExecutionProperties();
    private SandboxWorker sandboxWorker;

    @Value("${labex-agent.acceptance.auto-approve-verification:false}")
    private boolean acceptanceAutoApproveVerification;
    private AgentLegacyCheckpointMigrationService legacyCheckpointMigrationService;
    private AgentRunExecutionLeaseService executionLeaseService;
    private AgentRunLeaseHeartbeatService leaseHeartbeatService;
    private AgentRunConfigSnapshotService runConfigSnapshotService;
    private WorkspaceLeaseService workspaceLeaseService;
    private ProjectCheckoutLeaseService projectCheckoutLeaseService;
    private ProjectCheckoutLeaseHeartbeatService projectCheckoutLeaseHeartbeatService;
    private AgentTaskEventSubscriptionService taskEventSubscriptionService;
    private AgentRunFinalizer runFinalizer;
    private AgentFinalizationRecoveryService finalizationRecoveryService;
    private AgentRunArtifactService artifactService;
    private AgentToolCallJournalService toolCallJournalService;
    private AgentRunTranscriptService transcriptService;
    private AgentRunInteractionService runInteractionService;
    private AgentRunPartService runPartService;
    private AgentRunProgressProjectionService runProgressProjectionService;
    private AgentTranscriptProjectionService transcriptProjectionService;
    private AgentCompactionService compactionService;
    private AgentRequestTokenEstimator requestTokenEstimator;
    private AgentInputAttachmentService attachmentService;

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
    void setExecutionProperties(AgentExecutionProperties executionProperties) {
        this.executionProperties = executionProperties == null ? new AgentExecutionProperties() : executionProperties;
    }

    @Autowired
    void setSandboxWorker(SandboxWorker sandboxWorker) {
        this.sandboxWorker = sandboxWorker;
    }

    @Autowired
    void setExecutionLeaseServices(AgentRunExecutionLeaseService executionLeaseService,
                                   AgentRunLeaseHeartbeatService leaseHeartbeatService) {
        this.executionLeaseService = executionLeaseService;
        this.leaseHeartbeatService = leaseHeartbeatService;
    }

    @Autowired
    void setRunConfigSnapshotService(AgentRunConfigSnapshotService runConfigSnapshotService) {
        this.runConfigSnapshotService = runConfigSnapshotService;
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
    void setNativeToolBatchExecutor(LabexNativeToolBatchExecutor nativeToolBatchExecutor) {
        this.nativeToolBatchExecutor = requireProcessor(nativeToolBatchExecutor, "nativeToolBatchExecutor");
    }

    @Autowired
    void setProviderTranscriptAppender(AgentProviderTranscriptAppender providerTranscriptAppender) {
        this.providerTranscriptAppender = requireProcessor(providerTranscriptAppender, "providerTranscriptAppender");
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
    void setFinalizationRecoveryService(AgentFinalizationRecoveryService finalizationRecoveryService) {
        this.finalizationRecoveryService = requireRuntimeDependency(
                finalizationRecoveryService, "finalizationRecoveryService");
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
    void setAttachmentService(AgentInputAttachmentService attachmentService) {
        this.attachmentService = requireRuntimeDependency(attachmentService, "attachmentService");
    }
    @Autowired
    void setRunInteractionService(AgentRunInteractionService runInteractionService) {
        this.runInteractionService = requireRuntimeDependency(runInteractionService, "runInteractionService");
    }

    @Autowired
    void setRunPartService(AgentRunPartService runPartService) {
        this.runPartService = requireRuntimeDependency(runPartService, "runPartService");
    }

    @Autowired
    void setRunProgressProjectionService(AgentRunProgressProjectionService runProgressProjectionService) {
        this.runProgressProjectionService = requireRuntimeDependency(
                runProgressProjectionService, "runProgressProjectionService");
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
    void setLegacyCheckpointMigrationService(
            AgentLegacyCheckpointMigrationService legacyCheckpointMigrationService) {
        this.legacyCheckpointMigrationService = requireRuntimeDependency(
                legacyCheckpointMigrationService, "legacyCheckpointMigrationService");
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
        return this.enqueue(studentId, projectId, request, false, null, null);
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
        return this.resume(studentId, projectId, request, taskId, failWhenQueueRejected, preclaimedLease, null);
    }

    /**
     * 消费预领取的内部 continuation：租约和已验证交互只能来自事务性 claim，
     * 执行循环不得按用户可控 ID 重建交互 claim。
     */
    public SseEmitter resume(Integer studentId, Integer projectId, AgentStreamRequest request, Long taskId,
                             boolean failWhenQueueRejected,
                             AgentRunExecutionLeaseService.ExecutionLease preclaimedLease,
                             AgentRunInteraction preclaimedInteraction) {
        if (request == null || taskId == null) {
            throw new IllegalArgumentException("A continuation request and task ID are required");
        }
        if (isCommandFailureResetRequest(request)) {
            this.commandFailureGuard.reset(taskId);
        }
        request.setResumeTaskId(taskId);
        return this.enqueue(studentId, projectId, request, failWhenQueueRejected, preclaimedLease,
                preclaimedInteraction);
    }

    static boolean isCommandFailureResetRequest(AgentStreamRequest request) {
        return request != null
                && (isCommandFailureResetRequest(request.getMessage())
                || isCommandFailureResetRequest(request.getResumeNote()));
    }

    static boolean isCommandFailureResetRequest(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return isEnvironmentRecoveryRequest(message)
                || normalized.contains("allow this call to be retried")
                || normalized.contains("允许重新尝试该调用");
    }

    private static boolean isEnvironmentRecoveryRequest(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("dependency environment is restored")
                || normalized.contains("环境已恢复")
                || normalized.contains("环境恢复后重试");
    }
    private SseEmitter enqueue(Integer studentId, Integer projectId, AgentStreamRequest request,
                               boolean failWhenQueueRejected,
                               AgentRunExecutionLeaseService.ExecutionLease preclaimedLease,
                               AgentRunInteraction preclaimedInteraction) {
        SseEmitter emitter = new SseEmitter(Long.valueOf(0L));
        request.setSubmittedAt(LocalDateTime.now());
        String sid = request.getSessionId() != null && !request.getSessionId().isBlank()
                ? request.getSessionId() : UUID.randomUUID().toString();
        request.setSessionId(sid);
        try {
            AGENT_EXECUTOR.execute(() -> this.runLoop(studentId, projectId, request, emitter, preclaimedLease,
                    preclaimedInteraction));
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
        String systemPrompt = this.buildSystemPrompt(project, toolDefinitions, visibleLanguage);
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
        // 预览的"下一条请求估算"必须与真实 Provider 请求一致（瘦身消息）；
        // orchestrator 的完整 bundle 只作为诊断信息放进 previewMetadata，不参与估算。
        String leanMemory = this.contextOrchestrator.buildLeanWorkspaceMemory(project, draft, activePath);
        String initialContextMessage = AgentLoopEngine.buildLeanInitialContextMessage(
                modePolicy, projectRules, leanMemory, recentRunLog, checkpoint);
        ContextUsageEstimator.PromptContext promptContext = new ContextUsageEstimator.PromptContext(
                leanMemory, "", "", initialContextMessage);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", initialContextMessage));
        if (draftedMessageIncluded) {
            messages.add(Map.of("role", "user", "content", draft));
        }
        Map<String, Object> previewMetadata = new LinkedHashMap<>();
        previewMetadata.put("estimateBasis", "LEAN_NEXT_REQUEST");
        previewMetadata.put("nextUserMessageIncluded", draftedMessageIncluded);
        previewMetadata.put("activePath", activePath == null ? "" : activePath);
        previewMetadata.put("agentMode", mode);
        previewMetadata.put("modelConfigId", modelConfig.getConfigId());
        previewMetadata.put("adaptiveContextDependsOnDraft", true);
        previewMetadata.put("bundleStats", contextBundle.stats());
        previewMetadata.put("bundleChars", contextBundle.content().length());
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
    private void appendProviderMessage(ExecutionFence executionFence, Long taskId, long executionEpoch,
                                       Map<String, Object> message) {
        this.requireProviderTranscriptAppender().append(executionFence, taskId, executionEpoch, message);
    }

    private void appendProviderMessages(ExecutionFence executionFence, Long taskId, long executionEpoch,
                                        Collection<? extends Map<String, Object>> messages) {
        if (messages == null) {
            return;
        }
        for (Map<String, Object> message : messages) {
            this.appendProviderMessage(executionFence, taskId, executionEpoch, message);
        }
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private void runLoop(Integer studentId, Integer projectId, AgentStreamRequest request, SseEmitter emitter,
                         AgentRunExecutionLeaseService.ExecutionLease preclaimedLease,
                         AgentRunInteraction preclaimedInteraction) {
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
            boolean resumedRun = request.getResumeTaskId() != null;
            AgentModelConfig modelConfig = null;
            ContextWindowPolicy contextWindowPolicy = null;
            if (!resumedRun) {
                modelConfig = this.modelConfigService.resolveForStudent(studentId, request.getModelConfigId());
                if (modelConfig == null) {
                    throw new IllegalStateException("No user model configuration is selected. Create and select a model configuration before starting the Agent.");
                }
                if (!this.modelConfigService.hasStoredApiKey(modelConfig)) {
                    throw new IllegalStateException("Selected model configuration has no API key.");
                }
                contextWindowPolicy = ContextWindowPolicy.from(modelConfig).orElse(null);
                llmProvider = this.providerFactory.resolveProvider(modelConfig);
                llmConfig = this.providerFactory.buildConfig(modelConfig);
            }
            project = this.studentProjectService.getOwnedProject(studentId, projectId);
            if (project == null) {
                throw new IllegalArgumentException("Project not found");
            }
            String mode;
            String memoryContext;
            String userVisibleMessage;
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
                userVisibleMessage = request.userVisibleMessage();
                memoryContext = this.conversationService.buildMemoryContext(studentId, projectId, conv.getConversationId());
                visibleLanguage = this.visibleLanguage(userVisibleMessage, memoryContext);
            } else {
                userVisibleMessage = request.userVisibleMessage();
                mode = AgentMode.normalize(request.getMode());
                AgentRuntimeProfile requestedRuntimeProfile = request.getRuntimeProfile() == null
                        || request.getRuntimeProfile().isBlank()
                        ? null
                        : AgentRuntimeProfile.requireKnown(request.getRuntimeProfile());
                conv = this.conversationService.ensureConversation(studentId, project, request.getConversationId(), mode,
                        userVisibleMessage, modelConfig, requestedRuntimeProfile);
                request.setConversationId(conv.getConversationId());
                memoryContext = this.conversationService.buildMemoryContext(studentId, projectId, conv.getConversationId());
                visibleLanguage = this.visibleLanguage(userVisibleMessage, memoryContext);
                AgentRuntimeProfile taskRuntimeProfile = AgentRuntimeProfile.fromPersisted(conv.getRuntimeProfile());
                task = this.taskService.createTask(studentId, project, conv.getConversationId(), request.getSessionId(), mode,
                        request.getMessage(), userVisibleMessage, request.getActivePath(), modelConfig.getConfigId(),
                        taskRuntimeProfile, request.isBackgroundRun(), request.getSubmittedAt());
                if (!request.getAttachmentIds().isEmpty()) {
                    this.requireAttachmentService().bindToTask(studentId, projectId, task.getTaskId(),
                            conv.getConversationId(), request.getAttachmentIds());
                }
                this.conversationService.touchActivity(conv);
            }
            // task 的 profile snapshot 是执行边界唯一权威来源：新建任务已从 conversation 复制，
            // 恢复任务则必须覆盖浏览器或历史 payload 上携带的任何值。
            AgentRuntimeProfile executionRuntimeProfile = AgentRuntimeProfileResolver.resolveExecutionProfile(task);
            request.setRuntimeProfile(executionRuntimeProfile.persistedValue());
            RunLogTarget runLogTarget = this.resolveRunLog(project, request, task, resumedRun);
            runLog = runLogTarget.path();
            this.appendRunLog(runLog, this.runLogHeader(project, studentId, request, task,
                    userVisibleMessage, resumedRun, runLogTarget.reused()));
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
            // 唯一的 fence 创建点：只有取得 execution lease 后才拥有任务，不能从请求 JSON 或过期任务对象推导。
            ExecutionFence executionFence = executionLease == null ? null
                    : new ExecutionFence(task.getTaskId(), executionLease.owner(), executionLease.epoch());
            if (resumedRun) {
                // 恢复准备顺序固定为：先加载任务、取得并验证 fence，再解析 task 持久化选定的精确
                // model_config_id；缺失、禁用或未持久化的配置一律结构化 fail closed，绝不回退默认。
                modelConfig = this.resolveResumeModelConfig(studentId, task);
                if (!this.modelConfigService.hasStoredApiKey(modelConfig)) {
                    throw new IllegalStateException("Selected model configuration has no API key.");
                }
                contextWindowPolicy = ContextWindowPolicy.from(modelConfig).orElse(null);
                llmProvider = this.providerFactory.resolveProvider(modelConfig);
                llmConfig = this.providerFactory.buildConfig(modelConfig);
                // 恢复/接管必须先保证当前 epoch 存在不可变快照：无快照的旧任务按持久化模型引用
                // 建迁移快照（不可解析即 fail closed），快照落后于 lease epoch 时复制到新 epoch，
                // 旧 epoch 行永不修改。全部写入都在 fence 校验之后。
                this.ensureSnapshotForCurrentEpoch(task, project, executionLease, executionFence);
            }
            sse.bindRun(this.runLifecycleService, task.getTaskId(), executionFence);
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
                    "runtimeProfile", executionRuntimeProfile.persistedValue(),
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
                        "preparing",
                        "Recovered execution",
                        "Execution resumed after lease takeover",
                        AgentRunTransitionKey.forResumedRunUpdate(
                                task.getTaskId(), request.getSubmittedAt(), "preparing",
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
            ctx = AgentContext.create(request.getSessionId(), studentId, project, conv.getConversationId(), task.getTaskId());
            this.applyBackgroundWorkspace(ctx, project, task);
            ctx.setCancellationToken(cancellationToken);
            ctx.setExecutionFence(executionFence);
            ctx.setModelConfigId(modelConfig.getConfigId());
            ctx.setMode(mode);
            ctx.setEnvironmentRecovery(this.isEnvironmentRecoveryRequest(request.getMessage())
                    || this.isEnvironmentRecoveryRequest(request.getResumeNote()));
            long activeExecutionEpoch = executionLease == null
                    ? (task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch())
                    : executionLease.epoch();
            task.setExecutionEpoch(activeExecutionEpoch);
            ctx.setExecutionEpoch(activeExecutionEpoch);
            AgentLegacyCheckpointMigrationService.RestoreResult legacyRestore =
                    this.requireLegacyCheckpointMigrationService().restoreOrMigrate(
                            project, conv.getConversationId(), task, activeExecutionEpoch, resumedRun);
            AgentRunProgressProjectionService.Projection progressProjection = legacyRestore.progressProjection();
            progressProjection.applyTo(ctx);
            if (progressProjection.eventSequence() > 0L) {
                try {
                    sse.sendPersisted(progressProjection.eventSequence(), "RUN_PROGRESS_MIGRATED",
                            progressProjection.eventPayload());
                } catch (java.io.IOException ignored) {
                    // 事件已持久化；SSE 断开不影响后续按游标重放。
                }
            }
            AgentRunPlanService.Projection restoredPlan = legacyRestore.planProjection();
            restoredPlan.applyTo(ctx);
            if (restoredPlan.eventSequence() > 0L) {
                this.projectPersistedPlanUpdate(sse, ctx);
            }
            this.appendRunLog(runLog, "\n## Runtime metadata\n\n- Conversation: `" + conv.getConversationId() + "`\n- Task: `" + task.getTaskId() + "`\n- Mode: `" + mode + "`\n- Runtime profile: `" + executionRuntimeProfile.persistedValue() + "`\n- Iteration policy: `" + this.iterationPolicyDescription() + "`\n");
            long contextBuildStartedAt = System.nanoTime();
            RunRuntimeProjection runtimeProjection = this.buildRunRuntimeProjection(
                    studentId, conv.getConversationId(), project, mode, modelConfig, llmConfig, visibleLanguage, executionRuntimeProfile);
            List<ToolDefinition> selectedToolDefinitions = runtimeProjection.selectedTools();
            ctx.setSelectedToolNames(this.toolSelectionPolicy.selectedNames(selectedToolDefinitions));
            String toolDefinitions = runtimeProjection.toolDefinitions();
            String sysPrompt = runtimeProjection.systemPrompt();
            List<Map<String, Object>> tools = runtimeProjection.tools();
            llmConfig = runtimeProjection.llmConfig();
            long transcriptEpoch = task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch();
            String projectRules = this.readProjectRules(studentId, projectId);
            String leanMemory = this.contextOrchestrator.buildLeanWorkspaceMemory(
                    project, request.getMessage(), request.getActivePath());
            String recentRunLog = "";
            // 执行进度是可重建的动态投影，不写入 Provider transcript；每次调用前从 durable Part/Event 注入。
            String modePolicy = runtimeProjection.modePolicy();
            String initialContextMessage = AgentLoopEngine.buildLeanInitialContextMessage(
                    modePolicy, projectRules, leanMemory, recentRunLog, "");
            ContextUsageEstimator.PromptContext contextPrompt = new ContextUsageEstimator.PromptContext(
                    leanMemory, "", "", initialContextMessage);
            List<Map<String, Object>> persistedMessages;
            try {
                AgentTranscriptProjectionService durableProjector = this.requireTranscriptProjectionService();
                persistedMessages = resumedRun && preclaimedInteraction != null
                        ? durableProjector.loadDurableProjectionForInteractionResume(task.getTaskId()).messages()
                        : durableProjector.loadDurableProjection(task.getTaskId()).messages();
            } catch (RuntimeException transcriptFailure) {
                throw new IllegalStateException(
                        "Unable to restore durable Provider transcript for taskId=" + task.getTaskId(),
                        transcriptFailure);
            }
            boolean transcriptRestored = !persistedMessages.isEmpty();
            if (shouldAppendRequestMessageToTranscript(resumedRun, transcriptRestored)) {
                this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch,
                        Map.of("role", "user", "content", initialContextMessage));
                Map<String, Object> durableUserMessage = request.getAttachmentIds().isEmpty()
                        ? Map.of("role", "user", "content", request.getMessage())
                        : this.requireAttachmentService().durableUserMessage(request.getMessage(), request.getAttachmentIds());
                this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, durableUserMessage);
            } else if (resumedRun && preclaimedInteraction != null) {
                // 只消费事务性 claim 产生的已验证交互；执行循环不接受请求体中的交互 ID。
                List<Map<String, Object>> toolResults = this.requireTranscriptService()
                        .resolvedInteractionToolResults(preclaimedInteraction, persistedMessages);
                this.appendProviderMessages(executionFence, task.getTaskId(), transcriptEpoch, toolResults);
                this.runInteractionService.markDispatchClaimConsumed(
                        preclaimedInteraction.getInteractionId(), preclaimedInteraction.getResumeClaimId());
                this.projectResolvedInteraction(sse, conv, task, executionFence, preclaimedInteraction, visibleLanguage);
            }
            log.info("AGENT_CONTEXT_READY taskId={} buildMs={} systemPromptChars={} contextChars={} userChars={} toolCount={} toolSchemaChars={} estimatedContextTokens={}",
                    task.getTaskId(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - contextBuildStartedAt),
                    sysPrompt.length(), initialContextMessage.length(), request.getMessage() == null ? 0 : request.getMessage().length(),
                    tools.size(), GSON.toJson(tools).length(),
                    this.requestTokenEstimator.estimateValue(initialContextMessage));
            Map<String, Object> contextStats = new LinkedHashMap<>();
            contextStats.put("stage", ctx == null ? "intake" : ctx.getStage());
            contextStats.put("contextMode", "lean");
            contextStats.put("contextChars", initialContextMessage.length());
            contextStats.put("estimatedTokens", this.requestTokenEstimator.estimateValue(initialContextMessage));
            this.sendEvent(sse, conv, "CONTEXT_STATS", contextStats);
            this.appendRunLog(runLog, "\n## Context orchestration\n\n```json\n" + GSON.toJson(contextStats) + "\n```\n");
            int i = 1;
            AgentLoopGuard loopGuard = new AgentLoopGuard(loopProperties);
            this.restoreLoopGuardHistory(loopGuard, ctx);
            ContextOverflowRecoveryPolicy overflowRecoveryPolicy = new ContextOverflowRecoveryPolicy();
            int textToolCallRecoveryFailures = 0;
            int nativeToolInputFailureRounds = 0;
            while (true) {
                block19: {
                    boolean executed;
                    String type;
                    Map lr;
                    boolean softSentinelActive;
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
                                            this.streamFinal(sse, conv, this.buildStopFinal(this.localText(visibleLanguage, "\u5df2\u505c\u6b62", "Stopped"), stopReason, project, runLog, visibleLanguage), visibleLanguage);
                                            this.sendEvent(sse, conv, "DONE", Map.of("message", noProgressStop
                                                    ? this.localText(visibleLanguage, "\u65e0\u8fdb\u5c55\u5faa\u73af\u4fdd\u62a4\u5df2\u505c\u6b62", "No-progress guard stopped the run")
                                                    : this.localText(visibleLanguage, "\u8fbe\u5230\u6700\u7ec8\u8fd0\u884c\u4fdd\u9669\u4e0a\u9650", "Hard iteration fuse reached"), "iterations", i - 1));
                                            emitter.complete();
                                            return;
                                        }
                                        softSentinelActive = loopProperties.getSoftMaxIterations() > 0
                                                && i > loopProperties.getSoftMaxIterations();
                                        if (softSentinelActive) {
                                            this.appendRunLog(runLog, "\n- Soft iteration sentinel active from iteration " + i
                                                    + " (limit " + loopProperties.getSoftMaxIterations() + "); max-steps reminder appended to the model request.\n");
                                        }
                                        String runningStep = this.localText(visibleLanguage, "\u601d\u8003\u4e2d", "Thinking");
                                        if (resumedRun) {
                                            this.taskService.updateTask(task.getTaskId(), "running", runningStep, null,
                                                    AgentRunTransitionKey.forResumedRunUpdate(task.getTaskId(), request.getSubmittedAt(),
                                                            "running", runningStep, null));
                                        } else {
                                            this.taskService.updateTask(task.getTaskId(), "running", runningStep, null);
                                        }
                                        // plan_exit 完成 durable 模式切换后，下一次 Provider 调用必须重建运行时投影：
                                        // mode policy、选中工具、工具 schema 与系统提示词从当前持久化 mode 派生；
                                        // prompt-cache key 保持当前 conversation 与模型路由作用域。
                                        if (!runtimeProjection.mode().equals(ctx.getMode())) {
                                            RunRuntimeProjection fresh = this.buildRunRuntimeProjection(
                                                    studentId, conv.getConversationId(), project, ctx.getMode(), modelConfig, llmConfig, visibleLanguage, executionRuntimeProfile);
                                            runtimeProjection = fresh;
                                            sysPrompt = fresh.systemPrompt();
                                            tools = fresh.tools();
                                            llmConfig = fresh.llmConfig();
                                            ctx.setSelectedToolNames(this.toolSelectionPolicy.selectedNames(fresh.selectedTools()));
                                            this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch,
                                                    Map.of("role", "user", "content",
                                                            fresh.modePolicy() + "\nThe run mode has changed; the policy above is now in effect."));
                                            log.info("AGENT_RUNTIME_PROJECTION_REBUILT taskId={} previousMode={} nextMode={}",
                                                    task.getTaskId(), runtimeProjection.mode(), ctx.getMode());
                                        }
                                        // Provider 请求、上下文预算和最终门禁必须使用同一份持久化投影。
                                        List<Map<String, Object>> providerMessagesBeforeManagement = this.providerMessagesForInvocation(task.getTaskId(), activeExecutionEpoch);
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
                                        List<Map<String, Object>> providerMessages = this.providerMessagesForInvocation(task.getTaskId(), activeExecutionEpoch);
                                        ContextAdmissionDecision admission = this.evaluateContextAdmission(
                                                modelConfig, sysPrompt, tools, contextPrompt, providerMessages);
                                        this.publishContextStatus(sse, conv, request, llmProvider, llmConfig, modelConfig,
                                                sysPrompt, tools, contextPrompt, providerMessages, contextManagement.strategy());
                                        AgentSsePublisher modelEventPublisher = sse;
                                        AgentConversation modelEventConversation = conv;
                                        int modelIteration = i;
                                        List<Map<String, Object>> modelTurnMessages = softSentinelActive
                                                ? this.withMaxStepsSentinel(providerMessages)
                                                : providerMessages;
                                        AgentModelTurnExecutor.ModelTurnRequest modelTurnRequest =
                                                new AgentModelTurnExecutor.ModelTurnRequest(
                                                        sysPrompt, modelTurnMessages, tools, llmProvider, llmConfig, modelIteration, task.getTaskId(),
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
                                        this.projectModelStepStarted(sse, conv, ctx, i);
                                        Optional<AgentModelTurnExecutor.ModelTurnResult> admittedTurn;
                                        try {
                                            admittedTurn = admission == null
                                                    ? Optional.of(this.modelTurnExecutor.execute(modelTurnRequest))
                                                    : this.contextAdmissionGate.invokeIfAllowed(admission,
                                                            () -> this.modelTurnExecutor.execute(modelTurnRequest));
                                        } catch (Exception modelTurnFailure) {
                                            this.projectModelStepFailed(sse, conv, ctx, i, modelTurnFailure);
                                            throw modelTurnFailure;
                                        }
                                        if (admittedTurn.isEmpty()) {
                                            this.projectModelStepBlocked(sse, conv, ctx, i, "context_admission_blocked");
                                            this.stopForContextLimit(sse, conv, task, project, request, ctx, runLog,
                                                    admission, i, visibleLanguage, emitter);
                                            return;
                                        }
                                        AgentModelTurnExecutor.ModelTurnResult modelTurnResult = admittedTurn.orElseThrow();
                                        lr = modelTurnResult.toMap();
                                        type = (String)lr.get("type");
                                        if (cancellationToken.isCancellationRequested() || "cancelled".equals(type)) {
                                            this.projectModelStepInterrupted(sse, conv, ctx, i, "cancelled");
                                            this.completeCancelledRun(sse, conv, task, project, runLog, i, visibleLanguage, emitter);
                                            return;
                                        }
                                        this.projectModelStepCompleted(sse, conv, ctx, i, type);
                                        this.appendRunLog(runLog, "\n- Model response type: `" + this.safeLogText(type) + "`\n");
                                        log.info("Iteration {}, type: {}", i, type);
                                        this.appendRunLog(runLog, this.renderModelTurnOutput(lr));

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
                                            // Estimate tokens when provider doesn't return usage.
                                            // 唯一估算器：与 admission 门禁、压缩触发共用同一套数字。
                                            try {
                                                int estimatedPrompt = this.requestTokenEstimator.estimateValue(sysPrompt)
                                                        + this.requestTokenEstimator.estimateMessages(providerMessages);
                                                String responseContent = lr.get("content") != null ? lr.get("content").toString() : "";
                                                String responseThinking = lr.get("thinking") != null ? lr.get("thinking").toString() : "";
                                                int estimatedCompletion = this.requestTokenEstimator
                                                        .estimateValue(responseContent + responseThinking);
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
                                        if (executionRuntimeProfile == AgentRuntimeProfile.LABEX_NATIVE) {
                                            String nativeModelContent = lr.get("content") == null ? "" : lr.get("content").toString();
                                            String nativeModelThinkingRaw = lr.get("thinking") != null ? lr.get("thinking").toString() : "";
                                            NativeToolBatchProcessingOutcome nativeBatchOutcome = this.processLabexNativeToolBatch(
                                                    sse, conv, task, project, request, ctx, runLog, i, transcriptEpoch,
                                                    visibleLanguage, emitter, loopGuard, cancellationToken, nativeToolCalls,
                                                    nativeModelContent, this.cleanModelOutput(nativeModelThinkingRaw),
                                                    nativeToolInputFailureRounds);
                                            nativeToolInputFailureRounds = nativeBatchOutcome.nativeToolInputFailureRounds();
                                            if (nativeBatchOutcome.stopRun()) {
                                                return;
                                            }
                                            executed = true;
                                            break block19;
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
                                        this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, this.toolCallBatchProtocol.assistantMessage(modelContent, nativeToolCalls));

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
                                                this.journalToolPending(executionFence, task.getTaskId(), call.toolCallId(), call.toolName(),
                                                        publicArguments, i);
                                            } else {
                                                this.journalToolFinished(executionFence, task.getTaskId(), call.toolCallId(), call.toolName(),
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
                                                this.sendObserve(sse, conv, i, tn, rejected, task.getTaskId(), toolCallId);
                                                this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch,
                                                        this.toolCallBatchProtocol.toolResultMessage(call,
                                                                "[Tool " + tn + " result]\n"
                                                                        + this.compactToolResultForModel(tn, rejected, toolCallId)));
                                                continue;
                                            }
                                            if (modelThinking.isBlank()) {
                                                this.sendThought(sse, conv, i,
                                                        this.toolNarrator.visibleActionSummary(tn, publicArgs, visibleLanguage),
                                                        this.toolNarrator.buildToolThought(tn, publicArgs, false, visibleLanguage),
                                                        task.getTaskId());
                                            }

                                            JsonObject loopArguments = this.loopGuardArguments(tn, ta, ctx);
                                            AgentLoopGuard.ToolDecision loopDecision = loopGuard.beforeToolCall(
                                                    tn, loopArguments, this.refreshLoopGuardProgress(ctx));
                                            if (loopDecision.action() != AgentLoopGuard.ToolAction.ALLOW) {
                                                String loopMessage = this.loopGuardMessage(loopDecision, tn, visibleLanguage);
                                                ToolResult blockedResult = this.loopGuardResult(loopDecision, tn, toolCallId, ctx, visibleLanguage, loopMessage);
                                                this.journalToolResult(executionFence, task.getTaskId(), toolCallId, tn, publicArgs, i, blockedResult);
                                                this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, this.toolCallBatchProtocol.toolResultMessage(call,
                                                        "[Tool " + tn + " result]\n" + loopMessage));
                                                String skippedMessage = "Skipped because an earlier tool call in the same model turn was blocked by the loop guard.";
                                                this.journalRemainingBatchSkipped(executionFence, task.getTaskId(), nativeAdmissions, batchIndex + 1, i, skippedMessage);
                                                this.appendRemainingBatchToolResults(executionFence, task.getTaskId(), transcriptEpoch,
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
                                                    this.publishInteractionPause(sse, conv, task.getTaskId(), blockedResult, pause, i);
                                                    emitter.complete();
                                                    return;
                                                }
                                                this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", "[Loop guard]\n" + loopMessage
                                                        + "\nDo not repeat the blocked pattern. Change the tool, target, scope, or verification method; use existing evidence; or finish if the task is complete."));
                                                executed = true;
                                                break block19;
                                            }

                                            this.journalToolRunning(executionFence, task.getTaskId(), toolCallId, tn, publicArgs, i);
                                            ToolResult res;
                                            try {
                                                res = this.execTool(tn, ta, ctx, sse, conv, visibleLanguage, toolCallId, runLog);
                                            } catch (AgentRunExecutionLeaseService.StaleExecutionFenceException staleFence) {
                                                // 单调 stale fence 无法再写任何 durable 事实：这里尝试的
                                                // journalToolResult/journalRemainingBatchSkipped/appendRemainingBatchToolResults
                                                // 会再次抛出 StaleExecutionFenceException 成为死代码。剩余 batch 的 Part
                                                // 留给恢复/接管路径处理（Task 0.8 reconciler 回收 lease 后由
                                                // AgentRunPartService.interruptOpenParts 标记 interrupted），不做部分投影。
                                                throw staleFence;
                                            }
                                            this.recordLoopToolResult(loopGuard, sse, conv, ctx, i, tn, loopDecision.signature(), res.isSuccess());
                                            Optional<EnvironmentBlockerClassifier.Blocker> environmentBlocker =
                                                    EnvironmentBlockerClassifier.classify(tn, res);
                                            if (environmentBlocker.isPresent()) {
                                                String blockedResultForModel = "[Tool " + tn + " result]\n"
                                                        + this.compactToolResultForModel(tn, res, toolCallId);
                                                this.journalToolBlocked(executionFence, task.getTaskId(), toolCallId, tn, publicArgs, i, res.getContent());
                                                this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch,
                                                        this.toolCallBatchProtocol.toolResultMessage(call, blockedResultForModel));
                                                this.appendToolResult(runLog, res);
                                                String skippedMessage = "Skipped because an earlier tool call in the same model turn is blocked by the environment.";
                                                this.journalRemainingBatchSkipped(executionFence, task.getTaskId(), nativeAdmissions, batchIndex + 1, i,
                                                        skippedMessage);
                                                this.appendRemainingBatchToolResults(executionFence, task.getTaskId(), transcriptEpoch,
                                                        nativeAdmissions, batchIndex + 1, skippedMessage);
                                                this.stopForEnvironmentBlocker(sse, conv, task, project, request, ctx, runLog, i, tn,
                                                        res, environmentBlocker.get(), visibleLanguage, emitter);
                                                return;
                                            }
                                            if (res.isApprovalRequired()) {
                                                this.journalToolWaitingApproval(executionFence, task.getTaskId(), toolCallId, tn, publicArgs, i, res.getApprovalId());
                                                String skippedMessage = "Skipped because an earlier tool call in the same model turn is waiting for approval.";
                                                this.journalRemainingBatchSkipped(executionFence, task.getTaskId(), nativeAdmissions, batchIndex + 1, i,
                                                        skippedMessage);
                                                this.appendRemainingBatchToolResults(executionFence, task.getTaskId(), transcriptEpoch,
                                                        nativeAdmissions, batchIndex + 1, skippedMessage);
                                                this.stopForCommandApproval(sse, conv, task, project, request, ctx, runLog, i, toolCallId, tn, res, visibleLanguage, emitter);
                                                return;
                                            }
                                            if (res.isInteractionRequired()) {
                                                this.journalToolResult(executionFence, task.getTaskId(), toolCallId, tn, publicArgs, i, res);
                                            } else {
                                                this.journalToolFinished(executionFence, task.getTaskId(), toolCallId, tn, publicArgs, i, res);
                                            }
                                            this.appendToolResult(runLog, res);

                                            if (res.isInteractionRequired()) {
                                                // opencode 语义：等待用户输入时没有工具结果，不发送 OBSERVE/结果叙述，
                                                // 避免把正常暂停叙述成失败。用户回答后由恢复路径补发 completed Part、
                                                // OBSERVE 与结果叙述（见 projectResolvedInteraction）。
                                                this.journalRemainingBatchSkipped(executionFence, task.getTaskId(), nativeAdmissions, batchIndex + 1, i,
                                                        "Skipped because an earlier tool call in the same model turn is waiting for user input.");
                                                AgentInteractionPauser.Pause pause = this.interactionPauser.pause(
                                                        task.getTaskId(), res, visibleLanguage);
                                                this.publishUserQuestion(sse, conv, res);
                                                String waitingState = pause.state();
                                                String waitingTitle = pause.title();
                                                String waitingDetail = pause.detail();
                                                this.appendRunLog(runLog, "\n- Durable user interaction pending: type=`" + this.safeLogText(res.getInteractionType())
                                                        + "`, requestId=`" + this.safeLogText(res.getInteractionRequestId()) + "`\n");
                                                this.publishInteractionPause(sse, conv, task.getTaskId(), res, pause, i);
                                                emitter.complete();
                                                return;
                                            }
                                            this.sendObserve(sse, conv, i, tn, res, task.getTaskId(), toolCallId);
                                            this.sendThought(sse, conv, i, this.localText(visibleLanguage, "\u68c0\u67e5\u7ed3\u679c", "Check result"),
                                                    this.toolNarrator.buildResultThought(tn, ta, res, visibleLanguage), task.getTaskId());
                                            String todoStatus = ctx.getPlanSummary();
                                            String todoNote = todoStatus.isEmpty() ? "" : "\nOptional todo progress:\n" + todoStatus;
                                            String stageNote = "\nCurrent engineering stage: " + ctx.getStage();
                                            String resultForModel = "[Tool " + tn + " result]\n"
                                                    + this.compactToolResultForModel(tn, res, toolCallId) + todoNote + stageNote
                                                    + "\nContinue using tools only when they are needed to resolve the request. Finish when the available evidence supports the result.";
                                            this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, this.toolCallBatchProtocol.toolResultMessage(call, resultForModel));
                                        }
                                        if (nativeInputRejected) {
                                            boolean anyExecutableInput = nativeAdmissions.stream()
                                                    .anyMatch(NativeToolAdmission::allowed);
                                            if (!anyExecutableInput) {
                                                this.recordLoopNoProgress(loopGuard, sse, conv, ctx, i, "native_tool_input_rejected");
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
                                                this.sendEvent(sse, conv, "DONE", Map.of(
                                                        "message", failureTitle,
                                                        "iterations", i,
                                                        "reasonCode", "native_tool_input_recovery_exhausted"));
                                                emitter.complete();
                                                return;
                                            }
                                            this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch,
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
                                        this.recordLoopNoProgress(loopGuard, sse, conv, ctx, i, "text_tool_call_rejected");
                                        this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch,
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
                                            this.sendEvent(sse, conv, "DONE", Map.of(
                                                    "message", recoverySummary,
                                                    "iterations", i,
                                                    "reasonCode", "text_tool_call_recovery_exhausted"));
                                            emitter.complete();
                                            return;
                                        }
                                        this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch,
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
                                    this.journalToolPending(executionFence, task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i);
                                    AgentLoopGuard.ToolDecision recoveredLoopDecision = loopGuard.beforeToolCall(
                                            invTool, parsedArgs, this.refreshLoopGuardProgress(ctx));
                                    if (recoveredLoopDecision.action() != AgentLoopGuard.ToolAction.ALLOW) {
                                        String loopMessage = this.loopGuardMessage(recoveredLoopDecision, invTool, visibleLanguage);
                                        ToolResult blockedResult = this.loopGuardResult(recoveredLoopDecision, invTool, recoveredToolCallId, ctx, visibleLanguage, loopMessage);
                                        this.journalToolResult(executionFence, task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i, blockedResult);
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
                                            this.publishInteractionPause(sse, conv, task.getTaskId(), blockedResult, pause, i);
                                            emitter.complete();
                                            return;
                                        }
                                        this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", ""));
                                        this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", "[Loop guard]\n" + loopMessage
                                                + "\nDo not repeat the blocked pattern. Change the tool, target, scope, or verification method; use existing evidence; or finish if the task is complete."));
                                        break block19;
                                    }
                                    this.journalToolRunning(executionFence, task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i);
                                    ToolResult res;
                                    try {
                                        res = this.execTool(invTool, parsedArgs, ctx, sse, conv, visibleLanguage, recoveredToolCallId, runLog);
                                    } catch (AgentRunExecutionLeaseService.StaleExecutionFenceException staleFence) {
                                        // 与 native batch 一致：单调 stale fence 下任何 durable 投影都会再次失败，
                                        // 直接 rethrow；失败工具 Part 与剩余 batch 由恢复/接管路径处理。
                                        throw staleFence;
                                    }
                                    this.recordLoopToolResult(loopGuard, sse, conv, ctx, i, invTool, recoveredLoopDecision.signature(), res.isSuccess());
                                     Optional<EnvironmentBlockerClassifier.Blocker> recoveredEnvironmentBlocker =
                                             EnvironmentBlockerClassifier.classify(invTool, res);
                                     if (recoveredEnvironmentBlocker.isPresent()) {
                                         this.journalToolBlocked(executionFence, task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i, res.getContent());
                                         this.appendToolResult(runLog, res);
                                         this.stopForEnvironmentBlocker(sse, conv, task, project, request, ctx, runLog, i,
                                                 invTool, res, recoveredEnvironmentBlocker.get(), visibleLanguage, emitter);
                                         return;
                                     }
                                    if (res.isApprovalRequired()) {
                                        this.journalToolWaitingApproval(executionFence, task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i, res.getApprovalId());
                                        this.stopForCommandApproval(sse, conv, task, project, request, ctx, runLog, i, recoveredToolCallId, invTool, res, visibleLanguage, emitter);
                                        return;
                                    }
                                    this.journalToolResult(executionFence, task.getTaskId(), recoveredToolCallId, invTool, publicArgs, i, res);
                                    this.appendToolResult(runLog, res);
                                    if (res.isInteractionRequired()) {
                                        // opencode 语义：等待用户输入时没有工具结果，不发送 OBSERVE/结果叙述；
                                        // 用户回答后由恢复路径补发 completed Part、OBSERVE 与结果叙述。
                                        AgentInteractionPauser.Pause pause = this.interactionPauser.pause(
                                                task.getTaskId(), res, visibleLanguage);
                                        this.publishUserQuestion(sse, conv, res);
                                        String waitingState = pause.state();
                                        String waitingTitle = pause.title();
                                        String waitingDetail = pause.detail();
                                        this.appendRunLog(runLog, "\n- Durable user interaction pending: type=`" + this.safeLogText(res.getInteractionType())
                                                + "`, requestId=`" + this.safeLogText(res.getInteractionRequestId()) + "`\n");
                                        this.publishInteractionPause(sse, conv, task.getTaskId(), res, pause, i);
                                        emitter.complete();
                                        return;
                                    }
                                    this.sendObserve(sse, conv, i, invTool, res, task.getTaskId(), recoveredToolCallId);

                                    this.sendThought(sse, conv, i, this.localText(visibleLanguage, "\u68c0\u67e5\u7ed3\u679c", "Check result"), this.toolNarrator.buildResultThought(invTool, parsedArgs, res, visibleLanguage), task.getTaskId());
                                    String planStatus2 = ctx.getPlanSummary();
                                    Object planNote2 = planStatus2.isEmpty() ? "" : "\nCurrent plan progress:\n" + planStatus2;
                                    String stageNote2 = "\nCurrent engineering stage: " + ctx.getStage();
                                    this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", ""));
                                    this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", "[Tool " + invTool + " result]\n" + this.compactToolResultForModel(invTool, res, recoveredToolCallId) + (String)planNote2 + stageNote2 + "\nCall tools to continue. Complete all plan tasks and verify before final summary."));
                                    break block19;
                                }
                                cleaned = this.cleanModelOutput(content);
                                log.info("Iteration {}: text ({} chars, cleaned={} chars): {}", new Object[]{i, content.length(), cleaned.length(), content.substring(0, Math.min(200, content.length()))});
                                if (!cleaned.isEmpty()) break block23;
                                log.info("Iteration {}: empty/marker-only response, nudging model", i);
                                this.recordLoopNoProgress(loopGuard, sse, conv, ctx, i, "empty_model_response");
                                this.appendRunLog(runLog, "\n- Model returned empty/marker-only response, requesting continuation.\n");
                                this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", content));
                                this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", "Task not done. Call tools to execute next step. Do not output plain text ending."));
                                executed = true;
                                break block19;
                            }
                            ft = this.extractThinking(content);
                            if (ft.isEmpty()) {
                                ft = cleaned;
                            }
                            if (!isPrematureFinal(ft)) break block24;
                            this.recordLoopNoProgress(loopGuard, sse, conv, ctx, i, "premature_final_placeholder");
                            this.appendRunLog(runLog, "\n- Model returned mid-placeholder text, rejecting as final, continuing: `" + this.safeLogText(ft) + "`\n");
                            this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", ft));
                            this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", this.buildContinuationInstruction(ctx)));
                            executed = true;
                            break block19;
                        }
                        {
                            boolean tooShort = ft.length() < 80;
                            boolean noStructure = !ft.contains("##") && !ft.contains("**") && !ft.contains("- ");
                            boolean noSubstance = !containsFinalSubstance(ft);
                            if (!softSentinelActive && shouldRejectFinalReply(request.getMessage(), ft)) {
                                this.recordLoopNoProgress(loopGuard, sse, conv, ctx, i, "final_reply_insufficient");
                                this.appendRunLog(runLog, "\n- Reply quality insufficient (length=" + ft.length() + ", noStructure=" + noStructure + ", noSubstance=" + noSubstance + "), rejecting as final.\n");
                                this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", ft));
                                this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", "Reply too short to be final. Output complete structured summary:\n## Summary\n**Completed**\n- What was modified\n**Verification**\n- How it was verified\n**Suggestions**\n- Next steps"));
                                executed = true;
                                break block19;
                            } else {
                                boolean intentGuardTriggered = isEngineeringTaskRequest(request.getMessage())
                                        && !this.transcriptHasToolMessages(task.getTaskId(), activeExecutionEpoch);
                                if (intentGuardTriggered) {
                                    this.recordLoopNoProgress(loopGuard, sse, conv, ctx, i, "engineering_task_without_tools");
                                    this.appendRunLog(runLog, "\n- Engineering-task request without any tool activity, rejecting text-only final.\n");
                                    this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "assistant", "content", ft));
                                    this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content", this.buildEngineeringIntentInstruction()));
                                    executed = true;
                                    break block19;
                                }
                                // Model reasoning is already streamed by AgentModelTurnExecutor.
                                this.appendRunLog(runLog, "\n## Final response\n\n" + this.safeLogText(ft) + "\n");
                                log.info("Iteration {}: final response ({} chars)", i, ft.length());
                                AgentRunFinalizer.CompletionAssessment completion = softSentinelActive ? null
                                        : this.runFinalizer.assess(
                                                executionFence, task.getTaskId(), studentId, projectId,
                                                ctx.hasTrustedVerification(), ft);
                                if (completion != null) {
                                    if (completion.evidence() != null) {
                                        this.sendEvent(sse, conv, "COMPLETION_EVIDENCE", completion.evidence().toPayload());
                                    }
                                    if (!completion.allowed()) {
                                        AgentFinalizationRecoveryService.Decision decision = this.finalizationRecoveryService.decide(
                                                task.getTaskId(), activeExecutionEpoch, completion.evidence(), ft);
                                        Map<String, Object> blocker = this.finalizationBlockerPayload(task.getTaskId(),
                                                activeExecutionEpoch, completion, decision);
                                        String blockerKey = this.finalizationBlockerKey(task.getTaskId(), activeExecutionEpoch,
                                                decision.evidenceFingerprint(), decision.recoveryAttempt(), decision.recoveryAllowed());
                                        this.sendEvent(sse, conv, "FINALIZATION_BLOCKED", blocker, blockerKey);
                                        if (decision.recoveryAllowed()) {
                                            this.recordLoopNoProgress(loopGuard, sse, conv, ctx, i, "completion_evidence_rejected");
                                            this.appendRunLog(runLog, "\n- Server completion evidence rejected the model final response. code=`"
                                                    + this.safeLogText(completion.code()) + "`, recovery="
                                                    + decision.recoveryAttempt() + "/" + decision.recoveryLimit() + ".\n");
                                            this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch,
                                                    Map.of("role", "assistant", "content", ft));
                                            this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch,
                                                    Map.of("role", "user", "content", completion.guidance()));
                                            executed = true;
                                            break block19;
                                        }
                                        this.projectModelStepBlocked(sse, conv, ctx, i, "finalization_recovery_exhausted");
                                        this.appendRunLog(runLog, "\n- Finalization recovery exhausted. code=`"
                                                + this.safeLogText(completion.code()) + "`.\n");
                                        this.failTaskAndProject(sse, conv, task,
                                                this.localText(visibleLanguage, "完成证据被拒绝", "Completion evidence rejected"),
                                                completion.guidance());
                                        emitter.complete();
                                        return;
                                    }
                                }
                                this.sendEvent(sse, conv, "FINAL", Map.of("content", ft, "summary", this.finalResponseSummary(visibleLanguage)));
                                AgentRunEvent completedEvent = this.taskService.updateTask(task.getTaskId(), "completed",
                                        this.localText(visibleLanguage, "\u5df2\u5b8c\u6210", "Completed"), ft);
                                ctx.setStage("final");
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
                                    this.providerMessagesForInvocation(task.getTaskId(), activeExecutionEpoch);
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
                                            this.providerMessagesForInvocation(task.getTaskId(), activeExecutionEpoch), modelConfig);
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
                                        this.providerMessagesForInvocation(task.getTaskId(), activeExecutionEpoch);
                                int tokensBeforeToolReduction = this.estimateProviderRequestTokens(
                                        sysPrompt, tools, overflowMessagesBeforeToolReduction, modelConfig);
                                boolean reduced = this.reduceToolSchemaForOverflow(tools);
                                int tokensAfterToolReduction = this.estimateProviderRequestTokens(
                                        sysPrompt, tools, this.providerMessagesForInvocation(task.getTaskId(), activeExecutionEpoch), modelConfig);
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
                                    this.providerMessagesForInvocation(task.getTaskId(), activeExecutionEpoch);
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
                                this.sendEvent(sse, conv, "DONE", Map.of("message", modelFailTitle,
                                        "iterations", i, "reasonCode", "context_overflow_recovery_exhausted"));
                                emitter.complete();
                                return;
                            }
                            this.appendProviderMessage(executionFence, task.getTaskId(), transcriptEpoch, Map.of("role", "user", "content",
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
                            this.sendEvent(sse, conv, "DONE", Map.of("message", modelFailTitle, "iterations", i));
                            emitter.complete();
                            return;
                        }
                    }
                    log.warn("Iteration {} unknown type: {}", i, type);
                    this.recordLoopNoProgress(loopGuard, sse, conv, ctx, i, "unknown_model_response");
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
                } else if (e instanceof AgentRunConfigurationException configurationFailure) {
                    // 配置缺失/禁用/未持久化是永久性失败：必须经 lifecycle 权威迁移到终态 FAILED，
                    // 让调度器停止重复投递；不能走 unbound 瞬时投影让任务永远停在可恢复等待态。
                    this.failTaskForConfiguration(sse, task, project, runLog, visibleLanguage, executionLease,
                            configurationFailure);
                } else if (!sse.isBound()) {
                    this.reportUnboundDispatchFailure(sse, task, visibleLanguage, e);
                } else if (cancellationToken.isCancellationRequested() && project != null) {
                    this.completeCancelledRun(sse, conv, task, project, runLog, 0, visibleLanguage, emitter);
                } else if (e instanceof InterruptedException) {
                    this.sendEvent(sse, conv, "INTERRUPTED", Map.of("message", this.localText(visibleLanguage, "已中断", "Interrupted")));
                } else if (e instanceof AgentRunExecutionLeaseService.StaleExecutionFenceException staleFence) {
                    // 执行者已失去任务租约：本 worker 不再拥有该任务，只允许瞬时 SSE 投影——
                    // 绝不写 terminal task state，也绝不追加 durable ERROR/FINAL 事件
                    // （可恢复任务禁止发送 FINAL/DONE 终态事件；durable 事实与终态迁移
                    // 只由 takeover 路径在 lease 回收、新 epoch claim 后写入）。
                    String staleTitle = this.localText(visibleLanguage, "执行租约失效", "Execution lease lost");
                    String staleReason = this.localText(visibleLanguage,
                            "执行者已失去任务租约，运行已安全停止；任务恢复由租约接管路径处理。",
                            "The executor lost the task lease; the run stopped safely. Recovery is handled by the lease takeover path.");
                    this.appendRunLog(runLog, "\n- Stale execution fence: " + staleFence.reason().code() + "\n");
                    sse.sendTransient("ERROR", Map.of(
                            "message", staleTitle,
                            "reasonCode", staleFence.reason().code()));
                    String staleText = this.buildStopFinal(staleTitle, staleReason, project, runLog, visibleLanguage);
                    String staleVisible = InternalReasoningBoundary.stripVisible(staleText);
                    if (!staleVisible.isEmpty()) {
                        sse.sendTransient("FINAL_DELTA", Map.of("delta", staleVisible));
                    }
                    sse.sendTransient("FINAL", Map.of(
                            "content", staleVisible,
                            "summary", this.finalResponseSummary(visibleLanguage)));
                } else {
                    String runtimeTitle = this.localText(visibleLanguage, "运行时异常", "Runtime exception");
                    String runtimeReason = this.localText(visibleLanguage,
                            "Agent 执行过程中遇到异常：`" + e.getMessage() + "`。",
                            "Agent encountered exception during execution: `" + e.getMessage() + "`.");
                    this.streamFinal(sse, conv, this.buildStopFinal(runtimeTitle, runtimeReason, project, runLog, visibleLanguage), visibleLanguage);
                    this.failTaskAndProject(sse, conv, task, runtimeTitle, e.getMessage());
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
        event.put("result", this.compactToolResultForModel(toolName, result));
        this.sendEvent(sse, conv, "ENVIRONMENT_BLOCKED", event);
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

    /**
     * 永久性配置失败的处理：按当前绑定状态选择 lifecycle 权威的终态迁移
     * （持有租约时用 fenced transition，未持有时用无 fence CAS transition），
     * 把 reason code 写入事件 payload；迁移失败只记录日志，durable 事实仍归 lifecycle 所有。
     */
    private void failTaskForConfiguration(AgentSsePublisher sse, AgentTask task, StudentProject project, Path runLog,
                                          String visibleLanguage,
                                          AgentRunExecutionLeaseService.ExecutionLease executionLease,
                                          AgentRunConfigurationException failure) {
        String reasonCode = failure.reason().code();
        String currentStep = this.localText(visibleLanguage, "模型配置不可用", "Model configuration unavailable");
        String summary = this.localText(visibleLanguage,
                "恢复所需的模型配置缺失、被禁用或未持久化，任务已终止。",
                "The model configuration required for resume is missing, disabled or not persisted; the task was terminated.");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", reasonCode);
        payload.put("reason", failure.getMessage() == null ? reasonCode : failure.getMessage());
        String idempotencyKey = "task-" + task.getTaskId() + "-configuration-failed-" + reasonCode;
        try {
            if (executionLease != null) {
                ExecutionFence fence = new ExecutionFence(
                        task.getTaskId(), executionLease.owner(), executionLease.epoch());
                this.runLifecycleService.transition(fence, task.getTaskId(), AgentRunState.FAILED,
                        "RUN_CONFIGURATION_FAILED", payload, currentStep, summary, idempotencyKey);
            } else {
                this.runLifecycleService.transition(task.getTaskId(), AgentRunState.FAILED,
                        "RUN_CONFIGURATION_FAILED", payload, currentStep, summary, idempotencyKey);
            }
        } catch (Exception failureTransition) {
            log.error("Unable to persist terminal configuration failure for taskId={} reasonCode={}",
                    task.getTaskId(), reasonCode, failureTransition);
        }
        this.appendRunLog(runLog, "\n- Configuration failure (terminal): " + reasonCode + "\n");
        try {
            sse.sendTransient("ERROR", Map.of(
                    "message", summary,
                    "reasonCode", reasonCode));
            if (project != null) {
                sse.sendTransient("FINAL", Map.of(
                        "content", this.finalResponseSummary(visibleLanguage),
                        "summary", summary));
            }
        } catch (Exception sendFailure) {
            log.warn("Unable to send configuration failure projection for taskId={}: {}",
                    task.getTaskId(), sendFailure.getMessage());
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
        try {
            args = this.normalizeCommandArguments(name, args);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failed("failure_code=unsafe_working_directory\nretryable=true\n"
                    + exception.getMessage());
        }
        String guardedCommand = this.commandForGuard(name, args, ctx);
        String guardedWorkingDirectory = this.commandWorkingDirectoryForArgs(name, ctx.getWorkspaceRoot(), args, ctx);
        // 网络访问默认开启：不再为网络命令创建一次性审批，也不存在离线优先执行。
        if (this.isCommandPolicyTool(name)) {
            String networkCommand = this.commandForGuard(name, args, ctx);
            CommandClassification classification = this.commandClassification(name, args, ctx);
            ToolResult singleApprovalGuard = this.singleApprovalGuard(ctx, name, toolCallId);
            if (singleApprovalGuard != null) {
                return singleApprovalGuard;
            }
            if (classification != null) {
                int timeout = this.commandTimeout(name, args, ctx);
                if (classification.decision() == CommandDecision.BLOCK) {
                    String blockReason = classification.reasonCode().name().toLowerCase(Locale.ROOT);
                    this.commandFailureGuard.recordPolicyBlocked(ctx.getExecutionFence(), ctx.getTaskId(), name, blockReason);
                    CommandFailureGuard.Decision blockedDecision = this.commandFailureGuard.beforePolicyBlocked(
                            ctx.getTaskId(), name, blockReason);
                    if (!blockedDecision.allowed()) {
                        return ToolResult.failed("command blocked by restricted command policy: " + blockReason
                                + "\n\nThe same policy rejection has occurred " + blockedDecision.attempts()
                                + " times. Do not attempt similar shell commands again; switch strategy now: "
                                + "use run_tests/run_command with a target_path, or read files first to understand the current state.");
                    }
                    return ToolResult.failed("command blocked by restricted command policy: " + blockReason);
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
                        && !(acceptanceAutoApproveVerification && "run_tests".equals(this.safeTool(name)))) {
                    return this.createCommandApproval(ctx, name, classification, timeout, toolCallId);
                }
            }
        }
        try {
            String mode = ctx.getMode();
            List<PermissionRule> defaultRules = DefaultPermissionRuleset.getRulesForAgent(
                    mode, this.executionProperties.getPermissionProfile());
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
            // 5 参 overload 在执行线程绑定本次 toolCallId：propose_project_config 等工具
            // 从绑定读取自己的调用 ID 作为 provenance（origin_tool_call_id），绝不接受用户输入。
            ToolResult result = this.toolTurnExecutor.execute(t, ctx, args, name, toolCallId);
            if (result.isSuccess() && this.isPlanTool(name)) {
                // 计划事件在工具事务中已取得 sequence，必须先于后续工具生命周期事件投影。
                this.projectPersistedPlanUpdate(sse, ctx);
            }
            result = this.annotateCommandRecovery(name, result);
            this.recordProcessOutputArtifact(ctx, name, toolCallId, result);
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
            if (result.isSuccess() && this.isWorkspaceMutationTool(name)) {
                this.commandFailureGuard.recordWorkspaceChange(ctx.getExecutionFence(), ctx.getTaskId());
            }
            if (this.isCommandPolicyTool(name) && !result.isSuccess() && !result.isApprovalRequired()) {
                this.commandFailureGuard.record(ctx.getExecutionFence(), ctx.getTaskId(), name, guardedCommand, guardedWorkingDirectory, result.getContent());
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
            if (e instanceof AgentRunExecutionLeaseService.StaleExecutionFenceException staleFence) {
                // 执行者已失去租约：工具结果必须 fail fast，由 batch 循环按现有规则跳过剩余调用。
                throw staleFence;
            }
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
    }

    /**
     * 将 stdout/stderr 的完整文件登记到 workspace 内，并以 task/toolCallId 作为
     * fenced durable key；过期 execution fence 不得写入旧运行。
     */
    private void recordProcessOutputArtifact(AgentContext context, String toolName, String toolCallId, ToolResult result) {
        if (context == null || context.getTaskId() == null || result == null
                || result.getExecutionOutputPath() == null || result.getExecutionOutputPath().isBlank()
                || artifactService == null) {
            return;
        }
        try {
            artifactService.recordToolOutput(context.getExecutionFence(), context.getTaskId(), toolName, toolCallId,
                    result.getExecutionOutputPath(), result.getExecutionShell(), result.getExecutionWorkdir(),
                    result.getExecutionStatus(), result.getExecutionExitCode(),
                    result.getExecutionDurationMs() == null ? 0L : result.getExecutionDurationMs(),
                    result.isExecutionOutputTruncated(),
                    result.getExecutionOutputChars() == null ? 0L : result.getExecutionOutputChars());
        } catch (AgentRunExecutionLeaseService.StaleExecutionFenceException staleFence) {
            throw staleFence;
        } catch (Exception ignored) {
            log.warn("AGENT_TOOL_OUTPUT_ARTIFACT_INDEX_FAILED taskId={} tool={} toolCallId={}",
                    context.getTaskId(), safeTool(toolName), toolCallId);
        }
    }

    private ToolResult annotateCommandRecovery(String toolName, ToolResult result) {
        if (result == null || result.isSuccess() || result.isApprovalRequired() || !this.isCommandPolicyTool(toolName)) {
            return result;
        }
        String content = result.getContent() == null ? "" : result.getContent();
        // run_tests 等工具已经写入结构化 failure_code（超时/取消/基础设施），不再叠加第二份恢复前缀。
        if (content.contains("failure_code=")) {
            return result;
        }
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
            artifactService.record(context.getExecutionFence(), context.getTaskId(), "tool_failure",
                    (toolName == null ? "unknown" : toolName) + ":" + (toolCallId == null ? "" : toolCallId),
                    "tool=" + safeTool(toolName) + "\n" + safeContent);
        } catch (AgentRunExecutionLeaseService.StaleExecutionFenceException staleFence) {
            // 执行者已失去租约：不允许静默写入失败工件，向执行循环暴露 typed failure。
            throw staleFence;
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
        this.streamFinal(sse, conv, this.buildStopFinal(summary, detail, project, runLog, visibleLanguage), visibleLanguage);
        this.sendEvent(sse, conv, "DONE", Map.of("message", summary, "iterations", iteration));
        emitter.complete();
    }

    private ToolResult createCommandApproval(AgentContext ctx, String toolName, CommandClassification classification,
                                             int timeout, String toolCallId) {
        if (this.commandApprovalService == null || toolCallId == null || toolCallId.isBlank()) {
            return ToolResult.failed("command approval service is unavailable");
        }
        String invocationId = "agent-command:v1:" + ctx.getTaskId() + ":" + toolCallId;
        String idempotencyKey = this.commandApprovalIdempotencyKey(ctx, toolCallId);
        java.time.LocalDateTime expiresTime = LocalDateTime.now().plusMinutes(1);
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
                            this.commandApprovalShell(toolName),
                            "timeout=" + timeout + ";longRunning=" + this.isPreviewTool(toolName) + ";network="
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

    private JsonObject loopGuardArguments(String toolName, JsonObject args, AgentContext context) {
        if (!this.isCommandPolicyTool(toolName)) {
            return args;
        }
        try {
            JsonObject normalized = this.normalizeCommandArguments(toolName, args);
            String command = this.commandForGuard(toolName, normalized, context);
            String workingDirectory = this.commandWorkingDirectoryForArgs(
                    toolName, context.getWorkspaceRoot(), normalized, context);
            return commandLoopArguments(toolName, normalized, command, workingDirectory,
                    this.networkRequested(normalized));
        } catch (RuntimeException ignored) {
            return args;
        }
    }

    /** 循环保护比较真实命令身份，而不是模型使用的 strategy 包装。 */
    static JsonObject commandLoopArguments(String toolName, JsonObject originalArguments,
                                           String resolvedCommand, String workingDirectory,
                                           boolean networkRequested) {
        String safeTool = toolName == null ? "" : toolName.trim();
        boolean commandTool = "run_tests".equals(safeTool) || "shell".equals(safeTool) || "bash".equals(safeTool) || "start_preview".equals(safeTool);
        if (!commandTool || resolvedCommand == null || resolvedCommand.isBlank()) {
            return originalArguments;
        }
        JsonObject normalized = new JsonObject();
        normalized.addProperty("command", resolvedCommand.trim());
        normalized.addProperty("working_directory",
                workingDirectory == null || workingDirectory.isBlank() ? "." : workingDirectory.trim().replace('\\', '/'));
        if (networkRequested) {
            normalized.addProperty("network", true);
        }
        if ("start_preview".equals(safeTool) && originalArguments != null
                && originalArguments.has("port") && !originalArguments.get("port").isJsonNull()) {
            normalized.add("port", originalArguments.get("port").deepCopy());
        }
        return normalized;
    }

    private CommandClassification commandClassification(String toolName, JsonObject args, AgentContext context) {
        String command = this.commandForGuard(toolName, args, context);
        String workingDirectory = this.commandWorkingDirectoryForArgs(
                toolName, context.getWorkspaceRoot(), args, context);
        if (command.isBlank()) return null;
        int timeout = this.commandTimeout(toolName, args, context);
        String shell = this.usesWorkerShellContract(toolName) ? "shell" : "direct";
        return this.commandClassifier.classify(new CommandRequest(
                command, shell, workingDirectory, timeout, this.isPreviewTool(toolName), this.isPreviewTool(toolName),
                this.executionProperties.getPermissionProfile()));
    }

    /** 网络访问默认开启；网络命令直接执行，不再进入审批流。 */

    /**
     * Enforces one pending approval per task: while the task is already waiting for an approval or
     * user interaction, creating a second approval is rejected instead of stacking a second card.
     */
    private ToolResult singleApprovalGuard(AgentContext ctx, String toolName, String toolCallId) {
        if (ctx == null || ctx.getTaskId() == null || this.taskService == null) {
            return null;
        }
        try {
            AgentTask task = this.taskService.getOwnedTask(
                    ctx.getStudentId(), ctx.getProject().getProjectId(), ctx.getTaskId());
            if (task == null) {
                return null;
            }
            String status = task.getStatus();
            if ("waiting_approval".equals(status) || "waiting_user".equals(status)) {
                log.warn("SINGLE_APPROVAL_GUARD taskId={} tool={} toolCallId={} existingStatus={}",
                        ctx.getTaskId(), toolName, toolCallId, status);
                return ToolResult.failed("runtime_protocol_error=single_approval_guard\n"
                        + "任务已有挂起的审批或交互等待处理；同一会话同时只允许一个审批，请等待现有审批完成后再继续。");
            }
        } catch (RuntimeException failure) {
            log.debug("SINGLE_APPROVAL_GUARD_SKIPPED taskId={} reason={}",
                    ctx.getTaskId(), failure.getMessage());
        }
        return null;
    }

    private String commandForGuard(String toolName, JsonObject args, AgentContext context) {
        if ("run_tests".equals(toolName)) {
            return String.join(" ", this.resolvedTestCommand(context, args).command());
        }
        return this.permissionInput(toolName, args);
    }

    private TestCommandResolver.ResolvedTestCommand resolvedTestCommand(AgentContext context, JsonObject args) {
        VerificationStrategy strategy = context.isEnvironmentRecovery()
                ? this.recoveryProperties.getVerificationStrategy()
                : this.verificationStrategy(args);
        return TestCommandResolver.resolveProject(
                context.getWorkspaceRoot(), strategy, this.recoveryProperties.getFallbackVerificationStrategy(),
                this.verificationTargets(context, args));
    }

    private List<String> verificationTargets(AgentContext context, JsonObject args) {
        if (args != null && args.has("target_path") && !args.get("target_path").isJsonNull()) {
            String target = args.get("target_path").getAsString();
            if (target != null && !target.isBlank()) {
                return List.of(target.trim().replace('\\', '/'));
            }
        }
        return context.getUnverifiedChangeTargets().stream().sorted().toList();
    }

    private VerificationStrategy verificationStrategy(JsonObject args) {
        String requested = args != null && args.has("strategy") ? args.get("strategy").getAsString() : null;
        return VerificationStrategy.parse(requested, this.recoveryProperties.getVerificationStrategy());
    }

    private JsonObject normalizeCommandArguments(String toolName, JsonObject args) {
        if (!this.isShellTool(toolName) || args == null || !this.executionProperties.isSafeProfile()) {
            return args;
        }
        String command = ToolSupport.stringArgMulti(args, "", "command", "cmd", "shell_command");
        String workingDirectory = ToolSupport.stringArgMulti(
                args, "", "workdir", "working_directory", "workingDirectory", "cwd");
        DirectCommandWorkingDirectory.Normalized normalized =
                DirectCommandWorkingDirectory.normalize(command, workingDirectory);
        if (!normalized.rewritten()) {
            return args;
        }
        JsonObject effective = args.deepCopy();
        effective.addProperty("command", normalized.command());
        effective.addProperty("working_directory", normalized.workingDirectory());
        return effective;
    }

    private String commandWorkingDirectoryForArgs(
            String toolName, Path workspaceRoot, JsonObject args, AgentContext context) {
        if (this.isShellTool(toolName) || this.isPreviewTool(toolName)) {
            String requested = ToolSupport.stringArgMulti(
                    args, ".", "workdir", "working_directory", "workingDirectory", "cwd");
            return requested == null || requested.isBlank() ? "." : requested.replace('\\', '/');
        }
        if ("run_tests".equals(toolName)) {
            return relativeWorkingDirectory(workspaceRoot, this.resolvedTestCommand(context, args));
        }
        return ".";
    }

    /** 将固定解析出的验证项目目录绑定到审批和失败熔断指纹。 */
    static String commandWorkingDirectory(String toolName, Path workspaceRoot) {
        return commandWorkingDirectory(toolName, workspaceRoot, null);
    }

    static String commandWorkingDirectory(String toolName, Path workspaceRoot, JsonObject args) {
        if (("shell".equals(toolName) || "bash".equals(toolName) || "start_preview".equals(toolName)) && args != null) {
            String requested = ToolSupport.stringArgMulti(
                    args, ".", "workdir", "working_directory", "workingDirectory", "cwd");
            return requested == null || requested.isBlank() ? "." : requested.replace('\\', '/');
        }
        if (!"run_tests".equals(toolName) || workspaceRoot == null) return ".";
        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(
                workspaceRoot, VerificationStrategy.parse(
                        args != null && args.has("strategy") ? args.get("strategy").getAsString() : null,
                        VerificationStrategy.AUTO), null);
        return relativeWorkingDirectory(workspaceRoot, resolved);
    }

    private static String relativeWorkingDirectory(
            Path workspaceRoot, TestCommandResolver.ResolvedTestCommand resolved) {
        if (workspaceRoot == null || resolved == null
                || resolved.workingDirectory() == null || resolved.command().isEmpty()) return ".";
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path workingDirectory = resolved.workingDirectory().toAbsolutePath().normalize();
        if (!workingDirectory.startsWith(root)) return ".";
        String relative = root.relativize(workingDirectory).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private int commandTimeout(String toolName, JsonObject args, AgentContext context) {
        int defaultTimeout = "run_tests".equals(toolName)
                ? TestCommandResolver.defaultTimeoutSeconds(this.resolvedTestCommand(context, args).command())
                : 60;
        try {
            if (this.isShellTool(toolName) && args != null && args.has("timeout") && !args.get("timeout").isJsonNull()) {
                int milliseconds = args.get("timeout").getAsInt();
                return Math.min(600, Math.max(1, (int) Math.ceil(milliseconds / 1_000.0)));
            }
            int requested = args != null && args.has("timeout_seconds")
                    ? args.get("timeout_seconds").getAsInt() : defaultTimeout;
            return Math.min(600, Math.max(1, requested));
        } catch (RuntimeException exception) {
            return defaultTimeout;
        }
    }

    private boolean isWorkspaceMutationTool(String name) {
        String tool = this.safeTool(name);
        return "write_file".equals(tool) || "write".equals(tool)
                || "edit_file".equals(tool) || "edit".equals(tool)
                || "apply_patch".equals(tool) || "patch".equals(tool);
    }

    private boolean isCommandPolicyTool(String name) {
        return this.isShellTool(name) || this.isPreviewTool(name) || "run_tests".equals(name);
    }

    private int shellTimeout(JsonObject args) {
        try {
            if (args != null && args.has("timeout") && !args.get("timeout").isJsonNull()) {
                return Math.min(600, Math.max(1, (int) Math.ceil(args.get("timeout").getAsInt() / 1_000.0)));
            }
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

    private boolean isPreviewTool(String name) {
        return "start_preview".equals(name);
    }

    /** 默认 profile 使用完整 Worker Shell 语义；safe 仅保留为旧 direct-command 兼容开关。 */
    private boolean usesWorkerShellContract(String name) {
        return (this.isShellTool(name) || this.isPreviewTool(name)) && this.executionProperties != null
                && !this.executionProperties.isSafeProfile();
    }

    private String commandApprovalShell(String toolName) {
        return this.usesWorkerShellContract(toolName) ? "shell" : "direct";
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
        if (result == null || result.getApprovalId() == null || result.getApprovalId().isBlank()) {
            throw new IllegalStateException("Command approval requires a persisted approvalId before entering waiting_approval");
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
        if (result == null || result.getApprovalId() == null || result.getApprovalId().isBlank()) {
            throw new IllegalStateException("Command approval requires a persisted approvalId before entering waiting_approval");
        }
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
        if ("run_tests".equals(this.safeTool(toolName)) && arguments != null && arguments.has("target_path")) {
            publicArguments.add("target_path", arguments.get("target_path"));
        }
        if ("start_preview".equals(this.safeTool(toolName)) && arguments != null && arguments.has("port")) {
            publicArguments.add("port", arguments.get("port"));
        }
        if (arguments != null && arguments.has("working_directory")) {
            publicArguments.add("working_directory", arguments.get("working_directory"));
        }
        if (arguments != null && arguments.has("timeout_seconds")) {
            publicArguments.add("timeout_seconds", arguments.get("timeout_seconds"));
        }
        return publicArguments;
    }

    private void sendObserve(AgentSsePublisher sse, AgentConversation conv, int i, String tn, ToolResult r, Long tid, String toolCallId) throws Exception {
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
        // 结构化执行状态随事件下发，前端不解析输出文本猜测 timed_out/cancelled。
        if (r.getExecutionStatus() != null) {
            o.put("executionStatus", r.getExecutionStatus());
        }
        if (r.getExecutionExitCode() != null) {
            o.put("executionExitCode", r.getExecutionExitCode());
        }
        if (r.getExecutionDurationMs() != null) {
            o.put("executionDurationMs", r.getExecutionDurationMs());
        }
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
        o.put("toolCallId", toolCallId == null ? "" : toolCallId);
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

    /** 对齐 opencode 的 max-steps 注入：哨兵作为最后一条 assistant 消息追加，不写入 transcript（派生只读）。 */
    static List<Map<String, Object>> withMaxStepsSentinel(List<Map<String, Object>> messages) {
        ArrayList<Map<String, Object>> result = new ArrayList<>(messages.size() + 1);
        result.addAll(messages);
        result.add(Map.of("role", "assistant", "content", MAX_STEPS_SENTINEL));
        return List.copyOf(result);
    }

    private boolean transcriptHasToolMessages(Long taskId, long executionEpoch) {
        if (taskId == null) {
            return false;
        }
        try {
            return this.providerMessagesForInvocation(taskId, executionEpoch).stream()
                    .anyMatch(m -> "tool".equals(m.get("role")));
        } catch (RuntimeException e) {
            log.warn("Unable to inspect tool activity for task {}: {}", taskId, e.getMessage());
            return true;
        }
    }

    private String buildEngineeringIntentInstruction() {
        return "Intent rule: when a request could be interpreted as either a question to answer or a task to complete, treat it as a task. "
                + "Your request contains engineering action words, but no tool has been called in this task yet. "
                + "Do not end with an ungrounded text-only promise. Use the most relevant available tool to do the work. "
                + "For multi-step work, todo_write is optional progress display, not a prerequisite. "
                + "If the user only wants an explanation, confirm with the question tool before ending.";
    }

    /**
     * 判断用户请求是否为需要动手的工程任务（对齐 opencode 的意图默认值规则：
     * 可问可做一律按任务处理；只有明显的解释/分析类请求才豁免）。
     */
    static boolean isEngineeringTaskRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) {
            return false;
        }
        String lower = userRequest.toLowerCase(Locale.ROOT);
        for (String marker : ANALYSIS_ENGINEERING_WORDS) {
            if (lower.contains(marker)) {
                return false;
            }
        }
        if (ENGINEERING_ACTION_WORD.matcher(lower).find()) {
            return true;
        }
        for (String marker : ENGINEERING_WORDS_ZH) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static boolean isPrematureFinal(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String normalized = text.trim().replaceAll("\\s+", "");
        if (normalized.matches("^(next step )?operation complete[.!?]*$")) { return true; }
        if (normalized.matches("^task complete[.!?]*$")) { return true; }
        if (normalized.matches("^done[.!?]*$")) { return true; }
        return normalized.matches("^(ok|okay|OK|good|great|done|finished).*");
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

    private String buildContinuationInstruction(AgentContext ctx) {
        String todo = ctx == null ? "" : ctx.getPlanSummary();
        String todoNote = todo == null || todo.isBlank() ? "" : " Optional todo progress:\n" + todo;
        return "Task is not finished yet. Use the most relevant available tool for the next concrete step. "
                + "Do not add unrelated installs, broad tests, or preview work unless the request or evidence requires them."
                + todoNote;
    }

    private String buildRecoverableErrorGuidance(String errMsg, int retryCount, long delayMs) {
        return "Model connection recoverable error (attempt " + retryCount + "): " + this.limitForThought(errMsg, 180) + ". This usually means cloud handshake, proxy, TLS or temporary link interruption, not project code failure. Will wait " + delayMs + "ms then continue from existing conversation, task plan and this round's log. Will first confirm which tool steps succeeded, then resume from next unfinished action. If same step fails again, will narrow tool scope or re-read project state.";
    }

    /**
     * 恢复路径的精确模型解析：只接受 task 持久化选定的、仍归属该用户且启用的配置。
     * 缺失、禁用或未持久化（旧任务 null 值）一律抛 {@link AgentRunConfigurationException}
     * 结构化 fail closed，绝不调用 resolveForStudent 回退，也绝不让调度器无限重复投递。
     */
    private AgentModelConfig resolveResumeModelConfig(Integer studentId, AgentTask task) {
        Integer configId = task == null ? null : task.getModelConfigId();
        if (configId == null) {
            throw new AgentRunConfigurationException(
                    AgentRunConfigurationException.Reason.MODEL_CONFIG_NOT_PERSISTED,
                    "taskId=" + (task == null ? "null" : task.getTaskId()));
        }
        AgentModelConfig modelConfig = this.modelConfigService.resolveOwnedEnabled(studentId, configId);
        if (modelConfig == null) {
            AgentModelConfig owned = this.modelConfigService.getOwned(studentId, configId);
            if (owned == null) {
                throw new AgentRunConfigurationException(
                        AgentRunConfigurationException.Reason.MODEL_CONFIG_MISSING,
                        "configId=" + configId);
            }
            throw new AgentRunConfigurationException(
                    AgentRunConfigurationException.Reason.MODEL_CONFIG_DISABLED,
                    "configId=" + configId);
        }
        return modelConfig;
    }

    /**
     * 确保恢复/接管路径的当前 epoch 拥有不可变配置快照：无快照的旧任务按 task 持久化的精确
     * 模型引用创建迁移快照（任何引用不可解析即结构化 fail closed，不使用默认回退）；快照 epoch
     * 落后于 lease epoch 时复制同一 effective revision 到新 epoch；旧 epoch 行永不修改。
     * 全部写入都要求 active fence；手工构造的测试实例未接线时跳过（Spring 运行时为必填依赖）。
     */
    private void ensureSnapshotForCurrentEpoch(AgentTask task, StudentProject project,
                                               AgentRunExecutionLeaseService.ExecutionLease executionLease,
                                               ExecutionFence executionFence) {
        if (this.runConfigSnapshotService == null || executionFence == null) {
            return;
        }
        long epoch = executionLease == null ? 0L : executionLease.epoch();
        AgentRunConfigSnapshot latest = this.runConfigSnapshotService.getLatest(task.getTaskId());
        if (latest == null) {
            this.runConfigSnapshotService.createMigrationSnapshot(task, project, executionFence);
        } else if (latest.getExecutionEpoch() < epoch) {
            this.runConfigSnapshotService.copyForNewEpoch(task.getTaskId(), epoch, executionFence);
        }
    }

    private String buildSystemPrompt(StudentProject project, String toolDefinitions, String visibleLanguage) {
        return buildSystemPrompt(project, toolDefinitions, visibleLanguage, AgentRuntimeProfile.LABEX_LEGACY);
    }

    private String buildSystemPrompt(StudentProject project, String toolDefinitions, String visibleLanguage,
                                     AgentRuntimeProfile runtimeProfile) {
        String permissionProfile = this.executionProperties == null
                ? AgentExecutionProperties.STANDARD_PROFILE : this.executionProperties.getPermissionProfile();
        return LabexSystemPrompt.buildSystemPrompt(project, toolDefinitions, visibleLanguage,
                this.shellPromptDescriptor(project), permissionProfile, runtimeProfile);
    }

    private WorkerShellDescriptor shellPromptDescriptor(StudentProject project) {
        boolean networkEnabled = this.executionProperties != null && this.executionProperties.isNetworkDefaultEnabled();
        try {
            if (this.sandboxWorker != null) {
                Path workspaceRoot = Path.of(".");
                if (project != null && project.getWorkspacePath() != null && !project.getWorkspacePath().isBlank()) {
                    workspaceRoot = ProjectWorkspace.paths(project).workspaceRoot();
                }
                WorkerRunSpec run = WorkerRunSpec.forWorkspace("prompt-projection", workspaceRoot, networkEnabled);
                WorkerShellDescriptor descriptor = this.sandboxWorker.shellDescriptor(run);
                if (descriptor != null) {
                    return descriptor;
                }
                if (this.sandboxWorker.usesLinuxShell()) {
                    return WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", networkEnabled);
                }
            }
        } catch (RuntimeException ignored) {
            log.debug("Unable to resolve worker shell prompt descriptor", ignored);
        }
        return WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", networkEnabled);
    }

    /**
     * 可重建的运行时投影：mode policy、选中工具、工具 schema、系统提示词与 prompt-cache key。
     * cache key 按 conversation 和模型路由稳定；plan_exit 后仍需重建 mode 派生的工具与系统提示词。
     */
    private RunRuntimeProjection buildRunRuntimeProjection(Integer studentId, String conversationId, StudentProject project, String mode,
                                                           AgentModelConfig modelConfig,
                                                           LlmProvider.LlmConfig baseLlmConfig,
                                                           String visibleLanguage,
                                                           AgentRuntimeProfile runtimeProfile) {
        List<ToolDefinition> selectedTools = this.selectToolDefinitions(studentId, mode, modelConfig, runtimeProfile);
        String toolDefinitions = this.buildToolDefinitions(selectedTools);
        String systemPrompt = this.buildSystemPrompt(project, toolDefinitions, visibleLanguage, runtimeProfile);
        List<Map<String, Object>> tools = new ArrayList<>(this.buildToolsList(selectedTools));
        LlmProvider.LlmConfig configured = baseLlmConfig == null ? null : baseLlmConfig.withPromptCacheKey(
                PromptCacheKeyFactory.forConversation(studentId, modelConfig.getConfigId(),
                        baseLlmConfig.baseUrl(), baseLlmConfig.modelName(), conversationId));
        return new RunRuntimeProjection(mode, selectedTools, toolDefinitions, systemPrompt, tools,
                this.buildModePolicy(mode), configured);
    }

    /** 当前 mode 派生的 Provider 请求投影；mode 变更后必须整体重建。 */
    private record RunRuntimeProjection(String mode, List<ToolDefinition> selectedTools, String toolDefinitions,
                                        String systemPrompt, List<Map<String, Object>> tools, String modePolicy,
                                        LlmProvider.LlmConfig llmConfig) {
    }

    private List<ToolDefinition> selectToolDefinitions(Integer studentId, String mode, AgentModelConfig modelConfig) {
        return selectToolDefinitions(studentId, mode, modelConfig, AgentRuntimeProfile.LABEX_LEGACY);
    }

    private List<ToolDefinition> selectToolDefinitions(Integer studentId, String mode, AgentModelConfig modelConfig,
                                                        AgentRuntimeProfile runtimeProfile) {
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
        return this.toolSelectionPolicy.select(this.toolRegistry, mode, capabilities, runtimeProfile);
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

    /** 已恢复的 durable transcript 已含原始用户目标，调度元数据不能再次写成用户消息。 */
    static boolean shouldAppendRequestMessageToTranscript(boolean resumedRun, boolean transcriptRestored) {
        return !transcriptRestored;
    }

    /** Provider transcript、压缩选择等权威输入只读取持久化 Message/Part。 */
    List<Map<String, Object>> providerMessagesForBudget(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalStateException("Provider projection requires a positive durable taskId");
        }
        return this.requireTranscriptProjectionService().loadProviderMessages(taskId);
    }

    /**
     * 在 Provider 调用边界附加只读运行时投影。
     *
     * <p>该消息不写回 transcript，避免把可重建派生状态变成第二事实源；预算、admission 与真实调用
     * 必须复用同一返回值。</p>
     */
    List<Map<String, Object>> providerMessagesForInvocation(Long taskId, long executionEpoch) {
        List<Map<String, Object>> durable = this.providerMessagesForBudget(taskId);
        AgentRunProgressProjectionService.Projection progress =
                this.requireRunProgressProjectionService().load(taskId, executionEpoch);
        ArrayList<Map<String, Object>> projected = new ArrayList<>(durable.size() + 1);
        projected.addAll(durable);
        projected.add(Map.of(
                "role", "user",
                "content", "<agent_runtime_projection purpose=\"derived_read_only\">\n"
                        + progress.renderForPrompt()
                        + "</agent_runtime_projection>"));
        return List.copyOf(projected);
    }

    private AgentCompactionService requireCompactionService() {
        if (this.compactionService == null) {
            throw new IllegalStateException("Durable compaction service is unavailable");
        }
        return this.compactionService;
    }

    private AgentLegacyCheckpointMigrationService requireLegacyCheckpointMigrationService() {
        return requireRuntimeDependency(
                this.legacyCheckpointMigrationService, "legacyCheckpointMigrationService");
    }

    private void projectModelStepStarted(AgentSsePublisher sse, AgentConversation conversation,
                                         AgentContext context, int iteration) throws Exception {
        this.projectModelStep(sse, conversation, context, iteration, "MODEL_STEP_STARTED", "", "");
    }

    private void projectModelStepCompleted(AgentSsePublisher sse, AgentConversation conversation,
                                           AgentContext context, int iteration, String resultType) throws Exception {
        this.projectModelStep(sse, conversation, context, iteration, "MODEL_STEP_COMPLETED", resultType, "");
    }

    private void projectModelStepFailed(AgentSsePublisher sse, AgentConversation conversation,
                                        AgentContext context, int iteration, Exception failure) throws Exception {
        String errorType = failure == null ? "unknown" : failure.getClass().getSimpleName();
        this.projectModelStep(sse, conversation, context, iteration, "MODEL_STEP_FAILED", "", errorType);
    }

    private void projectModelStepBlocked(AgentSsePublisher sse, AgentConversation conversation,
                                         AgentContext context, int iteration, String reason) throws Exception {
        this.projectModelStep(sse, conversation, context, iteration, "MODEL_STEP_BLOCKED", "", reason);
    }

    private void projectModelStepInterrupted(AgentSsePublisher sse, AgentConversation conversation,
                                             AgentContext context, int iteration, String reason) throws Exception {
        this.projectModelStep(sse, conversation, context, iteration, "MODEL_STEP_INTERRUPTED", "", reason);
    }

    private void projectModelStep(AgentSsePublisher sse, AgentConversation conversation,
                                  AgentContext context, int iteration, String eventType,
                                  String resultType, String reason) throws Exception {
        if (context == null || context.getTaskId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", context.getTaskId());
        payload.put("executionEpoch", context.getExecutionEpoch());
        payload.put("iteration", iteration);
        if (resultType != null && !resultType.isBlank()) {
            payload.put("resultType", resultType);
        }
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason);
        }
        this.sendEvent(sse, conversation, eventType, payload);
    }

    private Map<String, Object> finalizationBlockerPayload(Long taskId, long executionEpoch,
                                                         AgentRunFinalizer.CompletionAssessment completion,
                                                         AgentFinalizationRecoveryService.Decision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("executionEpoch", executionEpoch);
        payload.put("reasonCode", completion.code());
        payload.put("guidance", completion.guidance());
        payload.put("evidenceFingerprint", decision.evidenceFingerprint());
        payload.put("finalClaimFingerprint", decision.finalClaimFingerprint());
        payload.put("recoveryAttempt", decision.recoveryAttempt());
        payload.put("recoveryLimit", decision.recoveryLimit());
        payload.put("recoveryAllowed", decision.recoveryAllowed());
        return payload;
    }

    private String finalizationBlockerKey(Long taskId, long executionEpoch, String evidenceFingerprint,
                                          int recoveryAttempt, boolean recoveryAllowed) {
        String phase = recoveryAllowed ? "recovery-" + recoveryAttempt : "exhausted";
        return "finalization-blocked-" + taskId + "-" + executionEpoch + "-" + evidenceFingerprint + "-" + phase;
    }

    private void recordLoopNoProgress(AgentLoopGuard loopGuard, AgentSsePublisher sse,
                                      AgentConversation conversation, AgentContext context, int iteration,
                                      String reason) throws Exception {
        loopGuard.recordModelNoProgress();
        this.projectLoopGuardProgress(loopGuard, sse, conversation, context, iteration, reason, "");
    }

    private void recordLoopToolResult(AgentLoopGuard loopGuard, AgentSsePublisher sse,
                                      AgentConversation conversation, AgentContext context, int iteration,
                                      String toolName, String signature, boolean success) throws Exception {
        loopGuard.recordToolResult(signature, success);
        this.projectLoopGuardProgress(loopGuard, sse, conversation, context, iteration,
                success ? "tool_success" : "tool_failure", toolName);
    }

    private void projectLoopGuardProgress(AgentLoopGuard loopGuard, AgentSsePublisher sse,
                                          AgentConversation conversation, AgentContext context, int iteration,
                                          String reason, String toolName) throws Exception {
        if (loopGuard == null || context == null || context.getTaskId() == null) {
            return;
        }
        this.sendEvent(sse, conversation, "LOOP_GUARD_PROGRESS", Map.of(
                "taskId", context.getTaskId(),
                "executionEpoch", context.getExecutionEpoch(),
                "iteration", iteration,
                "reason", reason == null ? "" : reason,
                "tool", toolName == null ? "" : toolName,
                "nonProgressIterations", loopGuard.nonProgressIterations()));
    }

    private void restoreLoopGuardHistory(AgentLoopGuard loopGuard, AgentContext context) {
        if (loopGuard == null || context == null || context.getTaskId() == null || runPartService == null) {
            return;
        }
        for (AgentRunPart part : runPartService.currentEpochToolHistory(
                context.getTaskId(), context.getExecutionEpoch())) {
            JsonObject arguments = durableToolArguments(part == null ? null : part.getInputJson());
            loopGuard.restoreDurableToolCall(part == null ? "" : part.getToolName(),
                    this.loopGuardArguments(part == null ? "" : part.getToolName(), arguments, context));
        }
        loopGuard.restoreNonProgressIterations(runPartService.currentEpochLoopGuardProgress(
                context.getTaskId(), context.getExecutionEpoch()));
    }

    private JsonObject durableToolArguments(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(inputJson);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private String refreshLoopGuardProgress(AgentContext context) {
        if (context == null || context.getTaskId() == null || this.runProgressProjectionService == null) {
            return "";
        }
        AgentRunProgressProjectionService.Projection progress = this.runProgressProjectionService.load(
                context.getTaskId(), context.getExecutionEpoch());
        if (progress == null) {
            return "";
        }
        progress.applyTo(context);
        return progress.loopGuardProgressFingerprint();
    }

    private AgentRunProgressProjectionService requireRunProgressProjectionService() {
        return requireRuntimeDependency(this.runProgressProjectionService, "runProgressProjectionService");
    }

    private LabexNativeToolBatchExecutor requireNativeToolBatchExecutor() {
        return requireRuntimeDependency(this.nativeToolBatchExecutor, "nativeToolBatchExecutor");
    }

    private AgentProviderTranscriptAppender requireProviderTranscriptAppender() {
        return requireRuntimeDependency(this.providerTranscriptAppender, "providerTranscriptAppender");
    }

    private AgentRunTranscriptService requireTranscriptService() {
        if (this.transcriptService == null) {
            throw new IllegalStateException("Durable Provider transcript service is unavailable");
        }
        return this.transcriptService;
    }

    private AgentInputAttachmentService requireAttachmentService() {
        if (this.attachmentService == null) {
            throw new IllegalStateException("Agent image attachment service is unavailable");
        }
        return this.attachmentService;
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
        return this.contextAdmissionService.decideAfterContextManagement(breakdown, value.autoCompactionEnabled());
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
        payload.put("cacheHitTokens", intUsage(usageMap, "cache_hit_tokens"));
        payload.put("cacheMissTokens", intUsage(usageMap, "cache_miss_tokens"));
        payload.put("inputTokensNonCached",
                Math.max(0, intUsage(usageMap, "prompt_tokens") - intUsage(usageMap, "cached_tokens")));
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
        // 自动压缩只裁剪 durable transcript；只读运行时投影由后续硬门禁单独计入。
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
            return this.requestTokenEstimator.estimateValue(sysPrompt)
                    + this.requestTokenEstimator.estimateMessages(messages)
                    + this.requestTokenEstimator.estimateValue(tools);
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

    /**
     * Native profile 的 structured tool batch 入口。
     *
     * <p>生命周期、SSE 和现有工具执行包装仍由 Engine 持有；批内 tool-call 协议、提前 durable
     * 落库、确定性串行执行、审批/取消后的显式收尾委托给 {@link LabexNativeToolBatchExecutor}。</p>
     */
    private NativeToolBatchProcessingOutcome processLabexNativeToolBatch(
            AgentSsePublisher sse, AgentConversation conversation, AgentTask task, StudentProject project,
            AgentStreamRequest request, AgentContext context, Path runLog, int iteration, long transcriptEpoch,
            String visibleLanguage, SseEmitter emitter, AgentLoopGuard loopGuard, CancellationToken cancellationToken,
            List<AgentModelTurnExecutor.NativeToolCall> nativeToolCalls, String modelContent, String modelThinking,
            int nativeToolInputFailureRounds) throws Exception {
        List<LabexNativeToolBatchExecutor.Admission> admissions = new ArrayList<>();
        for (AgentModelTurnExecutor.NativeToolCall call : nativeToolCalls) {
            AgentToolTurnExecutor.ToolInputResolution input = this.toolTurnExecutor.resolveNative(
                    context, call, visibleLanguage);
            JsonObject publicArguments = this.publicToolArguments(call.toolName(), input.arguments());
            admissions.add(new LabexNativeToolBatchExecutor.Admission(call, input, publicArguments));
        }
        boolean nativeInputRejected = admissions.stream().anyMatch(admission -> !admission.allowed());
        int nextInputFailureRounds = nativeInputRejected
                ? nativeToolInputFailureRounds + 1 : 0;

        // 当前连接只作展示投影；真正的 pending Part 由 batch executor 在执行任何工具前完整持久化。
        for (LabexNativeToolBatchExecutor.Admission admission : admissions) {
            AgentModelTurnExecutor.NativeToolCall call = admission.call();
            String eventSummary = admission.allowed()
                    ? this.toolNarrator.visibleActionSummary(call.toolName(), admission.publicArguments(), visibleLanguage)
                    : this.localText(visibleLanguage, "工具调用已拒绝", "Tool call rejected");
            String eventContent = admission.allowed()
                    ? this.toolNarrator.visibleActionDetail(call.toolName(), admission.publicArguments(), visibleLanguage)
                    : admission.rejection().getContent();
            this.sendEvent(sse, conversation, "TOOL_CALL", Map.of(
                    "iteration", iteration,
                    "tool", call.toolName(),
                    "arguments", admission.publicArguments(),
                    "summary", eventSummary,
                    "content", eventContent,
                    "taskId", task.getTaskId(),
                    "toolCallId", call.toolCallId(),
                    "toolCallIndex", call.toolCallIndex()));
        }

        Map<String, AgentLoopGuard.ToolDecision> loopDecisions = new LinkedHashMap<>();
        LabexNativeToolBatchExecutor.BatchResult batchResult = this.requireNativeToolBatchExecutor().execute(
                new LabexNativeToolBatchExecutor.BatchRequest(
                        context.getExecutionFence(), task.getTaskId(), transcriptEpoch, iteration,
                        modelContent, admissions,
                        cancellationToken == null ? () -> false : cancellationToken::isCancellationRequested),
                admission -> {
                    AgentModelTurnExecutor.NativeToolCall call = admission.call();
                    String toolName = call.toolName();
                    JsonObject arguments = admission.arguments();
                    JsonObject publicArguments = admission.publicArguments();
                    String toolCallId = call.toolCallId();
                    this.appendRunLog(runLog, "\n### Tool call\n\n- Tool: `" + this.safeLogText(toolName)
                            + "`\n- Args:\n\n```json\n" + GSON.toJson((JsonElement) publicArguments) + "\n```\n");
                    if (modelThinking.isBlank()) {
                        this.sendThought(sse, conversation, iteration,
                                this.toolNarrator.visibleActionSummary(toolName, publicArguments, visibleLanguage),
                                this.toolNarrator.buildToolThought(toolName, publicArguments, false, visibleLanguage),
                                task.getTaskId());
                    }

                    JsonObject loopArguments = this.loopGuardArguments(toolName, arguments, context);
                    AgentLoopGuard.ToolDecision loopDecision = loopGuard.beforeToolCall(
                            toolName, loopArguments, this.refreshLoopGuardProgress(context));
                    if (loopDecision.action() != AgentLoopGuard.ToolAction.ALLOW) {
                        String loopMessage = this.loopGuardMessage(loopDecision, toolName, visibleLanguage);
                        ToolResult blockedResult = this.loopGuardResult(
                                loopDecision, toolName, toolCallId, context, visibleLanguage, loopMessage);
                        loopDecisions.put(toolCallId, loopDecision);
                        return LabexNativeToolBatchExecutor.CallExecution.loopGuardBlocked(blockedResult);
                    }

                    this.journalToolRunning(context.getExecutionFence(), task.getTaskId(), toolCallId, toolName,
                            publicArguments, iteration);
                    ToolResult result = this.execTool(toolName, arguments, context, sse, conversation,
                            visibleLanguage, toolCallId, runLog);
                    this.recordLoopToolResult(loopGuard, sse, conversation, context, iteration, toolName,
                            loopDecision.signature(), result.isSuccess());
                    return LabexNativeToolBatchExecutor.CallExecution.completed(result);
                },
                this::nativeBatchToolResultForModel);

        for (LabexNativeToolBatchExecutor.Outcome outcome : batchResult.outcomes()) {
            this.projectLabexNativeToolBatchOutcome(sse, conversation, task, context, runLog, iteration,
                    visibleLanguage, outcome);
        }

        if (batchResult.terminal() == LabexNativeToolBatchExecutor.Terminal.CONTINUE && nativeInputRejected) {
            boolean anyExecutableInput = admissions.stream().anyMatch(LabexNativeToolBatchExecutor.Admission::allowed);
            if (!anyExecutableInput) {
                this.recordLoopNoProgress(loopGuard, sse, conversation, context, iteration,
                        "native_tool_input_rejected");
            }
            if (nextInputFailureRounds >= MAX_NATIVE_TOOL_INPUT_FAILURE_ROUNDS) {
                String failureTitle = this.localText(visibleLanguage,
                        "原生工具调用参数连续无效", "Native tool-call arguments repeatedly invalid");
                String failureReason = this.localText(visibleLanguage,
                        "模型连续返回无法安全解析或不符合 schema 的原生工具参数，运行已停止，避免误执行或无限重试。",
                        "The model repeatedly returned native tool arguments that could not be parsed or did not match the exposed schema. The run stopped to prevent unsafe execution or an infinite retry.");
                this.sendEvent(sse, conversation, "ERROR", Map.of(
                        "message", failureTitle,
                        "iteration", iteration,
                        "reasonCode", "native_tool_input_recovery_exhausted"));
                this.streamFinal(sse, conversation,
                        this.buildStopFinal(failureTitle, failureReason, project, runLog, visibleLanguage),
                        visibleLanguage);
                this.failTaskAndProject(sse, conversation, task, failureTitle, failureReason);
                this.sendEvent(sse, conversation, "DONE", Map.of(
                        "message", failureTitle,
                        "iterations", iteration,
                        "reasonCode", "native_tool_input_recovery_exhausted"));
                emitter.complete();
                return NativeToolBatchProcessingOutcome.stopped(nextInputFailureRounds);
            }
            this.appendProviderMessage(context.getExecutionFence(), task.getTaskId(), transcriptEpoch,
                    Map.of("role", "user", "content",
                            "One or more native tool calls were rejected before execution because their arguments were missing, malformed, not a JSON object, unavailable in this turn, or incompatible with the exposed schema. "
                                    + "Retry with complete JSON object arguments that exactly match the selected tool schema. Do not repeat rejected arguments."));
        }

        LabexNativeToolBatchExecutor.Outcome terminalOutcome = batchResult.terminalOutcome();
        switch (batchResult.terminal()) {
            case CONTINUE:
                return NativeToolBatchProcessingOutcome.continues(nextInputFailureRounds);
            case CANCELLED:
                this.completeCancelledRun(sse, conversation, task, project, runLog, iteration, visibleLanguage, emitter);
                return NativeToolBatchProcessingOutcome.stopped(nextInputFailureRounds);
            case ENVIRONMENT_BLOCKED:
                if (terminalOutcome == null) {
                    throw new IllegalStateException("Environment-blocked native batch has no terminal tool outcome");
                }
                EnvironmentBlockerClassifier.Blocker blocker = EnvironmentBlockerClassifier.classify(
                                terminalOutcome.admission().call().toolName(), terminalOutcome.result())
                        .orElseThrow(() -> new IllegalStateException("Environment blocker classification was lost"));
                this.stopForEnvironmentBlocker(sse, conversation, task, project, request, context, runLog, iteration,
                        terminalOutcome.admission().call().toolName(), terminalOutcome.result(), blocker,
                        visibleLanguage, emitter);
                return NativeToolBatchProcessingOutcome.stopped(nextInputFailureRounds);
            case WAITING_APPROVAL:
                if (terminalOutcome == null) {
                    throw new IllegalStateException("Approval-waiting native batch has no terminal tool outcome");
                }
                this.stopForCommandApproval(sse, conversation, task, project, request, context, runLog, iteration,
                        terminalOutcome.admission().call().toolCallId(), terminalOutcome.admission().call().toolName(),
                        terminalOutcome.result(), visibleLanguage, emitter);
                return NativeToolBatchProcessingOutcome.stopped(nextInputFailureRounds);
            case WAITING_USER:
                if (terminalOutcome == null) {
                    throw new IllegalStateException("User-interaction native batch has no terminal tool outcome");
                }
                ToolResult interactionResult = terminalOutcome.result();
                AgentInteractionPauser.Pause pause = this.interactionPauser.pause(
                        task.getTaskId(), interactionResult, visibleLanguage);
                this.publishUserQuestion(sse, conversation, interactionResult);
                this.appendRunLog(runLog, "\n- Durable user interaction pending: type=`"
                        + this.safeLogText(interactionResult.getInteractionType()) + "`, requestId=`"
                        + this.safeLogText(interactionResult.getInteractionRequestId()) + "`\n");
                this.publishInteractionPause(sse, conversation, task.getTaskId(), interactionResult, pause, iteration);
                emitter.complete();
                return NativeToolBatchProcessingOutcome.stopped(nextInputFailureRounds);
            case LOOP_GUARD:
                if (terminalOutcome == null) {
                    throw new IllegalStateException("Loop-guard native batch has no terminal tool outcome");
                }
                AgentModelTurnExecutor.NativeToolCall loopCall = terminalOutcome.admission().call();
                AgentLoopGuard.ToolDecision loopDecision = loopDecisions.get(loopCall.toolCallId());
                if (loopDecision == null) {
                    throw new IllegalStateException("Loop-guard native batch lost its decision");
                }
                String loopMessage = terminalOutcome.result().getContent() == null
                        ? this.loopGuardMessage(loopDecision, loopCall.toolName(), visibleLanguage)
                        : terminalOutcome.result().getContent();
                String visibleSignature = loopCall.toolName() + ":" + this.toolNarrator.toolTarget(
                        this.safeTool(loopCall.toolName()), terminalOutcome.admission().publicArguments());
                this.appendRunLog(runLog, "\n- " + loopMessage + "\n");
                this.sendThought(sse, conversation, iteration,
                        this.localText(visibleLanguage, "检测到重复操作", "Loop detected"),
                        loopMessage, task.getTaskId());
                this.sendEvent(sse, conversation, "LOOP_GUARD", Map.of(
                        "iteration", iteration,
                        "tool", loopCall.toolName(),
                        "signature", visibleSignature,
                        "action", loopDecision.action().name().toLowerCase(Locale.ROOT),
                        "cycleLength", loopDecision.cycleLength(),
                        "message", loopMessage));
                this.metricsService.recordLoopGuard(context, visibleSignature, iteration);
                if (terminalOutcome.result().isInteractionRequired()) {
                    AgentInteractionPauser.Pause loopPause = this.interactionPauser.pause(
                            task.getTaskId(), terminalOutcome.result(), visibleLanguage);
                    this.publishUserQuestion(sse, conversation, terminalOutcome.result());
                    this.publishInteractionPause(sse, conversation, task.getTaskId(), terminalOutcome.result(),
                            loopPause, iteration);
                    emitter.complete();
                    return NativeToolBatchProcessingOutcome.stopped(nextInputFailureRounds);
                }
                this.appendProviderMessage(context.getExecutionFence(), task.getTaskId(), transcriptEpoch,
                        Map.of("role", "user", "content", "[Loop guard]\n" + loopMessage
                                + "\nDo not repeat the blocked pattern. Change the tool, target, scope, or verification method; use existing evidence; or finish if the task is complete."));
                return NativeToolBatchProcessingOutcome.continues(nextInputFailureRounds);
        }
        throw new IllegalStateException("Unhandled native tool batch terminal state: " + batchResult.terminal());
    }

    private String nativeBatchToolResultForModel(LabexNativeToolBatchExecutor.Admission admission,
                                                  ToolResult result,
                                                  LabexNativeToolBatchExecutor.ProjectionKind kind) {
        String toolName = admission == null || admission.call() == null ? "unknown" : admission.call().toolName();
        String toolCallId = admission == null || admission.call() == null ? "" : admission.call().toolCallId();
        if (kind == LabexNativeToolBatchExecutor.ProjectionKind.LOOP_GUARD
                || kind == LabexNativeToolBatchExecutor.ProjectionKind.SKIPPED
                || kind == LabexNativeToolBatchExecutor.ProjectionKind.INTERRUPTED) {
            return result == null || result.getContent() == null ? "" : result.getContent();
        }
        String compact = this.compactToolResultForModel(toolName, result, toolCallId);
        if (kind == LabexNativeToolBatchExecutor.ProjectionKind.RESULT) {
            return compact + "\nContinue using tools only when they are needed to resolve the request. Finish when the available evidence supports the result.";
        }
        return compact;
    }

    private void projectLabexNativeToolBatchOutcome(AgentSsePublisher sse, AgentConversation conversation,
                                                    AgentTask task, AgentContext context, Path runLog, int iteration,
                                                    String visibleLanguage,
                                                    LabexNativeToolBatchExecutor.Outcome outcome) throws Exception {
        if (outcome == null || outcome.admission() == null || outcome.admission().call() == null) {
            return;
        }
        AgentModelTurnExecutor.NativeToolCall call = outcome.admission().call();
        String toolName = call.toolName();
        String toolCallId = call.toolCallId();
        ToolResult result = outcome.result() == null ? ToolResult.failed("Tool returned no result") : outcome.result();
        switch (outcome.status()) {
            case REJECTED:
                this.appendRunLog(runLog, "\n- Native input rejected: `"
                        + this.safeLogText(outcome.admission().reasonCode()) + "`\n");
                this.sendThought(sse, conversation, iteration,
                        this.localText(visibleLanguage, "拒绝非法原生工具调用", "Rejected invalid native tool call"),
                        result.getContent() == null ? "" : result.getContent(), task.getTaskId());
                this.appendToolResult(runLog, result);
                this.sendObserve(sse, conversation, iteration, toolName, result, task.getTaskId(), toolCallId);
                return;
            case COMPLETED:
                this.appendToolResult(runLog, result);
                this.sendObserve(sse, conversation, iteration, toolName, result, task.getTaskId(), toolCallId);
                this.sendThought(sse, conversation, iteration,
                        this.localText(visibleLanguage, "检查结果", "Check result"),
                        this.toolNarrator.buildResultThought(toolName, outcome.admission().arguments(), result,
                                visibleLanguage), task.getTaskId());
                return;
            case LOOP_GUARD_BLOCKED:
            case ENVIRONMENT_BLOCKED:
            case WAITING_APPROVAL:
            case WAITING_USER:
            case INTERRUPTED:
                this.appendToolResult(runLog, result);
                return;
            case SKIPPED:
                return;
        }
    }

    private record NativeToolBatchProcessingOutcome(boolean stopRun, int nativeToolInputFailureRounds) {
        private static NativeToolBatchProcessingOutcome continues(int nativeToolInputFailureRounds) {
            return new NativeToolBatchProcessingOutcome(false, nativeToolInputFailureRounds);
        }

        private static NativeToolBatchProcessingOutcome stopped(int nativeToolInputFailureRounds) {
            return new NativeToolBatchProcessingOutcome(true, nativeToolInputFailureRounds);
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

    private RunLogTarget resolveRunLog(StudentProject project, AgentStreamRequest request,
                                             AgentTask task, boolean resumedRun) {
        if (resumedRun) {
            Path existing = this.restoreRunLog(project, task);
            if (existing != null) {
                return new RunLogTarget(existing, true);
            }
        }
        return new RunLogTarget(this.createRunLog(project, request), false);
    }

    /** 恢复执行必须沿用持久化 SESSION 事件中的日志路径，不能制造第二段伪会话。 */
    private Path restoreRunLog(StudentProject project, AgentTask task) {
        if (task == null || task.getTaskId() == null) return null;
        try {
            long executionEpoch = task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch();
            String candidate = this.requireRunProgressProjectionService()
                    .load(task.getTaskId(), executionEpoch)
                    .runLogPath();
            String normalized = candidate == null ? "" : candidate.strip().replace('\\', '/');
            if (!normalized.startsWith(".labex/agent-logs/")
                    || !normalized.endsWith(".md")
                    || normalized.contains("../")) {
                return null;
            }
            Path restored = ProjectWorkspace.paths(project).resolveForCreate(normalized);
            Files.createDirectories(restored.getParent());
            return restored;
        } catch (RuntimeException | IOException failure) {
            log.warn("Unable to restore durable agent run log for task {}: {}",
                    task.getTaskId(), failure.getMessage());
            return null;
        }
    }

    private String runLogHeader(StudentProject project, Integer studentId, AgentStreamRequest request,
                                AgentTask task, String userVisibleMessage,
                                boolean resumedRun, boolean reusedRunLog) {
        if (!resumedRun) {
            return "# LabexAgent run log\n\n- Session: `" + this.safeLogText(request.getSessionId())
                    + "`\n- Project: `" + this.safeLogText(project.getProjectName())
                    + "`\n- Student: `" + studentId
                    + "`\n- Start time: `" + LocalDateTime.now()
                    + "`\n\n## User input\n\n" + this.safeLogText(userVisibleMessage) + "\n";
        }
        String continuation = this.safeLogText(request.getResumeNote());
        String section = "\n\n---\n\n## Durable continuation\n\n- Resumed at: `" + LocalDateTime.now()
                + "`\n- Task: `" + (task == null ? "" : task.getTaskId())
                + "`\n- Execution epoch: `" + (task == null || task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch())
                + "`\n- Same user turn: `true`\n"
                + (continuation.isBlank() ? "" : "\n### Resume reason\n\n" + continuation + "\n");
        if (reusedRunLog) {
            return section;
        }
        return "# LabexAgent run log\n\n- Session: `" + this.safeLogText(request.getSessionId())
                + "`\n- Project: `" + this.safeLogText(project.getProjectName())
                + "`\n- Student: `" + studentId
                + "`\n- Start time: `" + LocalDateTime.now()
                + "`\n- Durable task continuation: `true`\n\n## Original user objective\n\n"
                + this.safeLogText(userVisibleMessage) + "\n" + section;
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

    private record RunLogTarget(Path path, boolean reused) {
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

    /** 运行日志必须记录模型本轮全部原始输出：正文与思考过程均完整落盘，不截断。 */
    private String renderModelTurnOutput(Map<String, Object> modelTurnResult) {
        StringBuilder section = new StringBuilder();
        Object rawContent = modelTurnResult == null ? null : modelTurnResult.get("content");
        Object rawThinking = modelTurnResult == null ? null : modelTurnResult.get("thinking");
        if (rawContent != null && !rawContent.toString().isEmpty()) {
            section.append("\n### Model output (verbatim)\n\n")
                    .append(this.fencedLogText(rawContent.toString()))
                    .append("\n");
        }
        if (rawThinking != null && !rawThinking.toString().isBlank()) {
            section.append("\n### Model thinking (verbatim)\n\n")
                    .append(this.fencedLogText(rawThinking.toString()))
                    .append("\n");
        }
        return section.toString();
    }

    /** 用比内容中最长反引号串更长的 fence 包裹，避免模型输出里的 ``` 破坏日志代码块。 */
    private String fencedLogText(String body) {
        String safe = this.safeLogText(body);
        String fence = "```";
        while (safe.contains(fence)) {
            fence = fence + "`";
        }
        return fence + "text\n" + safe + "\n" + fence;
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
        return ToolSchemaCanonicalizer.openAiTools(definitions);
    }

    private void appendRemainingBatchToolResults(ExecutionFence executionFence,
                                                 Long taskId,
                                                 long transcriptEpoch,
                                                 List<NativeToolAdmission> admissions,
                                                 int startIndex, String skippedReason) {
        if (admissions == null || startIndex >= admissions.size()) return;
        for (int index = Math.max(0, startIndex); index < admissions.size(); index++) {
            NativeToolAdmission nativeAdmission = admissions.get(index);
            AgentModelTurnExecutor.NativeToolCall call = nativeAdmission.call();
            String content = nativeAdmission.allowed()
                    ? skippedReason
                    : this.compactToolResultForModel(call.toolName(), nativeAdmission.rejection(), call.toolCallId());
            this.appendProviderMessage(executionFence, taskId, transcriptEpoch,
                    this.toolCallBatchProtocol.toolResultMessage(call,
                            "[Tool " + call.toolName() + " result]\n" + content));
        }
    }

    private void journalRemainingBatchSkipped(ExecutionFence executionFence, Long taskId,
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
            this.toolCallJournalService.skipped(executionFence, taskId, call.toolCallId(), call.toolName(),
                    nativeAdmission.publicArguments(), iteration, reason);
        }
    }

    private void journalToolPending(ExecutionFence executionFence, Long taskId, String toolCallId, String toolName,
                                    Object arguments, int iteration) {
        this.toolCallJournalService.pending(executionFence, taskId, toolCallId, toolName, arguments, iteration);
    }

    private void journalToolRunning(ExecutionFence executionFence, Long taskId, String toolCallId, String toolName,
                                    Object arguments, int iteration) {
        this.toolCallJournalService.running(executionFence, taskId, toolCallId, toolName, arguments, iteration);
    }

    private void journalToolWaitingApproval(ExecutionFence executionFence, Long taskId, String toolCallId,
                                            String toolName, Object arguments,
                                            int iteration, String approvalId) {
        this.toolCallJournalService.waitingApproval(executionFence, taskId, toolCallId, toolName, arguments,
                iteration, approvalId);
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

    /**
     * opencode 语义的交互恢复投影：用户回答后，等待中的 question 工具以普通 completed 工具结果闭合——
     * 落库 completed/interrupted Part、补发 OBSERVE 与结果叙述（opencode 的 question 工具在用户回答后
     * 才返回正常工具结果，前端卡片与历史回放都以"已回答"呈现，而不是停留在 waiting_user）。
     */
    private void projectResolvedInteraction(AgentSsePublisher sse, AgentConversation conv, AgentTask task,
                                            ExecutionFence executionFence, AgentRunInteraction interaction,
                                            String visibleLanguage) throws Exception {
        if (interaction == null || !"question".equalsIgnoreCase(interaction.getInteractionType())) {
            return;
        }
        String toolCallId = this.interactionPayloadString(interaction.getRequestPayload(), "toolCallId");
        if (toolCallId.isBlank()) {
            return;
        }
        Map<String, Object> requestPayload = this.parseInteractionPayload(interaction.getRequestPayload());
        Map<String, Object> responsePayload = this.parseInteractionPayload(interaction.getResponsePayload());
        boolean answered = "answered".equalsIgnoreCase(interaction.getStatus());
        String question = String.valueOf(requestPayload.getOrDefault("question", ""));
        String answer = String.valueOf(responsePayload.getOrDefault("answer", ""));
        String feedback = String.valueOf(responsePayload.getOrDefault("feedback", ""));
        String resultText = answered
                ? this.localText(visibleLanguage, "\u7528\u6237\u5df2\u56de\u7b54\uff1a" + answer, "User answered: " + answer)
                : this.localText(visibleLanguage,
                        "\u7528\u6237\u53d6\u6d88\u4e86\u672c\u6b21\u63d0\u95ee" + (feedback.isBlank() ? "\u3002" : "\uff1a" + feedback),
                        "User cancelled this question" + (feedback.isBlank() ? "." : ": " + feedback));
        JsonObject publicArgs = new JsonObject();
        publicArgs.addProperty("question", question);
        if (answered) {
            this.toolCallJournalService.completed(executionFence, task.getTaskId(), toolCallId, "question",
                    publicArgs, 1, resultText);
            this.sendObservePayload(sse, conv, task.getTaskId(), toolCallId, resultText, true);
            this.sendThought(sse, conv, 1, this.localText(visibleLanguage, "\u68c0\u67e5\u7ed3\u679c", "Check result"),
                    this.toolNarrator.buildResultThought("question", publicArgs,
                            ToolResult.ok(resultText), visibleLanguage),
                    task.getTaskId());
        } else {
            this.toolCallJournalService.interrupted(executionFence, task.getTaskId(), toolCallId, "question",
                    publicArgs, 1, resultText);
        }
    }

    /** 直接构造 OBSERVE 载荷，绕过 summarizeObservation，避免把用户回答内容改写成泛化摘要。 */
    private void sendObservePayload(AgentSsePublisher sse, AgentConversation conv, Long taskId, String toolCallId,
                                    String content, boolean success) throws Exception {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("iteration", 1);
        payload.put("tool", "question");
        payload.put("success", success);
        payload.put("content", content);
        payload.put("resultChars", content == null ? 0 : content.length());
        payload.put("modelProjectionChars", Math.min(content == null ? 0 : content.length(), 4000));
        payload.put("modelProjectionTruncated", content != null && content.length() > 4000);
        payload.put("taskId", taskId);
        payload.put("toolCallId", toolCallId == null ? "" : toolCallId);
        payload.put("summary", success ? "Observed result from question" : "Tool failed: question");
        this.sendEvent(sse, conv, "OBSERVE", payload);
    }

    private String interactionPayloadString(String json, String key) {
        Map<String, Object> payload = this.parseInteractionPayload(json);
        Object raw = payload.get(key);
        return raw == null ? "" : String.valueOf(raw);
    }

    private Map<String, Object> parseInteractionPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return Map.of();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            parsed.getAsJsonObject().entrySet().forEach(entry -> {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive()) {
                    result.put(entry.getKey(), value.getAsString());
                } else {
                    result.put(entry.getKey(), value.toString());
                }
            });
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void journalToolResult(ExecutionFence executionFence, Long taskId, String toolCallId, String toolName,
                                   Object arguments, int iteration, ToolResult result) {
        if (result != null && result.isInteractionRequired()) {
            this.journalToolWaitingInteraction(executionFence, taskId, toolCallId, toolName, arguments, iteration,
                    result.getInteractionRequestId(), result.getInteractionType(), result.getContent(),
                    result.getInteractionPayload());
            return;
        }
        this.journalToolFinished(executionFence, taskId, toolCallId, toolName, arguments, iteration, result);
    }

    private void journalToolWaitingInteraction(ExecutionFence executionFence, Long taskId, String toolCallId,
                                               String toolName, Object arguments,
                                               int iteration, String requestId, String interactionType, String detail,
                                               Map<String, Object> interactionPayload) {
        this.toolCallJournalService.waitingInteraction(executionFence, taskId, toolCallId, toolName, arguments,
                iteration, requestId, interactionType, detail, interactionPayload);
    }

    private String interactionWaitingState(ToolResult result) {
        String type = result == null ? "" : String.valueOf(result.getInteractionType());
        return "permission".equals(type) || "network".equals(type) ? "waiting_approval" : "waiting_user";
    }

    private void journalToolBlocked(ExecutionFence executionFence, Long taskId, String toolCallId, String toolName,
                                    Object arguments, int iteration, String detail) {
        this.toolCallJournalService.blocked(executionFence, taskId, toolCallId, toolName, arguments, iteration, detail);
    }

    private void journalToolFinished(ExecutionFence executionFence, Long taskId, String toolCallId, String toolName,
                                     Object arguments, int iteration, ToolResult result) {
        String detail = result == null ? "" : result.getContent();
        // 结构化执行状态直接决定 durable Part 状态，不能只靠 result 文本猜测。
        String executionStatus = result == null ? "" : String.valueOf(result.getExecutionStatus());
        if (result != null && result.isSuccess()) {
            this.toolCallJournalService.completed(executionFence, taskId, toolCallId, toolName, arguments, iteration, detail);
        } else if ("cancelled".equals(executionStatus)) {
            this.toolCallJournalService.interrupted(executionFence, taskId, toolCallId, toolName, arguments, iteration, detail);
        } else if ("infrastructure_error".equals(executionStatus)) {
            this.toolCallJournalService.blocked(executionFence, taskId, toolCallId, toolName, arguments, iteration, detail);
        } else {
            this.toolCallJournalService.failed(executionFence, taskId, toolCallId, toolName, arguments, iteration, detail);
        }
    }

    private void sendEvent(AgentSsePublisher sse, AgentConversation conv, String type, Object data) throws Exception {
        sse.send(type, data);
    }

    private void sendEvent(AgentSsePublisher sse, AgentConversation conv, String type, Object data,
                           String idempotencyKey) throws Exception {
        sse.send(type, data, idempotencyKey);
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

    /**
     * 组装首条 durable user message 的瘦身初始上下文（对齐 opencode `session/llm/request.ts` 与
     * `session/system.ts`：稳定策略放最前，项目文件/结构/诊断一律由工具按需拉取，不预注入）。
     *
     * <p>恢复（resume）连续性不由本方法保证：durable transcript 投影会重放完整历史
     * （含 compaction checkpoint），运行进度由每次调用追加的 {@code <agent_runtime_projection>}
     * 提供。recentRunLog / checkpoint 参数仅供显式调用方注入有界恢复文本，运行时恢复路径不传
     * （旧版文件 checkpoint 是只读迁移入口，不是事实源，见 AgentCheckpointStore）。</p>
     */
    static String buildLeanInitialContextMessage(String modePolicy, String projectRules,
                                                 String leanMemory, String recentRunLog, String checkpoint) {
        StringBuilder builder = new StringBuilder();
        if (modePolicy != null && !modePolicy.isBlank()) {
            builder.append(modePolicy).append("\n\n");
        }
        if (projectRules != null && !projectRules.isBlank()) {
            builder.append("<project_rules file=\"Labex.md\">\n")
                    .append(limitForContext(projectRules, 10_000))
                    .append("\n</project_rules>\n\n");
        }
        if (leanMemory != null && !leanMemory.isBlank()) {
            builder.append("<workspace_memory scope=\"lean\">\n")
                    .append(limitForContext(leanMemory, 2_000))
                    .append("\n</workspace_memory>\n\n");
        }
        if (recentRunLog != null && !recentRunLog.isBlank()) {
            builder.append("<latest_agent_run_log purpose=\"resume_previous_work\">\n")
                    .append(limitForContext(recentRunLog, 12_000))
                    .append("\n</latest_agent_run_log>\n\n");
        }
        if (checkpoint != null && !checkpoint.isBlank()) {
            builder.append("<agent_checkpoint purpose=\"resume_after_disconnect_or_failure\">\n")
                    .append(limitForContext(checkpoint, 12_000))
                    .append("\n</agent_checkpoint>\n\n");
        }
        return builder.toString().trim();
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

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private static String limitForContext(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n...context truncated...";
    }

    private String compactToolResultForModel(String toolName, ToolResult result) {
        return this.compactToolResultForModel(toolName, result, "");
    }

    private String compactToolResultForModel(String toolName, ToolResult result, String toolCallId) {
        if (result == null) {
            return "";
        }
        String content = result.getContent() == null ? "" : result.getContent();
        AgentContextManager.ToolResultProjection projection = this.contextManager.compactToolResultProjection(
                this.safeTool(toolName), content, result.isSuccess());
        String compact = projection.content();
        if (projection.truncated() && toolCallId != null && !toolCallId.isBlank()) {
            compact = compact + "\n\n[Tool output truncated for the model. The complete durable output remains available only for this task. "
                    + "Use read_tool_output with tool_call_id=\"" + this.escapeJson(toolCallId)
                    + "\", offset=0, and a narrow limit to inspect a specific page. Do not request the entire output at once.]";
        }
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
