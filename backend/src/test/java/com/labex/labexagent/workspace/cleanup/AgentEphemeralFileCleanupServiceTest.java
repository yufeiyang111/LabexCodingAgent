package com.labex.labexagent.workspace.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentPreviewRun;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.mapper.AgentPreviewRunMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.mapper.StudentProjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ephemeral 文件清理的失败回归测试：固定“终态 + 宽限 + 活跃执行保护 + 路径 allowlist”
 * 行为。清理器只允许触碰 agent-logs / artifacts(task-*、preview) / tmp 目录，
 * 绝不触碰 checkpoint、git snapshot/worktree、workspace memory 等恢复事实。
 */
class AgentEphemeralFileCleanupServiceTest {

    private static final Duration OLD = Duration.ofDays(10);
    private static final Duration FRESH = Duration.ofMinutes(1);
    private static final Integer PROJECT_ID = 7;
    private static final Integer STUDENT_ID = 3;

    @TempDir
    Path workspace;

    @TempDir
    Path secondWorkspace;

    private StudentProjectMapper projectMapper;
    private AgentTaskMapper taskMapper;
    private AgentPreviewRunMapper previewMapper;
    private AgentEphemeralCleanupProperties properties;
    private AgentEphemeralFileCleanupService service;
    private Path lockedDir;

    @BeforeEach
    void setUp() {
        projectMapper = mock(StudentProjectMapper.class);
        taskMapper = mock(AgentTaskMapper.class);
        previewMapper = mock(AgentPreviewRunMapper.class);
        properties = new AgentEphemeralCleanupProperties();
        service = new AgentEphemeralFileCleanupService(projectMapper, taskMapper, previewMapper, properties);
        lockedDir = null;
    }

    @AfterEach
    void unlock() throws IOException {
        if (lockedDir != null) {
            Files.setAttribute(lockedDir, "dos:readonly", false);
            lockedDir = null;
        }
    }

    @Test
    void deletesExpiredRunLogWhenSessionInactive() throws IOException {
        stubGuards(List.of(), List.of());
        Path log = runLog("sess-done");
        makeOld(log);

        service.sweepOnce();

        assertFalse(Files.exists(log));
    }

    @Test
    void keepsRunLogWhileSessionHasNonTerminalTask() throws IOException {
        stubGuards(List.of(task(101L, "waiting_approval", "sess-live")), List.of());
        Path log = runLog("sess-live");
        makeOld(log);

        service.sweepOnce();

        assertTrue(Files.exists(log));
    }

    @Test
    void keepsFreshRunLogEvenIfSessionTerminal() throws IOException {
        stubGuards(List.of(), List.of());
        Path log = runLog("sess-old2");
        makeOld(log, FRESH);

        service.sweepOnce();

        assertTrue(Files.exists(log));
    }

    @Test
    void keepsUnparseableRunLogNames() throws IOException {
        stubGuards(List.of(), List.of());
        Path weird = workspace.resolve(".labex/agent-logs/weird.md");
        Files.createDirectories(weird.getParent());
        Files.writeString(weird, "log");
        makeOld(weird);

        service.sweepOnce();

        assertTrue(Files.exists(weird));
    }

    @Test
    void sanitizesStoredSessionIdBeforeMatching() throws IOException {
        stubGuards(List.of(task(102L, "running", "user:42")), List.of());
        Path log = runLog("user_42");
        makeOld(log);

        service.sweepOnce();

        assertTrue(Files.exists(log));
    }

    @Test
    void deletesExpiredArtifactDirButKeepsActiveTaskDir() throws IOException {
        stubGuards(List.of(task(202L, "waiting_user", "s2")), List.of());
        Path done = artifact("task-101/a.log");
        Path live = artifact("task-202/b.log");
        makeOld(done);
        makeOld(live);

        service.sweepOnce();

        assertFalse(Files.exists(done));
        assertTrue(Files.exists(live));
    }

    @Test
    void leavesUnknownArtifactEntriesUntouched() throws IOException {
        stubGuards(List.of(), List.of());
        Path custom = artifact("custom/keep.log");
        Path garbage = artifact("task-notanum/x.log");
        makeOld(custom);
        makeOld(garbage);

        service.sweepOnce();

        assertTrue(Files.exists(custom));
        assertTrue(Files.exists(garbage));
    }

    @Test
    void cleansStalePreviewLogsButKeepsActiveOnes() throws IOException {
        stubGuards(List.of(), List.of(preview("pv-active")));
        Path dead = artifact("preview/pv-dead.log");
        Path active = artifact("preview/pv-active.log");
        Path fresh = artifact("preview/pv-fresh.log");
        makeOld(dead);
        makeOld(active);
        makeOld(fresh, FRESH);

        service.sweepOnce();

        assertFalse(Files.exists(dead));
        assertTrue(Files.exists(active));
        assertTrue(Files.exists(fresh));
    }

    @Test
    void skipsTmpCleanupWhileProjectHasActiveTask() throws IOException {
        stubGuards(List.of(task(301L, "running", "s3")), List.of());
        Path tmp = workspace.resolve(".labex-agent/worker-tmp/old.tmp");
        Files.createDirectories(tmp.getParent());
        Files.writeString(tmp, "x");
        makeOld(tmp);

        service.sweepOnce();

        assertTrue(Files.exists(tmp));
    }

