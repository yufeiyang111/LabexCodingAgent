package com.labex.labexagent.tool.impl;

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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ApplyPatchToolTest {

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
        DiffService diffService = mock(DiffService.class);
        JsonObject request = args(change("Created.txt", "create", "content"),
                change("../outside.txt", "create", "outside"));

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
    void rejectsNonEditableTargetsAndOversizedContentWithoutStaging() throws Exception {
        DiffService diffService = mock(DiffService.class);
        ApplyPatchTool tool = new ApplyPatchTool(diffService);
        Files.createDirectory(workspace.resolve("directory"));
        Files.write(workspace.resolve("binary.bin"), new byte[]{1, 0, 2});
        Files.writeString(workspace.resolve("large.txt"), "x".repeat((int) ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES + 1));
        Files.writeString(workspace.resolve("replacement.txt"), "old");
        String oversizedContent = "x".repeat((int) ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES + 1);

        ToolResult directory = tool.execute(context(), args(change("directory", "delete", null)));
        ToolResult binary = tool.execute(context(), args(change("binary.bin", "delete", null)));
        ToolResult large = tool.execute(context(), args(change("large.txt", "delete", null)));
        ToolResult create = tool.execute(context(), args(change("Created.txt", "create", oversizedContent)));
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
        assertFalse(create.isSuccess());
        assertEquals("File content exceeds max_file_bytes=" + ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES,
                create.getContent());
        assertFalse(replacement.isSuccess());
        assertEquals("File content exceeds max_file_bytes=" + ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES,
                replacement.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void infersReplaceWhenModelOmitsOperationButProvidesOldAndNewStrings() throws Exception {
        Files.writeString(workspace.resolve("AdminPage.jsx"), "status === 'PUBLISHED'");
        DiffService diffService = mock(DiffService.class);
        when(diffService.stageAndApplyBatchDeferred(eq(7), same(project), eq("conversation"), eq(1L), any()))
                .thenReturn(List.of(pendingChange("replace-id", "diff")));
        JsonObject change = new JsonObject();
        change.addProperty("path", "AdminPage.jsx");
        change.addProperty("old_string", "PUBLISHED");
        change.addProperty("new_string", "published");

        ToolResult result = new ApplyPatchTool(diffService).execute(context(), args(change));

        assertTrue(result.isSuccess());
    }

    @Test
    void batchesPreparedCreateReplaceAndDeleteInInputOrder() throws Exception {
        Files.writeString(workspace.resolve("Replace.txt"), "old-value");
        Files.writeString(workspace.resolve("Delete.txt"), "delete-value");
        DiffService diffService = mock(DiffService.class);
        PendingChange create = pendingChange("create-id", "diff1");
        PendingChange replace = pendingChange("replace-id", "diff2");
        PendingChange delete = pendingChange("delete-id", "diff3");
        when(diffService.stageAndApplyBatchDeferred(eq(7), same(project), eq("conversation"), eq(1L), any()))
                .thenReturn(List.of(create, replace, delete));
        JsonObject replaceRequest = change("Replace.txt", "replace", null);
        replaceRequest.addProperty("old_string", "old");
        replaceRequest.addProperty("new_string", "new");

        ToolResult result = new ApplyPatchTool(diffService).execute(context(), args(
                change("Create.txt", "create", "create-value"), replaceRequest, change("Delete.txt", "delete", null)));

        ArgumentCaptor<List<DiffService.ChangeRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(diffService).stageAndApplyBatchDeferred(eq(7), same(project), eq("conversation"), eq(1L), requests.capture());
        assertEquals(List.of(
                new DiffService.ChangeRequest("Create.txt", "", "create-value", "create"),
                new DiffService.ChangeRequest("Replace.txt", "old-value", "new-value", "modify"),
                new DiffService.ChangeRequest("Delete.txt", "delete-value", "", "delete")), requests.getValue());
        assertTrue(result.isSuccess());
        assertEquals("已自动应用 3 个文件变更（可随时回退）", result.getContent());
        assertEquals("diff1\ndiff2\ndiff3\n", result.getDiff());
        assertEquals("create-id", result.getPendingChangeId());
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
