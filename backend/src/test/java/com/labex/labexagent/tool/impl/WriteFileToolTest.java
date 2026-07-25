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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

class WriteFileToolTest {

    @TempDir
    Path workspace;

    private StudentProject project;

    @Test
    void failsWhenFilePathIsMissingOrEmpty() throws Exception {
        DiffService diffService = mock(DiffService.class);
        WriteFileTool tool = tool(diffService);

        ToolResult missing = tool.execute(context(), new JsonObject());
        ToolResult empty = tool.execute(context(), args("file_path", "", "content", "updated"));

        assertFalse(missing.isSuccess());
        assertEquals("file_path is required", missing.getContent());
        assertFalse(empty.isSuccess());
        assertEquals("file_path is required", empty.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void rejectsAbsoluteAndTraversalPathsWithoutStagingChanges() throws Exception {
        DiffService diffService = mock(DiffService.class);
        WriteFileTool tool = tool(diffService);

        ToolResult absolute = tool.execute(context(), args("file_path", "/outside.txt", "content", "updated"));
        ToolResult traversal = tool.execute(context(), args("file_path", "../outside.txt", "content", "updated"));
        ToolResult windowsAbsolute = tool.execute(context(), args("file_path", "C:\\outside.txt", "content", "updated"));

        assertFalse(absolute.isSuccess());
        assertEquals("Unsafe file path", absolute.getContent());
        assertFalse(traversal.isSuccess());
        assertEquals("Unsafe file path", traversal.getContent());
        assertFalse(windowsAbsolute.isSuccess());
        assertEquals("Unsafe file path", windowsAbsolute.getContent());
        verifyNoInteractions(diffService);
    }

    @Test
    void overwritesExistingNonemptyFileAndStagesExactModification() throws Exception {
        DiffService diffService = mock(DiffService.class);
        WriteFileTool tool = tool(diffService);
        String before = "class Main {}";
        String after = "class Main { void run() {} }";
        Files.writeString(workspace.resolve("Main.java"), before);
        AgentContext context = context();
        when(diffService.stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("Main.java"), eq(before), eq(after), eq("modify")))
                .thenReturn(pendingChange("Main.java", "modify", before, after));

        ToolResult result = tool.execute(context, args("file_path", "./Main.java", "content", after));

        assertTrue(result.isSuccess());
        assertEquals("diff", result.getDiff());
        assertEquals("change", result.getPendingChangeId());
        verify(diffService).stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("Main.java"), eq(before), eq(after), eq("modify"));
    }

    @Test
    void createsNewFileAndStagesEmptyBeforeContent() throws Exception {
        DiffService diffService = mock(DiffService.class);
        WriteFileTool tool = tool(diffService);
        AgentContext context = context();
        when(diffService.stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("NewFile.java"), eq(""), eq("class NewFile {}"), eq("create")))
                .thenReturn(pendingChange("NewFile.java", "create", "", "class NewFile {}"));

        ToolResult result = tool.execute(context, args("file_path", "NewFile.java", "content", "class NewFile {}"));

