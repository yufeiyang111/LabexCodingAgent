package com.labex.labexagent.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.labex.entity.AgentChangeSet;
import com.labex.entity.AgentFileChange;
import com.labex.entity.StudentProject;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.tool.FileContentFingerprint;
import com.labex.labexagent.service.WorkspaceContextInvalidator;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.service.StudentProjectService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffServiceCasTest {

    @BeforeAll
    static void initializeEntityMetadata() {
        if (TableInfoHelper.getTableInfo(AgentFileChange.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    AgentFileChange.class);
        }
    }

    @TempDir
    Path workspace;

    @Test
    void applyRejectsAStagedChangeWhenTheFileHashHasBecomeStale() throws Exception {
        Path file = Files.writeString(workspace.resolve("Main.java"), "class Original {}\n");
        AtomicReference<AgentFileChange> storedChange = new AtomicReference<>();
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        doAnswer(invocation -> {
            storedChange.set(invocation.getArgument(0));
            return 1;
        }).when(fileChangeMapper).insert(any(AgentFileChange.class));
        when(fileChangeMapper.selectOne(any())).thenAnswer(invocation -> storedChange.get());

        StudentProjectService projectService = mock(StudentProjectService.class);
        StudentProject project = project();
        when(projectService.getOwnedProject(7, 12)).thenReturn(project);

        AgentTaskService taskService = taskService();
        GitSnapshotService snapshots = mock(GitSnapshotService.class);
        GitSnapshotService.Snapshot unavailable = new GitSnapshotService.Snapshot(false, "", "unavailable", "test");
        when(snapshots.capture(any(StudentProject.class), any(String.class))).thenReturn(unavailable);
        when(snapshots.capture(any(StudentProject.class), any(String.class), any(Collection.class))).thenReturn(unavailable);

        DiffService service = new DiffService(projectService, taskService, fileChangeMapper, snapshots);
        PendingChange change = service.stage(7, project, "conversation", 1L, "Main.java",
                "class Original {}\n", "class AgentEdit {}\n", "modify");
        Files.writeString(file, "class HumanEdit {}\n");

        assertThrows(DiffService.ChangeConflictException.class, () -> service.apply(7, change.getId()));

        assertEquals("class HumanEdit {}\n", Files.readString(file));
        assertEquals("conflicted", storedChange.get().getStatus());
    }

    @Test
    void applyUsesGitDiffWhenSnapshotsAreAvailable() throws Exception {
        Files.writeString(workspace.resolve("Main.java"), "class Original {}\n");
        AtomicReference<AgentFileChange> storedChange = new AtomicReference<>();
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        doAnswer(invocation -> {
            storedChange.set(invocation.getArgument(0));
            return 1;
        }).when(fileChangeMapper).insert(any(AgentFileChange.class));
        when(fileChangeMapper.selectOne(any())).thenAnswer(invocation -> storedChange.get());

        StudentProjectService projectService = mock(StudentProjectService.class);
        StudentProject project = project();
        when(projectService.getOwnedProject(7, 12)).thenReturn(project);

        AgentTaskService taskService = taskService();
        GitSnapshotService snapshots = mock(GitSnapshotService.class);
        GitSnapshotService.Snapshot before = new GitSnapshotService.Snapshot(true, "before", "changed", "");
        GitSnapshotService.Snapshot after = new GitSnapshotService.Snapshot(true, "after", "changed", "");
        GitSnapshotService.ChangedFile changedFile = new GitSnapshotService.ChangedFile("M", "", "Main.java");
        when(snapshots.capture(any(StudentProject.class), any(String.class))).thenReturn(before, after);
        when(snapshots.capture(any(StudentProject.class), any(String.class), any(Collection.class))).thenReturn(before, after);
        when(snapshots.usablePair(any(), any())).thenReturn(true);
        when(snapshots.changedFiles(any(StudentProject.class), any(), any())).thenReturn(List.of(changedFile));
        when(snapshots.diffForFile(any(StudentProject.class), any(), any(), any())).thenReturn("diff --git a/Main.java b/Main.java");

        DiffService service = new DiffService(projectService, taskService, fileChangeMapper, snapshots);
        PendingChange applied = service.stageAndApply(7, project, "conversation", 1L, "Main.java",
                "class Original {}\n", "class AgentEdit {}\n", "modify");

        assertEquals("diff --git a/Main.java b/Main.java", applied.getDiff());
        assertEquals("diff --git a/Main.java b/Main.java", storedChange.get().getDiff());
        assertEquals("applied", storedChange.get().getStatus());
        DiffService.ApplyTelemetry telemetry = service.consumeLastApplyTelemetry();
        assertEquals("complete", telemetry.phase());
        String beforeContent = "class Original {}\n";
        String afterContent = "class AgentEdit {}\n";
        assertEquals(Map.of(
                "state", "applied",
                "changeIds", List.of(applied.getId()),
                "targets", List.of(Map.of(
                        "path", "Main.java",
                        "operation", "modify",
                        "changeId", applied.getId(),
                        "before", Map.of(
                                "state", "present",
                                "sha256", FileContentFingerprint.sha256(beforeContent),
                                "bytes", (long) beforeContent.getBytes(StandardCharsets.UTF_8).length),
                        "after", Map.of(
                                "state", "present",
                                "sha256", FileContentFingerprint.sha256(afterContent),
                                "bytes", (long) afterContent.getBytes(StandardCharsets.UTF_8).length,
                                "verified", true)))), telemetry.workspaceMutation());
        assertTrue(telemetry.timingMs().containsKey("snapshotBeforeMs"));
        verify(snapshots, times(2)).capture(eq(project), any(String.class), eq(List.of("Main.java")));
        verify(snapshots, never()).capture(any(StudentProject.class), any(String.class));
    }

    @Test
    void batchUsesOneSnapshotPairAndOneMetadataRefresh() throws Exception {
        List<AgentFileChange> storedChanges = new ArrayList<>();
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        doAnswer(invocation -> {
            storedChanges.add(invocation.getArgument(0));
            return 1;
        }).when(fileChangeMapper).insert(any(AgentFileChange.class));
        when(fileChangeMapper.selectOne(any())).thenAnswer(invocation -> storedChanges.get(storedChanges.size() - 1));

        StudentProjectService projectService = mock(StudentProjectService.class);
        AgentTaskService taskService = taskService();
        GitSnapshotService snapshots = mock(GitSnapshotService.class);
        GitSnapshotService.Snapshot before = new GitSnapshotService.Snapshot(true, "before", "changed", "");
        GitSnapshotService.Snapshot after = new GitSnapshotService.Snapshot(true, "after", "changed", "");
        GitSnapshotService.ChangedFile first = new GitSnapshotService.ChangedFile("A", "", "First.txt");
        GitSnapshotService.ChangedFile second = new GitSnapshotService.ChangedFile("A", "", "Second.txt");
        when(snapshots.capture(any(StudentProject.class), any(String.class))).thenReturn(before, after);
        when(snapshots.capture(any(StudentProject.class), any(String.class), any(Collection.class))).thenReturn(before, after);
        when(snapshots.usablePair(any(), any())).thenReturn(true);
        when(snapshots.changedFiles(any(StudentProject.class), eq(before), eq(after))).thenReturn(List.of(first, second));
        when(snapshots.diffForFile(any(StudentProject.class), eq(before), eq(after), any()))
                .thenAnswer(invocation -> "diff " + ((GitSnapshotService.ChangedFile) invocation.getArgument(3)).path());
        when(snapshots.serializePaths(any())).thenAnswer(invocation -> {
            List<GitSnapshotService.ChangedFile> files = invocation.getArgument(0);
            return files.isEmpty() ? "" : files.get(0).path();
        });

        WorkspaceContextInvalidator invalidator = mock(WorkspaceContextInvalidator.class);
        DiffService service = new DiffService(projectService, taskService, fileChangeMapper, snapshots,
                new com.labex.labexagent.workspace.WorkspaceLeaseService(), invalidator);
        StudentProject project = project();
        List<PendingChange> applied = service.stageAndApplyBatch(7, project, "conversation", 1L, List.of(
                new DiffService.ChangeRequest("First.txt", "", "first", "create"),
                new DiffService.ChangeRequest("Second.txt", "", "second", "create")));

        assertEquals(2, applied.size());
        assertEquals("first", Files.readString(workspace.resolve("First.txt")));
        assertEquals("second", Files.readString(workspace.resolve("Second.txt")));
        assertEquals("diff First.txt", applied.get(0).getDiff());
        assertEquals("diff Second.txt", applied.get(1).getDiff());
        assertEquals("First.txt", storedChanges.get(0).getSnapshotPaths());
        assertEquals("Second.txt", storedChanges.get(1).getSnapshotPaths());
        verify(snapshots, times(2)).capture(eq(project), any(String.class), eq(List.of("First.txt", "Second.txt")));
        verify(snapshots, never()).capture(any(StudentProject.class), any(String.class));
        verify(snapshots).changedFiles(any(StudentProject.class), eq(before), eq(after));
        verify(projectService).refreshProjectMetadata(7, 12);
        verify(invalidator).invalidate(eq(project), eq(List.of("First.txt", "Second.txt")));
        verify(taskService, never()).updateTask(eq(1L), eq("completed"), any(), any());
    }

    @Test
    void exposesDeferredBatchApplyForAgentStepSnapshotFinalization() {
        boolean exists = java.util.Arrays.stream(DiffService.class.getMethods())
                .anyMatch(method -> method.getName().equals("stageAndApplyBatchDeferred")
                        && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{
                                Integer.class, StudentProject.class, String.class, Long.class, List.class}));

        assertEquals(true, exists, "DiffService must expose deferred batch apply for Agent step snapshots");
    }

    @Test
    void deferredBatchDefersAfterSnapshotUntilTheAgentStepSchedulesIt() throws Exception {
        AtomicReference<AgentFileChange> stored = new AtomicReference<>();
        AgentFileChangeMapper mapper = mock(AgentFileChangeMapper.class);
        when(mapper.update(any(), any())).thenReturn(1);
        doAnswer(invocation -> { stored.set(invocation.getArgument(0)); return 1; }).when(mapper).insert(any(AgentFileChange.class));
        when(mapper.selectOne(any())).thenAnswer(invocation -> stored.get());
        StudentProjectService projects = mock(StudentProjectService.class);
        GitSnapshotService snapshots = mock(GitSnapshotService.class);
        GitSnapshotService.Snapshot before = new GitSnapshotService.Snapshot(true, "before", "tree", "");
        GitSnapshotService.Snapshot after = new GitSnapshotService.Snapshot(true, "after", "tree", "");
        when(snapshots.capture(eq(project()), any(String.class), eq(List.of("Main.java")))).thenReturn(before, after);
        when(snapshots.usablePair(any(), any())).thenReturn(true);
        when(snapshots.changedFiles(any(), eq(before), eq(after))).thenReturn(List.of(new GitSnapshotService.ChangedFile("M", "", "Main.java")));
        when(snapshots.serializePaths(any())).thenReturn("Main.java");

        DiffService service = service(projects, taskService(), mapper, snapshots, WorkspaceContextInvalidator.noop());
        PendingChange change = service.stageAndApplyBatchDeferred(7, project(), "conversation", 1L,
                List.of(new DiffService.ChangeRequest("Main.java", "", "new", "create"))).get(0);

        verify(snapshots, times(1)).capture(any(StudentProject.class), any(String.class), any(Collection.class));
        assertEquals("pending_step", stored.get().getSnapshotStatus());
        service.scheduleDeferredSnapshot(change.getId());
        service.awaitDeferredSnapshots(1L, "test");

        verify(snapshots, times(2)).capture(any(StudentProject.class), any(String.class), any(Collection.class));
        assertEquals("captured", stored.get().getSnapshotStatus());
    }

    @Test
    void startupRecoveryConvertsInterruptedPendingStepToContentFallback() {
        AgentFileChange interrupted = new AgentFileChange();
        interrupted.setSnapshotStatus("pending_step");
        AgentFileChangeMapper mapper = mock(AgentFileChangeMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(interrupted));
        DiffService service = service(mock(StudentProjectService.class), taskService(), mapper,
                mock(GitSnapshotService.class), WorkspaceContextInvalidator.noop());

        service.recoverInterruptedDeferredSnapshots();

        assertEquals("recovered_content", interrupted.getSnapshotStatus());
        verify(mapper).update(any(), any());
    }

    @Test
    void applyInvalidatesContextOnlyAfterTheWriteSucceeds() throws Exception {
        Path file = Files.writeString(workspace.resolve("Main.java"), "class Original {}\n");
        AtomicReference<AgentFileChange> storedChange = new AtomicReference<>();
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        doAnswer(invocation -> {
            storedChange.set(invocation.getArgument(0));
            return 1;
        }).when(fileChangeMapper).insert(any(AgentFileChange.class));
        when(fileChangeMapper.selectOne(any())).thenAnswer(invocation -> storedChange.get());

        StudentProjectService projectService = mock(StudentProjectService.class);
        StudentProject project = project();
        when(projectService.getOwnedProject(7, 12)).thenReturn(project);
        GitSnapshotService snapshots = unavailableSnapshots();
        WorkspaceContextInvalidator invalidator = mock(WorkspaceContextInvalidator.class);
        DiffService service = service(projectService, taskService(), fileChangeMapper, snapshots, invalidator);

        PendingChange change = service.stage(7, project, "conversation", 1L, "Main.java",
                "class Original {}\n", "class AgentEdit {}\n", "modify");
        service.apply(7, change.getId());

        assertEquals("class AgentEdit {}\n", Files.readString(file));
        verify(invalidator).invalidate(eq(project), eq(List.of("Main.java")));
    }

    @Test
    void undoInvalidatesContextAfterRestoringTheFile() throws Exception {
        Path file = Files.writeString(workspace.resolve("Main.java"), "class AgentEdit {}\n");
        AtomicReference<AgentFileChange> storedChange = new AtomicReference<>();
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        doAnswer(invocation -> {
            storedChange.set(invocation.getArgument(0));
            return 1;
        }).when(fileChangeMapper).insert(any(AgentFileChange.class));
        when(fileChangeMapper.selectOne(any())).thenAnswer(invocation -> storedChange.get());
        when(fileChangeMapper.update(any(), any())).thenReturn(1);

        StudentProjectService projectService = mock(StudentProjectService.class);
        StudentProject project = project();
        when(projectService.getOwnedProject(7, 12)).thenReturn(project);
        GitSnapshotService snapshots = unavailableSnapshots();
        WorkspaceContextInvalidator invalidator = mock(WorkspaceContextInvalidator.class);
        DiffService service = service(projectService, taskService(), fileChangeMapper, snapshots, invalidator);

        PendingChange change = service.stage(7, project, "conversation", 1L, "Main.java",
                "class Original {}\n", "class AgentEdit {}\n", "modify");
        storedChange.get().setStatus("applied");
        service.undo(7, change.getId());

        assertEquals("class Original {}\n", Files.readString(file));
        verify(invalidator).invalidate(eq(project), eq(List.of("Main.java")));
    }

    @Test
    void conflictDoesNotInvalidateContext() throws Exception {
        Path file = Files.writeString(workspace.resolve("Main.java"), "class Original {}\n");
        AtomicReference<AgentFileChange> storedChange = new AtomicReference<>();
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        doAnswer(invocation -> {
            storedChange.set(invocation.getArgument(0));
            return 1;
        }).when(fileChangeMapper).insert(any(AgentFileChange.class));
        when(fileChangeMapper.selectOne(any())).thenAnswer(invocation -> storedChange.get());

        StudentProjectService projectService = mock(StudentProjectService.class);
        StudentProject project = project();
        when(projectService.getOwnedProject(7, 12)).thenReturn(project);
        WorkspaceContextInvalidator invalidator = mock(WorkspaceContextInvalidator.class);
        DiffService service = service(projectService, taskService(), fileChangeMapper, unavailableSnapshots(), invalidator);
        PendingChange change = service.stage(7, project, "conversation", 1L, "Main.java",
                "class Original {}\n", "class AgentEdit {}\n", "modify");
        Files.writeString(file, "class HumanEdit {}\n");

        assertThrows(DiffService.ChangeConflictException.class, () -> service.apply(7, change.getId()));

        verifyNoInteractions(invalidator);
    }

    @Test
    void capturesARealDeletedSkillFileAsDurableSnapshotEvidence() throws Exception {
        Path skill = workspace.resolve("skills/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "# skill instructions\n");
        StudentProject project = project();
        GitSnapshotService snapshots = new GitSnapshotService();
        GitSnapshotService.Snapshot before = snapshots.capture(project, "before delete skill");
        Files.delete(skill);
        Files.delete(skill.getParent());
        GitSnapshotService.Snapshot after = snapshots.capture(project, "after delete skill");
        assertTrue(snapshots.usablePair(before, after));

        AtomicReference<AgentFileChange> stored = new AtomicReference<>();
        AgentFileChangeMapper fileChanges = mock(AgentFileChangeMapper.class);
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(fileChanges).insert(any(AgentFileChange.class));
        AgentTaskService tasks = taskService();
        DiffService service = service(mock(StudentProjectService.class), tasks, fileChanges, snapshots,
                WorkspaceContextInvalidator.noop());

        List<PendingChange> recorded = service.recordSnapshotDiffWithoutTaskProjection(
                7, project, "conversation", 1L, "command_approval", before, after);

        assertEquals(1, recorded.size());
        assertEquals("skills/SKILL.md", recorded.get(0).getRelativePath());
        assertEquals("delete", recorded.get(0).getChangeType());
        assertEquals("# skill instructions\n", stored.get().getBeforeContent());
        assertEquals("", stored.get().getAfterContent());
        assertEquals("captured", stored.get().getSnapshotStatus());
        assertEquals("applied", stored.get().getStatus());
        verify(tasks, never()).updateTask(eq(1L), any(), any(), any());
    }

    @Test
    void recordsApprovedCommandSnapshotEvidenceWithoutMutatingWaitingTaskStatus() {
        AgentFileChangeMapper fileChanges = mock(AgentFileChangeMapper.class);
        StudentProjectService projectService = mock(StudentProjectService.class);
        AgentTaskService tasks = taskService();
        GitSnapshotService snapshots = mock(GitSnapshotService.class);
        StudentProject project = project();
        GitSnapshotService.Snapshot before = new GitSnapshotService.Snapshot(true, "before-71", "tree", "");
        GitSnapshotService.Snapshot after = new GitSnapshotService.Snapshot(true, "after-71", "tree", "");
        GitSnapshotService.ChangedFile deleted = new GitSnapshotService.ChangedFile("D", "", "skills/SKILL.md");
        when(snapshots.usablePair(before, after)).thenReturn(true);
        when(snapshots.changedFiles(project, before, after)).thenReturn(List.of(deleted));
        when(snapshots.readTextAt(project, "before-71", "skills/SKILL.md")).thenReturn("skill instructions\n");
        when(snapshots.readTextAt(project, "after-71", "skills/SKILL.md")).thenReturn("");
        when(snapshots.diffForFile(project, before, after, deleted)).thenReturn("diff --git a/skills/SKILL.md b/skills/SKILL.md");
        DiffService service = service(projectService, tasks, fileChanges, snapshots, WorkspaceContextInvalidator.noop());

        List<PendingChange> recorded = service.recordSnapshotDiffWithoutTaskProjection(
                7, project, "conversation", 1L, "command_approval", before, after);

        assertEquals(1, recorded.size());
        assertEquals("skills/SKILL.md", recorded.get(0).getRelativePath());
        assertEquals("delete", recorded.get(0).getChangeType());
        verify(tasks, never()).updateTask(eq(1L), any(), any(), any());
    }

    private DiffService service(StudentProjectService projectService, AgentTaskService taskService,
                                AgentFileChangeMapper fileChangeMapper, GitSnapshotService snapshots,
                                WorkspaceContextInvalidator invalidator) {
        return new DiffService(projectService, taskService, fileChangeMapper, snapshots,
                new com.labex.labexagent.workspace.WorkspaceLeaseService(), invalidator);
    }

    private GitSnapshotService unavailableSnapshots() {
        GitSnapshotService snapshots = mock(GitSnapshotService.class);
        GitSnapshotService.Snapshot unavailable = new GitSnapshotService.Snapshot(false, "", "unavailable", "test");
        when(snapshots.capture(any(StudentProject.class), any(String.class))).thenReturn(unavailable);
        when(snapshots.capture(any(StudentProject.class), any(String.class), any(Collection.class))).thenReturn(unavailable);
        return snapshots;
    }

    private AgentTaskService taskService() {
        AgentTaskService taskService = mock(AgentTaskService.class);
        AgentChangeSet changeSet = new AgentChangeSet();
        changeSet.setChangeSetId(99L);
        when(taskService.getOrCreateOpenChangeSet(7, 12, "conversation", 1L)).thenReturn(changeSet);
        return taskService;
    }

    private StudentProject project() {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return project;
    }
}
