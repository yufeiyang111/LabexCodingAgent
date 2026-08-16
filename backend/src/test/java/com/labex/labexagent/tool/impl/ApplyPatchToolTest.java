package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.labex.entity.AgentChangeSet;
import com.labex.entity.AgentFileChange;
import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.GitSnapshotService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.service.WorkspaceContextInvalidator;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.labexagent.workspace.WorkspaceLeaseService;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ApplyPatchToolTest {

    @BeforeAll
    static void initializeFileChangeMetadata() {
        if (TableInfoHelper.getTableInfo(AgentFileChange.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentFileChange.class);
        }
    }

    @TempDir
    Path workspace;

    private final StudentProject project = new StudentProject();

    @Test
    void missingOrEmptyChangesFailWithoutStaging() throws Exception {
        DiffService diffService = mock(DiffService.class);
        ApplyPatchTool tool = new ApplyPatchTool(diffService);

        ToolResult missing = tool.execute(context(), new JsonObject());
        ToolResult empty = tool.execute(context(), args());

        assertFalse(missing.isSuccess());
        assertEquals("changes 参数必须是数组", missing.getContent());
        assertFalse(empty.isSuccess());
        assertEquals("changes 不能为空", empty.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void validatesEveryChangeBeforeCallingBatchService() throws Exception {
        Files.writeString(workspace.resolve("Existing.txt"), "old value");
        DiffService diffService = mock(DiffService.class);
        JsonObject valid = change("Existing.txt", "replace", null);
        valid.addProperty("old_string", "old");
        valid.addProperty("new_string", "new");
        JsonObject unsafe = change("../outside.txt", "replace", null);
        unsafe.addProperty("old_string", "outside");
        unsafe.addProperty("new_string", "updated");
        JsonObject request = args(valid, unsafe);

        ToolResult result = new ApplyPatchTool(diffService).execute(context(), request);

        assertFalse(result.isSuccess());
        assertEquals("path escapes workspace", result.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void rejectsInvalidOperationsAndReplacementTargetsWithoutStaging() throws Exception {
        Files.writeString(workspace.resolve("Existing.txt"), "old old");
        DiffService diffService = mock(DiffService.class);
        ApplyPatchTool tool = new ApplyPatchTool(diffService);

        ToolResult operation = tool.execute(context(), args(change("Existing.txt", "rename", null)));
        JsonObject replace = change("Existing.txt", "replace", null);
        replace.addProperty("old_string", "old");
        replace.addProperty("new_string", "new");
        ToolResult duplicateMatch = tool.execute(context(), args(replace));

        assertFalse(operation.isSuccess());
        assertEquals("Unsupported patch operation: rename", operation.getContent());
        assertFalse(duplicateMatch.isSuccess());
        assertEquals("old_string must match exactly once: Existing.txt", duplicateMatch.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void rejectsEmptyOrUnmatchedOldStringWithoutStaging() throws Exception {
        Files.writeString(workspace.resolve("Existing.txt"), "unchanged");
        DiffService diffService = mock(DiffService.class);
        ApplyPatchTool tool = new ApplyPatchTool(diffService);
        JsonObject empty = change("Existing.txt", "replace", null);
        empty.addProperty("new_string", "new");
        JsonObject missing = change("Existing.txt", "replace", null);
        missing.addProperty("old_string", "missing");
        missing.addProperty("new_string", "new");

        ToolResult emptyResult = tool.execute(context(), args(empty));
        ToolResult missingResult = tool.execute(context(), args(missing));

        assertFalse(emptyResult.isSuccess());
        assertEquals("old_string must not be empty", emptyResult.getContent());
        assertFalse(missingResult.isSuccess());
        assertEquals("old_string was not found: Existing.txt", missingResult.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void rejectsWholeFileReplacementSoWriteFileOwnsIntentionalFullContentReplacement() throws Exception {
        Files.writeString(workspace.resolve("Existing.txt"), "before");
        DiffService diffService = mock(DiffService.class);
        ApplyPatchTool tool = new ApplyPatchTool(diffService);
        JsonObject replace = change("Existing.txt", "replace", null);
        replace.addProperty("old_string", "before");
        replace.addProperty("new_string", "after");

        ToolResult result = tool.execute(context(), args(replace));

        assertFalse(result.isSuccess());
        assertEquals("replace must preserve surrounding content; use write_file for an intentional full-file replacement: Existing.txt",
                result.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void rejectsNonEditableTargetsAndOversizedContentWithoutStaging() throws Exception {
        DiffService diffService = mock(DiffService.class);
        ApplyPatchTool tool = new ApplyPatchTool(diffService);
        Files.createDirectory(workspace.resolve("directory"));
        Files.write(workspace.resolve("binary.bin"), new byte[]{1, 0, 2});
        Files.writeString(workspace.resolve("large.txt"), "x".repeat((int) ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES + 1));
        Files.writeString(workspace.resolve("replacement.txt"), "prefix-old-suffix");
        String oversizedContent = "x".repeat((int) ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES + 1);

        ToolResult directory = tool.execute(context(), args(change("directory", "delete", null)));
        ToolResult binary = tool.execute(context(), args(change("binary.bin", "delete", null)));
        ToolResult large = tool.execute(context(), args(change("large.txt", "delete", null)));
        JsonObject replace = change("replacement.txt", "replace", null);
        replace.addProperty("old_string", "old");
        replace.addProperty("new_string", oversizedContent);
        ToolResult replacement = tool.execute(context(), args(replace));

        assertFalse(directory.isSuccess());
        assertEquals("Patch target is not a regular file", directory.getContent());
        assertFalse(binary.isSuccess());
        assertEquals("Patch target appears to be binary", binary.getContent());
        assertFalse(large.isSuccess());
        assertEquals("Patch target exceeds max_file_bytes=" + ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES,
                large.getContent());
        assertFalse(replacement.isSuccess());
        assertEquals("File content exceeds max_file_bytes=" + ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES,
                replacement.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void refusesToClaimDeletionWhenTheTargetIsAlreadyAbsent() throws Exception {
        DiffService diffService = mock(DiffService.class);

        ToolResult result = new ApplyPatchTool(diffService).execute(context(),
                args(change("skills/SKILL.md", "delete", null)));

        assertFalse(result.isSuccess());
        assertEquals("path does not exist", result.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void deleteMutatesTheRealWorkspaceBeforeReportingDurableMutationEvidence() throws Exception {
        Path target = workspace.resolve("skills/SKILL.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "# skill instructions\n");
        AtomicReference<AgentFileChange> stored = new AtomicReference<>();
        AgentFileChangeMapper fileChanges = mock(AgentFileChangeMapper.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(fileChanges).insert(any(AgentFileChange.class));
        when(fileChanges.selectOne(any())).thenAnswer(invocation -> stored.get());
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentChangeSet changeSet = new AgentChangeSet();
        changeSet.setChangeSetId(99L);
        when(tasks.getOrCreateOpenChangeSet(7, 12, "conversation", 1L)).thenReturn(changeSet);
        StudentProjectService projects = mock(StudentProjectService.class);
        GitSnapshotService snapshots = mock(GitSnapshotService.class);
        GitSnapshotService.Snapshot unavailable = new GitSnapshotService.Snapshot(false, "", "unavailable", "test");
        when(snapshots.capture(any(StudentProject.class), any(String.class), any(Collection.class)))
                .thenReturn(unavailable);
        DiffService realDiffs = new DiffService(projects, tasks, fileChanges, snapshots,
                new WorkspaceLeaseService(), WorkspaceContextInvalidator.noop());

        ToolResult result = new ApplyPatchTool(realDiffs).execute(context(),
                args(change("skills/SKILL.md", "delete", null)));

        assertTrue(result.isSuccess());
        assertFalse(Files.exists(target));
        assertEquals(stored.get().getChangeId(), result.getPendingChangeId());
        assertThat(result.getWorkspaceIdentity())
                .containsEntry("relativePaths", List.of("skills/SKILL.md"));
        @SuppressWarnings("unchecked")
        Map<String, Object> mutation = (Map<String, Object>) result.durableResultMetadata().get("workspaceMutation");
        assertThat(mutation)
                .containsEntry("state", "applied")
                .containsEntry("changeIds", List.of(stored.get().getChangeId()));
        @SuppressWarnings("unchecked")
        Map<String, Object> verification = (Map<String, Object>) result.durableResultMetadata()
                .get("workspaceVerification");
        assertThat(verification)
                .containsEntry("state", "verified")
                .containsEntry("targets", List.of(Map.of(
                        "path", "skills/SKILL.md",
                        "expectedState", "absent",
                        "observedState", "absent")));
        assertThat(stored.get())
                .extracting(AgentFileChange::getRelativePath, AgentFileChange::getChangeType,
                        AgentFileChange::getBeforeContent, AgentFileChange::getAfterContent,
                        AgentFileChange::getStatus)
                .containsExactly("skills/SKILL.md", "delete", "# skill instructions\n", "", "applied");
        verify(projects).refreshProjectMetadata(7, 12);
    }

    @Test
    void rejectsCanonicalPatchWhenOperationIsOmitted() throws Exception {
        DiffService diffService = mock(DiffService.class);
        JsonObject change = new JsonObject();
        change.addProperty("path", "AdminPage.jsx");
        change.addProperty("old_string", "PUBLISHED");
        change.addProperty("new_string", "published");

        ToolResult result = new ApplyPatchTool(diffService).execute(context(), args(change));

        assertFalse(result.isSuccess());
        assertEquals("operation is required", result.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void batchesPreparedReplaceAndDeleteInInputOrder() throws Exception {
        Files.writeString(workspace.resolve("Replace.txt"), "old-value");
        Files.writeString(workspace.resolve("Delete.txt"), "delete-value");
        DiffService diffService = mock(DiffService.class);
        PendingChange replace = pendingChange("replace-id", "diff2");
        PendingChange delete = pendingChange("delete-id", "diff3");
        when(diffService.stageAndApplyBatchDeferred(eq(7), same(project), eq("conversation"), eq(1L), any()))
                .thenReturn(List.of(replace, delete));
        JsonObject replaceRequest = change("Replace.txt", "replace", null);
        replaceRequest.addProperty("old_string", "old");
        replaceRequest.addProperty("new_string", "new");

        AgentContext context = context();
        context.setExecutionEpoch(9L);
        ToolResult result = new ApplyPatchTool(diffService).execute(context, args(
                replaceRequest, change("Delete.txt", "delete", null)));

        ArgumentCaptor<List<DiffService.ChangeRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(diffService).stageAndApplyBatchDeferred(eq(7), same(project), eq("conversation"), eq(1L), requests.capture());
        assertEquals(List.of(
                new DiffService.ChangeRequest("Replace.txt", "old-value", "new-value", "modify"),
                new DiffService.ChangeRequest("Delete.txt", "delete-value", "", "delete")), requests.getValue());
        assertTrue(result.isSuccess());
        assertEquals("已自动应用 2 个文件变更（可随时回退）", result.getContent());
        assertEquals("diff2\ndiff3\n", result.getDiff());
        assertEquals("replace-id", result.getPendingChangeId());
        assertThat(result.getWorkspaceIdentity())
                .containsEntry("taskId", 1L)
                .containsEntry("executionEpoch", 9L)
                .containsEntry("workingDirectory", ".")
                .containsEntry("relativePaths", List.of("Delete.txt", "Replace.txt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> mutation = (Map<String, Object>) result.durableResultMetadata().get("workspaceMutation");
        assertThat(mutation)
                .containsEntry("state", "applied")
                .containsEntry("changeIds", List.of("replace-id", "delete-id"));
    }

    @Test
    void enforcesBatchSizeLimitWithoutStaging() throws Exception {
        DiffService diffService = mock(DiffService.class);
        JsonObject request = args();
        for (int index = 0; index < 41; index++) {
            request.getAsJsonArray("changes").add(change("File" + index + ".txt", "create", "content"));
        }

        ToolResult result = new ApplyPatchTool(diffService).execute(context(), request);

        assertFalse(result.isSuccess());
        assertEquals("Too many changes (max_changes=40)", result.getContent());
        verifyNoInteractions(diffService);
    }

    private AgentContext context() {
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return AgentContext.create("session", 7, project, "conversation", 1L);
    }

    private JsonObject args(JsonObject... changes) {
        JsonObject args = new JsonObject();
        JsonArray array = new JsonArray();
        for (JsonObject change : changes) {
            array.add(change);
        }
        args.add("changes", array);
        return args;
    }

    private JsonObject change(String path, String operation, String content) {
        JsonObject change = new JsonObject();
        change.addProperty("path", path);
        change.addProperty("operation", operation);
        if (content != null) {
            change.addProperty("content", content);
        }
        return change;
    }

    private PendingChange pendingChange(String id, String diff) {
        return new PendingChange(id, 7, 12, "conversation", 1L, 99L,
                "path", "modify", "", "", diff, "applied");
    }
}