        assertTrue(result.isSuccess());
        verify(diffService).stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("NewFile.java"), eq(""), eq("class NewFile {}"), eq("create"));
    }

    @Test
    void emitsWriteLifecycleTimingLogsWithoutRecordingFileContent() throws Exception {
        DiffService diffService = mock(DiffService.class);
        WriteFileTool tool = tool(diffService);
        AgentContext context = context();
        String content = "secret-write-content";
        when(diffService.stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("Telemetry.java"), eq(""), eq(content), eq("create")))
                .thenReturn(pendingChange("Telemetry.java", "create", "", content));
        Logger logger = (Logger) LoggerFactory.getLogger(WriteFileTool.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ToolResult result = tool.execute(context, args("file_path", "Telemetry.java", "content", content));

            assertTrue(result.isSuccess());
            String messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(messages.contains("WRITE_FILE_TOOL_START"));
            assertTrue(messages.contains("WRITE_FILE_TOOL_SOURCE_READY"));
            assertTrue(messages.contains("WRITE_FILE_TOOL_COMPLETE"));
            assertFalse(messages.contains(content));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void characterizationSubmitsEmptyContentWhenContentIsOmitted() throws Exception {
        DiffService diffService = mock(DiffService.class);
        WriteFileTool tool = tool(diffService);
        AgentContext context = context();
        when(diffService.stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("EmptyContent.java"), eq(""), eq(""), eq("create")))
                .thenReturn(pendingChange("EmptyContent.java", "create", "", ""));

        ToolResult result = tool.execute(context, args("file_path", "EmptyContent.java"));

        assertTrue(result.isSuccess());
        verify(diffService).stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("EmptyContent.java"), eq(""), eq(""), eq("create"));
    }

    @Test
    void rejectsNonEditableExistingTargetsAndOversizedContentWithoutStaging() throws Exception {
        DiffService diffService = mock(DiffService.class);
        WriteFileTool tool = tool(diffService);
        Files.createDirectory(workspace.resolve("directory"));
        Files.write(workspace.resolve("binary.bin"), new byte[]{1, 0, 2});
        Files.writeString(workspace.resolve("large.txt"), "x".repeat((int) ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES + 1));
        String oversizedContent = "x".repeat((int) ToolSupport.MAX_TEXT_MUTATION_FILE_BYTES + 1);

        ToolResult directory = tool.execute(context(), args("file_path", "directory", "content", "updated"));
        ToolResult binary = tool.execute(context(), args("file_path", "binary.bin", "content", "updated"));
        ToolResult large = tool.execute(context(), args("file_path", "large.txt", "content", "updated"));
        ToolResult oversized = tool.execute(context(), args("file_path", "NewFile.txt", "content", oversizedContent));

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

    @ParameterizedTest
    @MethodSource("workspacePrefixedPaths")
    void normalizesWorkspacePrefixedPathsBeforeStaging(String rawPath) throws Exception {
        DiffService diffService = mock(DiffService.class);
        WriteFileTool tool = tool(diffService);
        AgentContext context = context();
        when(diffService.stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("src/Main.java"), eq(""), eq("updated"), eq("create")))
                .thenReturn(pendingChange("src/Main.java", "create", "", "updated"));

        ToolResult result = tool.execute(context, args("file_path", rawPath, "content", "updated"));

        assertTrue(result.isSuccess());
        verify(diffService).stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("src/Main.java"), eq(""), eq("updated"), eq("create"));
    }

    @ParameterizedTest
    @MethodSource("aliasArguments")
    void acceptsPathAndContentAliases(String pathKey, String contentKey) throws Exception {
        DiffService diffService = mock(DiffService.class);
        WriteFileTool tool = tool(diffService);
        AgentContext context = context();
        when(diffService.stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("Alias.java"), eq(""), eq("updated"), eq("create")))
                .thenReturn(pendingChange("Alias.java", "create", "", "updated"));

        ToolResult result = tool.execute(context, args(pathKey, "Alias.java", contentKey, "updated"));

        assertTrue(result.isSuccess());
        verify(diffService).stageAndApplyDeferred(eq(7), same(project), eq("conversation"), eq(1L),
                eq("Alias.java"), eq(""), eq("updated"), eq("create"));
    }

    private static Stream<String> workspacePrefixedPaths() {
        return Stream.of(" /workspace/src\\Main.java ", "workspace/src\\Main.java");
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> aliasArguments() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("path", "file_content"),
                org.junit.jupiter.params.provider.Arguments.of("filePath", "fileContent"));
    }

    private WriteFileTool tool(DiffService diffService) {
        return new WriteFileTool(diffService, mock(StudentProjectService.class));
    }

    private AgentContext context() {
        project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(99);
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

    private PendingChange pendingChange(String path, String changeType, String before, String after) {
        return new PendingChange("change", 7, 12, "conversation", 1L, 99L,
                path, changeType, before, after, "diff", "applied");
    }
}
