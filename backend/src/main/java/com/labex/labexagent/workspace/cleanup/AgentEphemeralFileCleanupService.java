package com.labex.labexagent.workspace.cleanup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentPreviewRun;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.mapper.AgentPreviewRunMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.mapper.StudentProjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * workspace 内 ephemeral 产物（run log、工具输出 artifact、preview 日志、执行临时目录）
 * 的唯一后台清理入口。
 *
 * <p>硬边界：只删除可再生的诊断文件，绝不触碰 {@code .labex/agent-checkpoints}、
 * {@code .labex/git-snapshots}、{@code .labex/background-runs}、{@code .labex/agent-memory.json}
 * 以及任何数据库行；不修改任何任务状态字段，也不提供用户请求触发的物理删除入口。
 * 清理资格 = 文件年龄超过保留期 AND 归属 task/preview 不在活跃集合
 * （活跃 task = 终态之外的一切状态；活跃 preview = starting/ready）。
 * Windows 文件锁导致的删除失败按条计数留待下一轮重试。</p>
 */
@Service
public class AgentEphemeralFileCleanupService {

    private static final Logger log = LoggerFactory.getLogger(AgentEphemeralFileCleanupService.class);
    private static final Set<String> TERMINAL_TASK_STATUSES = Set.of("completed", "failed", "cancelled");

    /** 与 AgentLoopEngine.createRunLog 的 {stamp}-{safeSession}.md 命名保持一致。 */
    private static final Pattern RUN_LOG_NAME = Pattern.compile("^\\d{8}-?\\d{6}-(.+)\\.md$");
    private static final Pattern TASK_DIR_NAME = Pattern.compile("^task-(\\d+)$");
    private static final String PREVIEW_DIR_NAME = "preview";
    private static final List<String> TMP_DIR_NAMES = List.of("worker-tmp", "terminal-tmp", "runtime");

    private final StudentProjectMapper projectMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentPreviewRunMapper previewMapper;
    private final AgentEphemeralCleanupProperties properties;

