package com.labex.labexagent.run;

import com.labex.entity.AgentSubagent;
import com.labex.entity.AgentTask;
import com.labex.entity.AgentConversation;
import com.labex.entity.StudentProject;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.ProjectIndexService;
import com.labex.mapper.AgentTaskMapper;
import java.util.concurrent.CompletableFuture;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 子代理统一派发入口：创建独立子会话 + 子任务，经 {@link AgentLoopEngine#startDetached}
 * 走主运行时（durable transcript / 状态机 / 压缩 / SSE 订阅全部复用）。
 * 取代旧的 LlmSubagentExecutor + SubagentScheduler 内存循环。
 */
@Service
public class SubagentLaunchService {

    public record LaunchSpec(String parentSessionId,
                             Integer studentId,
                             StudentProject project,
                             String parentConversationId,
                             Long parentTaskId,
                             String name,
                             String description,
                             String prompt,
                             String subagentType,
                             Integer modelConfigId,
                             boolean background,
                             String parentToolCallId) {
    }

    public record Launch(AgentSubagent subagent, AgentConversation conversation, CompletableFuture<Void> completion) {
    }

    private final AgentSubagentService subagents;
    private final AgentConversationService conversations;
    private final ProjectIndexService projectIndexService;
    private final AgentSubagentProperties properties;
    private final SubagentCompletionRegistry completions;
    private final SubagentResultSummaryService summaries;
    private final SubagentRunSyncService runSync;
    private final AgentTaskMapper taskMapper;
    private final AgentLoopEngine engine;

    public SubagentLaunchService(AgentSubagentService subagents,
                                 AgentConversationService conversations,
                                 ProjectIndexService projectIndexService,
                                 AgentSubagentProperties properties,
                                 SubagentCompletionRegistry completions,
                                 SubagentResultSummaryService summaries,
                                 SubagentRunSyncService runSync,
                                 AgentTaskMapper taskMapper,
                                 @Lazy AgentLoopEngine engine) {
        this.subagents = subagents;
        this.conversations = conversations;
        this.projectIndexService = projectIndexService;
        this.properties = properties == null ? new AgentSubagentProperties() : properties;
        this.completions = completions;
        this.summaries = summaries;
        this.runSync = runSync;
        this.taskMapper = taskMapper;
        this.engine = engine;
    }

    public Launch launch(LaunchSpec spec) {
        if (spec == null || spec.project() == null || spec.parentTaskId() == null
                || spec.prompt() == null || spec.prompt().isBlank()) {
            throw new IllegalArgumentException("subagent launch requires project, parent task and prompt");
        }
        SubagentType type = SubagentType.parse(spec.subagentType());
        int parentDepth = 0;
        AgentSubagent parentRow = this.subagents.findByChildTaskId(spec.parentTaskId());
        if (parentRow != null && parentRow.getSpawnDepth() != null) {
            parentDepth = Math.max(0, parentRow.getSpawnDepth());
        }
        int childDepth = parentDepth + 1;

        String identity = spec.name() == null || spec.name().isBlank()
                ? type.persisted() : spec.name();
        if (!identity.equalsIgnoreCase(type.persisted())) {
            identity = identity + " (" + type.persisted() + ")";
        }
        String digest = this.projectIndexService.buildProjectDigest(spec.project(), spec.prompt());
        String instructions = SubagentInstructions.build(type, spec.name(), spec.description(),
                spec.prompt(), digest, this.properties);

        AgentSubagent row = this.subagents.create(spec.parentTaskId(), identity, instructions,
                spec.modelConfigId(), this.properties.getDefaultTokenBudget(), "[]", "[]",
                spec.background(), childDepth, type);
        row.setParentToolCallId(spec.parentToolCallId());
        this.subagents.updateDispatchLink(row);

        AgentConversation parentConversation = this.conversations.getOwnedConversation(
                spec.studentId(), spec.project().getProjectId(), spec.parentConversationId());
        AgentConversation conversation = this.conversations.createSubagentConversation(
                spec.studentId(), spec.project(), parentConversation,
                identity + " · 子代理", null);

        CompletableFuture<Void> completion = this.completions.register(row.getSubagentId(), (rowId, task) -> {
            row.setChildTaskId(task.getTaskId());
            this.subagents.updateDispatchLink(row);
            if (task.getParentTaskId() == null) {
                task.setParentTaskId(row.getTaskId());
                this.taskMapper.updateById(task);
            }
            // 子任务落库即代表运行已开始：补齐 queued → running 状态迁移，
            // 使终态收束（queued → completed 本就不合法）能通过状态机守卫。
            try {
                this.subagents.transition(row, SubagentState.RUNNING);
            } catch (RuntimeException alreadyRunning) {
                // onCreated 由引擎在任务创建处调用；恢复场景重复回调时行可能已 running。
            }
        }, this.runSync::finalizeFromTask);

        AgentStreamRequest request = new AgentStreamRequest();
        request.setSessionId((spec.parentSessionId() == null ? "session" : spec.parentSessionId())
                + ":subagent:" + row.getSubagentId());
        request.setConversationId(conversation.getConversationId());
        request.setMode("subagent");
        request.setMessage(instructions);
        request.setDisplayMessage("[" + identity + "] "
                + (spec.description() == null || spec.description().isBlank()
                        ? ToolSupportLimit.limit(spec.prompt()) : spec.description()));
        request.setModelConfigId(spec.modelConfigId());
        request.setBackgroundRun(false);
        request.setSubagentRowId(row.getSubagentId());

        try {
            this.engine.startDetached(spec.studentId(), spec.project().getProjectId(), request);
        } catch (RuntimeException dispatchFailure) {
            this.completions.failed(row.getSubagentId(), dispatchFailure);
            try {
                this.summaries.complete(row, "subagent dispatch failed: " + dispatchFailure.getMessage(), false);
            } catch (RuntimeException ignored) {
                // 行状态机失败不能吞掉原始派发异常。
            }
            throw dispatchFailure;
        }
        return new Launch(row, conversation, completion);
    }

    /** 复用旧执行上下文工厂的会话隔离命名，便于取消与日志关联。 */
    public static AgentContext childContext(AgentContext parentLikeContext) {
        return parentLikeContext;
    }

    private static final class ToolSupportLimit {
        private static String limit(String value) {
            if (value == null) return "";
            return value.length() <= 200 ? value : value.substring(0, 200) + "...";
        }
    }
}
