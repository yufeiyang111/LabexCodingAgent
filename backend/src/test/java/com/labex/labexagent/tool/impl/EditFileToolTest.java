package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.FileContentFingerprint;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EditFileToolTest {

    @TempDir
    Path workspace;

    private StudentProject project;

    @Test
    void failsWhenFilePathIsMissingOrEmpty() throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);

        ToolResult missing = tool.execute(context(), new JsonObject());
        ToolResult empty = tool.execute(context(), args("file_path", "", "old_string", "old", "new_string", "new"));

        assertFalse(missing.isSuccess());
        assertEquals("file_path is required", missing.getContent());
        assertFalse(empty.isSuccess());
        assertEquals("file_path is required", empty.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void failsWhenOldStringIsMissingOrEmpty() throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);

        ToolResult missing = tool.execute(context(), args("file_path", "Main.java"));
        ToolResult empty = tool.execute(context(), args("file_path", "Main.java", "old_string", "", "new_string", "new"));

        assertFalse(missing.isSuccess());
        assertEquals("old_string is required", missing.getContent());
        assertFalse(empty.isSuccess());
        assertEquals("old_string is required", empty.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void rejectsAbsoluteAndTraversalPathsWithoutStagingChanges() throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);

        ToolResult absolute = tool.execute(context(), args("file_path", "/outside.txt", "old_string", "old", "new_string", "new"));
        ToolResult traversal = tool.execute(context(), args("file_path", "../outside.txt", "old_string", "old", "new_string", "new"));

        assertFalse(absolute.isSuccess());
        assertEquals("Unsafe file path", absolute.getContent());
        assertFalse(traversal.isSuccess());
        assertEquals("Unsafe file path", traversal.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void characterizationRejectsNonexistentPathAsUnsafeBeforeFilesExistsBranch() throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);

        ToolResult result = tool.execute(context(), args("file_path", "Missing.java", "old_string", "old", "new_string", "new"));

        // Characterization only: resolveExisting throws before EditFileTool reaches Files.exists, so that
        // branch is currently unreachable. The roadmap has not yet assigned this file-tool defect to a phase.
        assertFalse(result.isSuccess());
        assertEquals("Unsafe file path", result.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void failsWithoutStagingWhenOldStringIsAbsent() throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);
        Files.writeString(workspace.resolve("Main.java"), "class Main {}");

        ToolResult result = tool.execute(context(), args("file_path", "Main.java", "old_string", "missing", "new_string", "new"));

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("OLD_STRING_NOT_FOUND"));
        assertTrue(result.getContent().contains("请重新读取"));
        verifyNoInteractions(diffService);
    }

    @Test
    void failsWithoutStagingWhenReplacementWouldNotChangeContent() throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);
        Files.writeString(workspace.resolve("Main.java"), "placeholder");

        ToolResult result = tool.execute(context(), args("file_path", "Main.java", "old_string", "placeholder", "new_string", "placeholder"));

        assertFalse(result.isSuccess());
        assertEquals("未找到要替换的内容", result.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void rejectsAmbiguousReplacementWithoutStaging() throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);
        Files.writeString(workspace.resolve("Main.java"), "old + old + unchanged");

        ToolResult result = tool.execute(context(), args("file_path", "Main.java", "old_string", "old", "new_string", "new"));

        assertFalse(result.isSuccess());
        assertEquals("old_string must match exactly once", result.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void replacesExactlyOneLiteralOccurrenceAndStagesExactChange() throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);
        String before = "old + unchanged";
        String after = "new + unchanged";
        Files.writeString(workspace.resolve("Main.java"), before);
        AgentContext context = context();
        when(diffService.stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("Main.java"), eq(before), eq(after), eq("modify")))
                .thenReturn(pendingChange("Main.java", before, after));

        ToolResult result = tool.execute(context, args("file_path", "./Main.java", "old_string", "old", "new_string", "new"));

        assertTrue(result.isSuccess());
        assertEquals("diff", result.getDiff());
        assertEquals("change", result.getPendingChangeId());
        verify(diffService).stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("Main.java"), eq(before), eq(after), eq("modify"));
    }

    @Test
    void rejectsNonEditableTargetsAndOversizedReplacementWithoutStaging() throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);
        Files.createDirectory(workspace.resolve("directory"));
        Files.write(workspace.resolve("binary.bin"), new byte[]{1, 0, 2});
        Files.writeString(workspace.resolve("large.txt"), "old" + "x".repeat((int) ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES));
        Files.writeString(workspace.resolve("replacement.txt"), "old");
        String oversizedReplacement = "x".repeat((int) ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES + 1);

        ToolResult directory = tool.execute(context(), args("file_path", "directory", "old_string", "old", "new_string", "new"));
        ToolResult binary = tool.execute(context(), args("file_path", "binary.bin", "old_string", "old", "new_string", "new"));
        ToolResult large = tool.execute(context(), args("file_path", "large.txt", "old_string", "old", "new_string", "new"));
        ToolResult oversized = tool.execute(context(), args("file_path", "replacement.txt", "old_string", "old", "new_string", oversizedReplacement));

        assertFalse(directory.isSuccess());
        assertEquals("File target is not a regular file", directory.getContent());
        assertFalse(binary.isSuccess());
        assertEquals("File target appears to be binary", binary.getContent());
        assertFalse(large.isSuccess());
        assertEquals("File target exceeds max_file_bytes=" + ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES, large.getContent());
        assertFalse(oversized.isSuccess());
        assertEquals("File content exceeds max_file_bytes=" + ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES,
                oversized.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void rejectsEditWhenExpectedFileVersionIsStaleWithoutStaging() throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);
        String original = "old + unchanged";
        Files.writeString(workspace.resolve("Main.java"), original);
        String expectedSha256 = FileContentFingerprint.sha256(original);
        Files.writeString(workspace.resolve("Main.java"), "changed externally");

        ToolResult result = tool.execute(context(), args(
                "file_path", "Main.java",
                "old_string", "changed externally",
                "new_string", "updated",
                "expected_sha256", expectedSha256));

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("FILE_CHANGED_SINCE_READ"));
        verifyNoInteractions(diffService);
    }

    @Test
    void reportsMissingOldStringWithRecoveryGuidance() throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);
        Files.writeString(workspace.resolve("Main.java"), "class Main {}");

        ToolResult result = tool.execute(context(), args(
                "file_path", "Main.java", "old_string", "missing", "new_string", "new"));

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("OLD_STRING_NOT_FOUND"));
        assertTrue(result.getContent().contains("\u8bf7\u91cd\u65b0\u8bfb\u53d6"));
        verifyNoInteractions(diffService);
    }

    @ParameterizedTest
    @MethodSource("aliasArguments")
    void acceptsFilePathOldStringAndNewStringAliases(String pathKey, String oldKey, String newKey) throws Exception {
        DiffService diffService = mock(DiffService.class);
        EditFileTool tool = tool(diffService);
        String before = "placeholder";
        String after = "updated";
        Files.writeString(workspace.resolve("Main.java"), before);
        AgentContext context = context();
        when(diffService.stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("Main.java"), eq(before), eq(after), eq("modify")))
                .thenReturn(pendingChange("Main.java", before, after));

        ToolResult result = tool.execute(context, args(pathKey, "Main.java", oldKey, "placeholder", newKey, "updated"));

        assertTrue(result.isSuccess());
        verify(diffService).stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("Main.java"), eq(before), eq(after), eq("modify"));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> aliasArguments() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("path", "oldString", "newString"),
                org.junit.jupiter.params.provider.Arguments.of("filePath", "old_text", "new_text"));
    }

    private EditFileTool tool(DiffService diffService) {
        return new EditFileTool(diffService, mock(StudentProjectService.class));
    }

    private AgentContext context() {
        project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return AgentContext.create("session", 7, project, "conversation", 1L);
    }

    private JsonObject args(String... entries) {
        JsonObject args = new JsonObject();
        for (int index = 0; index < entries.length; index += 2) {
            args.addProperty(entries[index], entries[index + 1]);
        }
        return args;
    }

    private PendingChange pendingChange(String path, String before, String after) {
        return new PendingChange("change", 7, 12, "conversation", 1L, 99L,
                path, "modify", before, after, "diff", "applied");
    }
}