    public AgentEphemeralFileCleanupService(StudentProjectMapper projectMapper,
                                            AgentTaskMapper taskMapper,
                                            AgentPreviewRunMapper previewMapper,
                                            AgentEphemeralCleanupProperties properties) {
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.previewMapper = previewMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${labex-agent.agent-cleanup.interval-ms:1800000}")
    public void cleanupScheduled() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            log.warn("AGENT_EPHEMERAL_CLEANUP_TICK_FAILED error={}", failure.getMessage());
        }
    }

    public SweepStats sweepOnce() {
        GuardIndex guards;
        try {
            guards = loadGuards();
        } catch (RuntimeException failure) {
            log.warn("AGENT_EPHEMERAL_CLEANUP_SKIPPED reason=guard_load_failed error={}", failure.getMessage());
            return new SweepStats(0, 0, 0, 0, false);
        }

        Collector collector = new Collector(properties);
        for (StudentProject project : selectProjects()) {
            if (collector.exhausted()
                    || collector.projectsScanned >= Math.max(1, properties.getMaxProjectsPerTick())) {
                break;
            }
            if (project == null || project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
                continue;
            }
            Path root = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            collector.projectsScanned++;
            try {
                sweepProject(project.getProjectId(), root, guards, collector);
            } catch (IOException | RuntimeException failure) {
                log.warn("AGENT_EPHEMERAL_CLEANUP_PROJECT_FAILED projectId={} error={}",
                        project.getProjectId(), failure.getMessage());
            }
        }

        SweepStats stats = collector.snapshot();
        if (stats.entriesDeleted() > 0 || stats.entriesFailed() > 0) {
            log.info("AGENT_EPHEMERAL_CLEANUP projects={} deleted={} failed={} bytes={} budgetExhausted={}",
                    stats.projectsScanned(), stats.entriesDeleted(), stats.entriesFailed(),
                    stats.bytesDeleted(), stats.budgetExhausted());
        }
        return stats;
    }

    // ─── 单个项目 ───

    private void sweepProject(Integer projectId, Path root, GuardIndex guards, Collector collector)
            throws IOException {
        Long key = projectId == null ? null : projectId.longValue();

        Path logsDir = root.resolve(".labex").resolve("agent-logs");
        if (Files.isDirectory(logsDir, LinkOption.NOFOLLOW_LINKS)) {
            long cutoff = cutoffMillis(properties.getRunLogRetentionDays(), TimeUnit.DAYS);
            Set<String> activeSessions = key == null ? Set.of() : guards.sessionsFor(key);
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(logsDir)) {
                for (Path entry : entries) {
                    if (collector.exhausted()) return;
                    Matcher matcher = RUN_LOG_NAME.matcher(String.valueOf(entry.getFileName()));
                    if (!matcher.matches() || activeSessions.contains(matcher.group(1))) {
                        continue;
                    }
                    deleteIfAged(entry, cutoff, collector);
                }
            }
        }

        Path artifactsDir = root.resolve(".labex-agent").resolve("artifacts");
        if (Files.isDirectory(artifactsDir, LinkOption.NOFOLLOW_LINKS)) {
            long artifactCutoff = cutoffMillis(properties.getArtifactRetentionDays(), TimeUnit.DAYS);
            long previewCutoff = cutoffMillis(properties.getPreviewLogRetentionDays(), TimeUnit.DAYS);
            Set<String> activePreviews = key == null ? Set.of() : guards.previewsFor(key);
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(artifactsDir)) {
                for (Path entry : entries) {
                    if (collector.exhausted()) return;
                    String name = String.valueOf(entry.getFileName());
                    if (PREVIEW_DIR_NAME.equals(name) && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        cleanPreviewLogs(entry, activePreviews, previewCutoff, collector);
                        continue;
                    }
                    Matcher matcher = TASK_DIR_NAME.matcher(name);
                    if (!matcher.matches()) {
                        continue;
                    }
                    if (guards.hasActiveTask(Long.parseLong(matcher.group(1)))) {
                        continue;
                    }
                    deleteIfAged(entry, artifactCutoff, collector);
                }
            }
        }

        if (key != null && guards.projectHasActiveTask(key)) {
            return;
        }
        long tmpCutoff = cutoffMillis(properties.getTmpMaxAgeHours(), TimeUnit.HOURS);
        for (String tmpName : TMP_DIR_NAMES) {
            Path dir = root.resolve(".labex-agent").resolve(tmpName);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    if (collector.exhausted()) return;
                    deleteIfAged(entry, tmpCutoff, collector);
                }
            }
        }
    }

    private void cleanPreviewLogs(Path previewDir, Set<String> activePreviews, long cutoff, Collector collector)
            throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(previewDir)) {
            for (Path entry : entries) {
                if (collector.exhausted()) return;
                String name = String.valueOf(entry.getFileName());
                if (!name.endsWith(".log")) {
                    continue;
                }
                if (activePreviews.contains(name.substring(0, name.length() - ".log".length()))) {
                    continue;
                }
                deleteIfAged(entry, cutoff, collector);
            }
        }
    }

    // ─── 删除原语 ───

    private void deleteIfAged(Path entry, long cutoffMillis, Collector collector) throws IOException {
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unreadable) {
            collector.failed++;
            return;
        }
        if (modified.toMillis() >= cutoffMillis) {
            return;
        }
        Removal removal = deleteRecursively(entry);
        if (removal.failures > 0) {
            collector.failed++;
        } else {
            collector.deleted++;
            collector.bytes += removal.bytes;
            if (collector.bytes >= properties.getMaxDeleteBytesPerTick()) {
                collector.exhaustedFlag = true;
            }
        }
    }

    /**
     * 默认 NOFOLLOW：符号链接作为普通条目删除自身，不进入链接目标。
     * 任何单个路径删除失败只累计 failures，不中断整棵树的清理。
     */
    private Removal deleteRecursively(Path entry) throws IOException {
        Removal removal = new Removal();
        Files.walkFileTree(entry, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                removal.bytes += attrs.size();
                removeQuietly(file, removal);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) {
                    removal.failures++;
                }
                removeQuietly(dir, removal);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                removal.failures++;
                return FileVisitResult.CONTINUE;
            }
        });
        return removal;
    }

    private void removeQuietly(Path path, Removal removal) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException lockedOrDenied) {
            removal.failures++;
        }
    }

    private long cutoffMillis(long retentionAmount, TimeUnit unit) {
        long minAge = TimeUnit.MINUTES.toMillis(Math.max(0L, properties.getMinAgeMinutes()));
        long retention = unit.toMillis(Math.max(0L, retentionAmount));
        return System.currentTimeMillis() - Math.max(retention, minAge);
    }

    // ─── 活跃集合（唯一事实来自数据库，只读） ───

    private GuardIndex loadGuards() {
        Set<Long> activeTaskIds = new HashSet<>();
        Map<Long, Set<String>> sessionsByProject = new java.util.HashMap<>();
        Set<Long> projectsWithActiveTasks = new HashSet<>();
        List<AgentTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<AgentTask>()
                .notIn(AgentTask::getStatus, TERMINAL_TASK_STATUSES));
        for (AgentTask task : tasks == null ? List.<AgentTask>of() : tasks) {
            if (task == null || task.getStatus() == null || task.getProjectId() == null) {
                continue;
            }
            Long key = task.getProjectId().longValue();
            projectsWithActiveTasks.add(key);
            if (task.getTaskId() != null) {
                activeTaskIds.add(task.getTaskId());
            }
            if (task.getSessionId() != null && !task.getSessionId().isBlank()) {
                sessionsByProject.computeIfAbsent(key, absent -> new HashSet<>())
                        .add(sanitizeSession(task.getSessionId()));
            }
        }

        Map<Long, Set<String>> previewsByProject = new java.util.HashMap<>();
        List<AgentPreviewRun> previews = previewMapper.selectList(new LambdaQueryWrapper<AgentPreviewRun>()
                .in(AgentPreviewRun::getStatus, List.of("starting", "ready")));
        for (AgentPreviewRun run : previews == null ? List.<AgentPreviewRun>of() : previews) {
            if (run == null || run.getPreviewId() == null || run.getProjectId() == null) {
                continue;
            }
            previewsByProject.computeIfAbsent(run.getProjectId().longValue(), absent -> new HashSet<>())
                    .add(run.getPreviewId());
        }
        return new GuardIndex(activeTaskIds, sessionsByProject, previewsByProject, projectsWithActiveTasks);
    }

    /** 与 AgentLoopEngine.createRunLog 的会话名清洗规则一致。 */
    private static String sanitizeSession(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private List<StudentProject> selectProjects() {
        try {
            List<StudentProject> projects = projectMapper.selectList(null);
            return projects == null ? List.of() : projects;
        } catch (RuntimeException failure) {
            log.warn("AGENT_EPHEMERAL_CLEANUP_PROJECT_LIST_FAILED error={}", failure.getMessage());
            return List.of();
        }
    }

    // ─── 内部结构 ───

    private static final class Removal {
        private long bytes;
        private int failures;
    }

    private static final class Collector {
        private final AgentEphemeralCleanupProperties properties;
        private int projectsScanned;
        private int deleted;
        private long bytes;
        private int failed;
        private boolean exhaustedFlag;

        private Collector(AgentEphemeralCleanupProperties properties) {
            this.properties = properties;
        }

        private boolean exhausted() {
            return exhaustedFlag || deleted >= Math.max(1, properties.getMaxDeleteEntriesPerTick());
        }

        private SweepStats snapshot() {
            return new SweepStats(projectsScanned, deleted, bytes, failed,
                    exhaustedFlag || deleted >= Math.max(1, properties.getMaxDeleteEntriesPerTick()));
        }
    }

    private record GuardIndex(Set<Long> activeTaskIds,
                              java.util.Map<Long, Set<String>> sessionsByProject,
                              java.util.Map<Long, Set<String>> previewsByProject,
                              Set<Long> projectsWithActiveTasks) {

        private boolean hasActiveTask(long taskId) {
            return activeTaskIds.contains(taskId);
        }

        private boolean projectHasActiveTask(long projectId) {
            return projectsWithActiveTasks.contains(projectId);
        }

        private Set<String> sessionsFor(Long projectId) {
            return sessionsByProject.getOrDefault(projectId, Set.of());
        }

        private Set<String> previewsFor(Long projectId) {
            return previewsByProject.getOrDefault(projectId, Set.of());
        }
    }

    /** 一轮清理的只读统计。 */
    public record SweepStats(int projectsScanned, int entriesDeleted, long bytesDeleted,
                             int entriesFailed, boolean budgetExhausted) {

        @Override
        public String toString() {
            return "SweepStats[projects=%d, deleted=%d, failed=%d, bytes=%d, exhausted=%s]".formatted(
                    projectsScanned, entriesDeleted, entriesFailed, bytesDeleted, budgetExhausted).toUpperCase(Locale.ROOT);
        }
    }
}
