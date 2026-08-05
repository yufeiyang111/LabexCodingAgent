package com.labex.labexagent.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.ProjectWorkspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 只读的旧版 Agent 文件 checkpoint 迁移入口。
 *
 * <p>v1/v2 文件不再是运行时事实源，也不允许继续写入。调用方只能把其中的数据迁移为
 * durable Message/Part/Event 后再参与恢复。</p>
 */
@Service
public final class AgentCheckpointStore {
    private static final Set<Integer> LEGACY_VERSIONS = Set.of(1, 2);
    private static final int MAX_INVENTORY_ENTRIES = 10_000;
    private static final long MAX_CHECKPOINT_BYTES = 1_048_576L;
    private static final Gson GSON = new GsonBuilder().create();

    public Optional<Snapshot> loadLegacy(StudentProject project, String conversationId, Long taskId) {
        if (project == null || conversationId == null || conversationId.isBlank() || taskId == null) {
            return Optional.empty();
        }
        Path path = checkpointPath(project, conversationId, taskId);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            Snapshot snapshot = readSnapshot(path);
            if (!validIdentity(snapshot)
                    || !conversationId.equals(snapshot.conversationId)
                    || !taskId.equals(snapshot.taskId)) {
                throw new IllegalStateException("Legacy task checkpoint identity does not match its requested task");
            }
            return Optional.of(snapshot.normalized());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read legacy task checkpoint", exception);
        }
    }

    /**
     * 扫描活动 workspace 中的 v1/v2 checkpoint；不跟随符号链接，并对扫描规模设置硬上限。
     */
    public LegacyInventory scanLegacySources(List<StudentProject> projects) {
        List<LegacySource> sources = new ArrayList<>();
        long invalidSources = 0L;
        long unscannableProjects = 0L;
        int inspectedEntries = 0;
        boolean truncated = false;
        List<StudentProject> candidates = projects == null ? List.of() : projects;
        for (StudentProject project : candidates) {
            Path root;
            try {
                root = ProjectWorkspace.paths(project).resolveForCreate(".labex/agent-checkpoints");
            } catch (RuntimeException unavailableWorkspace) {
                unscannableProjects++;
                continue;
            }
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                invalidSources++;
                continue;
            }
            try (var paths = Files.walk(root, 2)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path candidate = iterator.next();
                    if (candidate.equals(root)) {
                        continue;
                    }
                    inspectedEntries++;
                    if (inspectedEntries > MAX_INVENTORY_ENTRIES) {
                        truncated = true;
                        break;
                    }
                    if (Files.isSymbolicLink(candidate)) {
                        invalidSources++;
                        continue;
                    }
                    if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if (candidate.getFileName() == null
                            || !candidate.getFileName().toString().endsWith(".json")) {
                        continue;
                    }
                    if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        invalidSources++;
                        continue;
                    }
                    try {
                        Snapshot snapshot = readSnapshot(candidate);
                        if (!validIdentity(snapshot)) {
                            invalidSources++;
                            continue;
                        }
                        Path expected = checkpointPath(project, snapshot.conversationId, snapshot.taskId)
                                .toAbsolutePath().normalize();
                        Path actual = candidate.toAbsolutePath().normalize();
                        if (!actual.equals(expected)) {
                            invalidSources++;
                            continue;
                        }
                        sources.add(new LegacySource(
                                project == null ? null : project.getStudentId(),
                                project == null ? null : project.getProjectId(),
                                snapshot.conversationId, snapshot.taskId, snapshot.version));
                    } catch (Exception invalidCheckpoint) {
                        invalidSources++;
                    }
                }
            } catch (Exception scanFailure) {
                unscannableProjects++;
            }
            if (truncated) {
                break;
            }
        }
        return new LegacyInventory(List.copyOf(sources), invalidSources, unscannableProjects, truncated);
    }

    private Snapshot readSnapshot(Path path) throws Exception {
        long size = Files.size(path);
        if (size < 0L || size > MAX_CHECKPOINT_BYTES) {
            throw new IllegalStateException("Legacy task checkpoint exceeds the supported size limit");
        }
        Snapshot snapshot = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Snapshot.class);
        if (snapshot == null) {
            throw new IllegalStateException("Legacy task checkpoint payload is empty");
        }
        return snapshot;
    }

    private boolean validIdentity(Snapshot snapshot) {
        return snapshot != null
                && LEGACY_VERSIONS.contains(snapshot.version)
                && snapshot.conversationId != null
                && !snapshot.conversationId.isBlank()
                && snapshot.taskId != null
                && snapshot.taskId > 0L;
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
            for (int index = 0; index < 6; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\u0000", "").strip();
    }

    public record LegacySource(Integer studentId,
                               Integer projectId,
                               String conversationId,
                               Long taskId,
                               int version) {
    }

    public record LegacyInventory(List<LegacySource> sources,
                                  long invalidSources,
                                  long unscannableProjects,
                                  boolean truncated) {
        public LegacyInventory {
            sources = sources == null ? List.of() : List.copyOf(sources);
            invalidSources = Math.max(0L, invalidSources);
            unscannableProjects = Math.max(0L, unscannableProjects);
        }
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
        private Integer currentPlanIndex;
        private String userRequest;
        private String note;
        private String lastTool;
        private String lastResult;
        private String runLog;
        private String updatedAt;

        private Snapshot normalized() {
            if (stage == null || stage.isBlank()) stage = "intake";
            if (trustedVerificationSources == null) trustedVerificationSources = List.of();
            if (unverifiedChangeTargets == null) unverifiedChangeTargets = List.of();
            if (plan == null) plan = List.of();
            if (status == null) status = "";
            if (note == null) note = "";
            if (lastTool == null) lastTool = "";
            if (lastResult == null) lastResult = "";
            if (runLog == null) runLog = "";
            return this;
        }

        public int version() {
            return version;
        }

        /** 旧计划只能作为数据库计划为空时的一次性迁移输入。 */
        public Optional<LegacyPlanSeed> legacyPlanSeed() {
            if (version != 1 || plan == null || plan.isEmpty()) {
                return Optional.empty();
            }
            List<AgentContext.PlanItem> items = plan.stream().map(PlanState::restore).toList();
            int index = currentPlanIndex == null ? 0 : currentPlanIndex;
            int boundedIndex = Math.max(0, Math.min(index, Math.max(0, items.size() - 1)));
            return Optional.of(new LegacyPlanSeed(items, boundedIndex));
        }

        /** v1/v2 执行辅助状态只能先迁移为 durable event，不能直接写入 AgentContext。 */
        public Optional<LegacyExecutionSeed> legacyExecutionSeed() {
            LinkedHashSet<String> trusted = normalizedSet(trustedVerificationSources, true);
            LinkedHashSet<String> targets = normalizedSet(unverifiedChangeTargets, false);
            boolean meaningful = !"intake".equalsIgnoreCase(safe(stage))
                    || writeCount > 0 || verificationCount > 0 || unverifiedChanges
                    || !trusted.isEmpty() || !targets.isEmpty()
                    || !safe(lastTool).isBlank() || !safe(lastResult).isBlank()
                    || !safe(runLog).isBlank() || !safe(note).isBlank();
            if (!meaningful) {
                return Optional.empty();
            }
            return Optional.of(new LegacyExecutionSeed(
                    stage, writeCount, verificationCount, unverifiedChanges,
                    trusted, targets, lastTool, lastResult, runLog, note));
        }

        private LinkedHashSet<String> normalizedSet(List<String> values, boolean lowerCase) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            if (values == null) {
                return result;
            }
            for (String value : values) {
                if (value == null || value.isBlank()) continue;
                String normalized = value.trim().replace('\\', '/');
                result.add(lowerCase ? normalized.toLowerCase(java.util.Locale.ROOT) : normalized);
            }
            return result;
        }
    }

    public record LegacyExecutionSeed(String stage,
                                      int writeCount,
                                      int verificationCount,
                                      boolean unverifiedChanges,
                                      Set<String> trustedVerificationSources,
                                      Set<String> unverifiedChangeTargets,
                                      String lastTool,
                                      String lastResult,
                                      String runLogPath,
                                      String resumeNote) {
        public LegacyExecutionSeed {
            stage = stage == null || stage.isBlank() ? "intake" : stage.trim();
            writeCount = Math.max(0, writeCount);
            verificationCount = Math.max(0, verificationCount);
            trustedVerificationSources = trustedVerificationSources == null
                    ? Set.of() : Set.copyOf(trustedVerificationSources);
            unverifiedChangeTargets = unverifiedChangeTargets == null
                    ? Set.of() : Set.copyOf(unverifiedChangeTargets);
            unverifiedChanges = unverifiedChanges || !unverifiedChangeTargets.isEmpty();
            lastTool = safe(lastTool);
            lastResult = safe(lastResult);
            runLogPath = safe(runLogPath).replace('\\', '/');
            resumeNote = safe(resumeNote);
        }
    }

    public record LegacyPlanSeed(List<AgentContext.PlanItem> items, int currentPlanIndex) {
        public LegacyPlanSeed {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    private static final class PlanState {
        private String title;
        private String description;
        private boolean completed;

        private AgentContext.PlanItem restore() {
            return new AgentContext.PlanItem(title, description, completed);
        }
    }
}
