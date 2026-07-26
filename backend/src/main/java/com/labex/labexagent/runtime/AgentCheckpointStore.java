package com.labex.labexagent.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.workspace.ProjectWorkspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 按会话和任务持久化可恢复的 Agent 执行状态。 */
public final class AgentCheckpointStore {
    private static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_REQUEST_CHARS = 2_000;
    private static final int MAX_NOTE_CHARS = 2_000;
    private static final int MAX_RESULT_CHARS = 12_000;

    public void save(StudentProject project, AgentStreamRequest request, AgentTask task, AgentContext context,
                     String status, String note, String lastTool, String lastResult, Path runLog) {
        if (project == null || request == null || task == null || task.getTaskId() == null
                || task.getConversationId() == null || task.getConversationId().isBlank()) {
            return;
        }
        Snapshot snapshot = Snapshot.capture(project, request, task, context, status, note, lastTool, lastResult, runLog);
        Path path = checkpointPath(project, task.getConversationId(), task.getTaskId());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, GSON.toJson(snapshot), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            replaceAtomically(temporary, path);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to persist task checkpoint", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (Exception ignored) {
                // 临时文件清理失败不能覆盖原始持久化异常。
            }
        }
    }

    public Optional<Snapshot> load(StudentProject project, String conversationId, Long taskId) {
        if (project == null || conversationId == null || conversationId.isBlank() || taskId == null) {
            return Optional.empty();
        }
        Path path = checkpointPath(project, conversationId, taskId);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            Snapshot snapshot = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Snapshot.class);
            if (snapshot == null || snapshot.version != VERSION
                    || !conversationId.equals(snapshot.conversationId) || !taskId.equals(snapshot.taskId)) {
                return Optional.empty();
            }
            return Optional.of(snapshot.normalized());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read task checkpoint", exception);
        }
    }

    public String renderForPrompt(Snapshot snapshot) {
        if (snapshot == null) return "";
        return """
                <agent_task_checkpoint version="%d">
                conversation_id: %s
                task_id: %s
                status: %s
                stage: %s
                write_count: %d
                verification_count: %d
                unverified_changes: %s
                plan: %s
                resume_note_json: %s
                last_tool_json: %s
                last_result_json: %s
                </agent_task_checkpoint>
                """.formatted(snapshot.version, safe(snapshot.conversationId), snapshot.taskId,
                safe(snapshot.status), safe(snapshot.stage), snapshot.writeCount, snapshot.verificationCount,
                snapshot.unverifiedChanges, GSON.toJson(snapshot.plan), GSON.toJson(bounded(snapshot.note, MAX_NOTE_CHARS)),
                GSON.toJson(bounded(snapshot.lastTool, 256)), GSON.toJson(bounded(snapshot.lastResult, MAX_RESULT_CHARS)));
    }

    private static void replaceAtomically(Path temporary, Path target) throws Exception {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    Path checkpointPath(StudentProject project, String conversationId, Long taskId) {
        String relative = ".labex/agent-checkpoints/" + safeSegment(conversationId) + "/" + taskId + ".json";
        return ProjectWorkspace.paths(project).resolveForCreate(relative);
    }

    private static String safeSegment(String value) {
        String normalized = value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return normalized.equals(value) ? normalized : normalized + "-" + shortHash(value == null ? "" : value);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 6; index++) result.append(String.format("%02x", digest[index]));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\u0000", "").strip();
    }

    private static String bounded(String value, int maxChars) {
        String normalized = safe(value);
        if (normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, maxChars) + "...truncated...";
    }

    public static final class Snapshot {
        private int version;
        private String conversationId;
        private Long taskId;
        private String sessionId;
        private String status;
        private String stage;
        private int writeCount;
        private int verificationCount;
        private boolean unverifiedChanges;
        private List<String> trustedVerificationSources;
        private List<String> unverifiedChangeTargets;
        private List<PlanState> plan;
        private int currentPlanIndex;
        private String userRequest;
        private String note;
        private String lastTool;
        private String lastResult;
        private String runLog;
        private String updatedAt;

        private static Snapshot capture(StudentProject project, AgentStreamRequest request, AgentTask task,
                                        AgentContext context, String status, String note, String lastTool,
                                        String lastResult, Path runLog) {
            Snapshot snapshot = new Snapshot();
            snapshot.version = VERSION;
            snapshot.conversationId = task.getConversationId();
            snapshot.taskId = task.getTaskId();
            snapshot.sessionId = request.getSessionId();
            snapshot.status = safe(status);
            snapshot.stage = context == null ? "intake" : context.getStage();
            snapshot.writeCount = context == null ? 0 : context.getWriteCount();
            snapshot.verificationCount = context == null ? 0 : context.getVerificationCount();
            snapshot.unverifiedChanges = context != null && context.hasUnverifiedChanges();
            snapshot.trustedVerificationSources = context == null ? List.of() : new ArrayList<>(context.getTrustedVerificationSources());
            snapshot.unverifiedChangeTargets = context == null ? List.of() : new ArrayList<>(context.getUnverifiedChangeTargets());
            snapshot.plan = context == null || context.getPlan() == null ? List.of()
                    : context.getPlan().stream().map(PlanState::capture).toList();
            snapshot.currentPlanIndex = context == null ? 0 : context.getCurrentPlanIndex();
            snapshot.userRequest = bounded(request.getMessage(), MAX_REQUEST_CHARS);
            snapshot.note = bounded(note, MAX_NOTE_CHARS);
            snapshot.lastTool = bounded(lastTool, 256);
            snapshot.lastResult = bounded(lastResult, MAX_RESULT_CHARS);
            snapshot.runLog = workspaceRelative(project, runLog);
            snapshot.updatedAt = LocalDateTime.now().toString();
            return snapshot.normalized();
        }

        private Snapshot normalized() {
            if (stage == null || stage.isBlank()) stage = "intake";
            if (trustedVerificationSources == null) trustedVerificationSources = List.of();
            if (unverifiedChangeTargets == null) unverifiedChangeTargets = List.of();
            if (plan == null) plan = List.of();
            if (status == null) status = "";
            if (note == null) note = "";
            if (lastTool == null) lastTool = "";
            if (lastResult == null) lastResult = "";
            return this;
        }

        public void restoreInto(AgentContext context) {
            if (context == null || !conversationId.equals(context.getConversationId()) || !taskId.equals(context.getTaskId())) {
                throw new IllegalArgumentException("Checkpoint does not belong to the target Agent context");
            }
            context.restoreExecutionState(stage, plan.stream().map(PlanState::restore).toList(), currentPlanIndex,
                    writeCount, verificationCount, unverifiedChanges,
                    new LinkedHashSet<>(trustedVerificationSources), new LinkedHashSet<>(unverifiedChangeTargets));
        }
    }

    private static final class PlanState {
        private String title;
        private String description;
        private boolean completed;

        private static PlanState capture(AgentContext.PlanItem item) {
            PlanState state = new PlanState();
            state.title = item == null ? "" : safe(item.getTitle());
            state.description = item == null ? "" : safe(item.getDescription());
            state.completed = item != null && item.isCompleted();
            return state;
        }

        private AgentContext.PlanItem restore() {
            return new AgentContext.PlanItem(title, description, completed);
        }
    }

    private static String workspaceRelative(StudentProject project, Path runLog) {
        if (project == null || runLog == null) return "";
        try {
            Path root = ProjectWorkspace.paths(project).workspaceRoot();
            return root.relativize(runLog.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (Exception ignored) {
            return "";
        }
    }
}