    @Test
    void cleansTmpEntriesWhenProjectIdle() throws IOException {
        stubGuards(List.of(), List.of());
        Path oldWorker = tmpEntry("worker-tmp/old.tmp");
        Path freshWorker = tmpEntry("worker-tmp/fresh.tmp");
        Path oldTerminal = tmpEntry("terminal-tmp/old.txt");
        Path oldRuntime = tmpEntry("runtime/state.json");
        makeOld(oldWorker);
        makeOld(freshWorker, FRESH);
        makeOld(oldTerminal);
        makeOld(oldRuntime);

        service.sweepOnce();

        assertFalse(Files.exists(oldWorker));
        assertTrue(Files.exists(freshWorker));
        assertFalse(Files.exists(oldTerminal));
        assertFalse(Files.exists(oldRuntime));
    }

    @Test
    void respectsEntryBudgetWithinTick() throws IOException {
        stubGuards(List.of(), List.of());
        Path first = runLog("budget-a");
        Path second = runLog("budget-b");
        Path third = runLog("budget-c");
        makeOld(first);
        makeOld(second);
        makeOld(third);
        properties.setMaxDeleteEntriesPerTick(2);

        AgentEphemeralFileCleanupService.SweepStats stats = service.sweepOnce();

        assertEquals(2, stats.entriesDeleted());
        assertTrue(stats.budgetExhausted());
        int remaining = 0;
        for (Path log : List.of(first, second, third)) {
            if (Files.exists(log)) remaining++;
        }
        assertEquals(1, remaining);
    }

    @Test
    void toleratesUndeletableDirectoryAndContinuesOthers() throws IOException {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        stubGuards(List.of(), List.of());
        lockedDir = Files.createDirectories(workspace.resolve(".labex-agent/artifacts/task-401/sub"));
        Files.writeString(workspace.resolve(".labex-agent/artifacts/task-401/a.log"), "x");
        Files.setAttribute(lockedDir, "dos:readonly", true);
        Path sibling = runLog("locked-sibling");
        makeOld(workspace.resolve(".labex-agent/artifacts/task-401"));
        makeOld(sibling);

        AgentEphemeralFileCleanupService.SweepStats stats = service.sweepOnce();

        assertFalse(Files.exists(sibling));
        assertTrue(stats.entriesFailed() >= 1);
    }

    @Test
    void neverTouchesProtectedNeighbors() throws IOException {
        stubGuards(List.of(), List.of());
        Path snapshots = protectedEntry(".labex/git-snapshots/snap");
        Path worktree = protectedEntry(".labex/background-runs/wt");
        Path checkpoint = protectedEntry(".labex/agent-checkpoints/c/c.json");
        Path memory = protectedEntry(".labex/agent-memory.json");

        service.sweepOnce();

        assertTrue(Files.exists(snapshots));
        assertTrue(Files.exists(worktree));
        assertTrue(Files.exists(checkpoint));
        assertTrue(Files.exists(memory));
    }

    @Test
    void sweepsEveryProjectWithinBudget() throws IOException {
        StudentProject first = project(workspace);
        StudentProject second = project(secondWorkspace);
        when(projectMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(first, second)));
        when(taskMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(previewMapper.selectList(any())).thenReturn(new ArrayList<>());
        Path logInSecond = secondWorkspace.resolve(".labex/agent-logs/20260101000000-second.md");
        Files.createDirectories(logInSecond.getParent());
        Files.writeString(logInSecond, "log");
        makeOld(logInSecond);

        service.sweepOnce();

        assertFalse(Files.exists(logInSecond));
    }

    // ─── fixtures ───

    private StudentProject project(Path wsRoot) {
        StudentProject project = new StudentProject();
        project.setProjectId(PROJECT_ID);
        project.setStudentId(STUDENT_ID);
        project.setWorkspacePath(wsRoot.toString());
        return project;
    }

    private void stubGuards(List<AgentTask> activeTasks, List<AgentPreviewRun> activePreviews) {
        when(projectMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(project(workspace))));
        when(taskMapper.selectList(any())).thenReturn(new ArrayList<>(activeTasks));
        when(previewMapper.selectList(any())).thenReturn(new ArrayList<>(activePreviews));
    }

    private AgentTask task(Long taskId, String status, String sessionId) {
        AgentTask task = new AgentTask();
        task.setTaskId(taskId);
        task.setStatus(status);
        task.setSessionId(sessionId);
        task.setProjectId(PROJECT_ID);
        task.setStudentId(STUDENT_ID);
        return task;
    }

    private AgentPreviewRun preview(String previewId) {
        AgentPreviewRun run = new AgentPreviewRun();
        run.setPreviewId(previewId);
        run.setStatus("ready");
        run.setProjectId(PROJECT_ID);
        run.setStudentId(STUDENT_ID);
        return run;
    }

    /** 与 AgentLoopEngine.createRunLog 的 {stamp}-{safeSession}.md 命名保持一致。 */
    private Path runLog(String session) throws IOException {
        Path log = workspace.resolve(".labex/agent-logs/20260101000000-" + session + ".md");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "log");
        return log;
    }

    private Path artifact(String relative) throws IOException {
        Path path = workspace.resolve(".labex-agent/artifacts/" + relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "out");
        return path;
    }

    private Path tmpEntry(String relative) throws IOException {
        Path path = workspace.resolve(".labex-agent/" + relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "tmp");
        return path;
    }

    private Path protectedEntry(String relative) throws IOException {
        Path path = workspace.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "keep");
        makeOld(path);
        return path;
    }

    private void makeOld(Path path) throws IOException {
        makeOld(path, OLD);
    }

    private void makeOld(Path path, Duration age) throws IOException {
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() - age.toMillis()));
        if (path.getParent() != null && !path.getParent().equals(workspace)) {
            try {
                Files.setLastModifiedTime(path.getParent(), FileTime.fromMillis(System.currentTimeMillis() - age.toMillis()));
            } catch (IOException ignored) {
            }
        }
    }
}
