package com.labex.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

import com.labex.entity.StudentProject;
import com.labex.service.impl.StudentProjectServiceImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

class StudentProjectServicePathTest {

    @TempDir
    Path workspace;

    @Test
    void rejectsLinkedFilesWhenReadingProjectContent() throws Exception {
        Path outside = Files.createTempFile("labex-project-outside", ".txt");
        Files.writeString(outside, "outside content");
        Path linkedFile = workspace.resolve("linked.txt");
        try {
            Files.createSymbolicLink(linkedFile, outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symbolic links are unavailable in this test environment");
        }
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        StudentProjectServiceImpl service = spy(new StudentProjectServiceImpl());
        doReturn(project).when(service).getOwnedProject(7, 12);

        assertThrows(IllegalArgumentException.class,
                () -> service.readProjectFile(7, 12, "linked.txt"));
    }

    @Test
    void surfacesDirectoryListingFailuresInsteadOfReturningAnEmptyTree() throws Exception {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        StudentProjectServiceImpl service = spy(new StudentProjectServiceImpl());
        doReturn(project).when(service).getOwnedProject(7, 12);

        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.list(workspace)).thenThrow(new IOException("simulated listing failure"));

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.listProjectTree(7, 12, null));

            assertEquals("Failed to list project tree", error.getMessage());
        }
    }


    @Test
    void listsDependencyDirectoriesForOnDemandWorkspaceBrowsing() throws Exception {
        Path dependencyDirectory = Files.createDirectories(workspace.resolve("node_modules"));
        Files.writeString(dependencyDirectory.resolve("package.json"), "{\"name\":\"sample\"}");
        StudentProject project = ownedProject();
        StudentProjectServiceImpl service = spy(new StudentProjectServiceImpl());
        doReturn(project).when(service).getOwnedProject(7, 12);

        List<Map<String, Object>> entries = service.listProjectTree(7, 12, null);

        assertTrue(entries.stream().anyMatch(entry -> "node_modules".equals(entry.get("name"))
                && "directory".equals(entry.get("type"))));
    }

    @Test
    void returnsReadonlyPreviewForLargeTextLogFiles() throws Exception {
        Files.writeString(workspace.resolve("application.log"), "x".repeat(2 * 1024 * 1024 + 1));
        StudentProject project = ownedProject();
        StudentProjectServiceImpl service = spy(new StudentProjectServiceImpl());
        doReturn(project).when(service).getOwnedProject(7, 12);

        Map<String, Object> result = service.readProjectFileForEditor(7, 12, "application.log");

        assertTrue((Boolean) result.get("truncated"));
        assertTrue((Boolean) result.get("readOnly"));
        assertTrue(((String) result.get("content")).length() > 0);
        assertTrue(((String) result.get("content")).length() < 2 * 1024 * 1024);
    }

    private StudentProject ownedProject() {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return project;
    }



    @Test
    void userFacingProjectMetadataCountsDependencyAndLogFiles() throws Exception {
        Files.writeString(workspace.resolve("application.log"), "log");
        Files.createDirectories(workspace.resolve("node_modules"));
        Files.writeString(workspace.resolve("node_modules/package.json"), "{}");
        StudentProject project = ownedProject();
        StudentProjectServiceImpl service = spy(new StudentProjectServiceImpl());
        doReturn(project).when(service).getOwnedProject(7, 12);
        doReturn(true).when(service).updateById(project);

        service.refreshProjectMetadata(7, 12);

        assertEquals(2, project.getFileCount());
        assertFalse(project.getStructureJson().contains("node_modules"),
                "Stored Agent context structure must remain separate from user-facing file counts");
    }



    @Test
    void projectMetadataExcludesInternalAgentRuntimeCachesFromUserFileCounts() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        Path mavenCache = Files.createDirectories(workspace.resolve(".labex-agent/runtime/home/.m2/repository/org/apache/apache/31"));
        Files.writeString(mavenCache.resolve("apache-31.pom.lastUpdated"), "cached");
        StudentProject project = ownedProject();
        StudentProjectServiceImpl service = spy(new StudentProjectServiceImpl());
        doReturn(project).when(service).getOwnedProject(7, 12);
        doReturn(true).when(service).updateById(project);

        service.refreshProjectMetadata(7, 12);

        assertEquals(1, project.getFileCount());
        assertEquals(Files.size(workspace.resolve("pom.xml")), project.getTotalSize());
        assertFalse(project.getStructureJson().contains(".labex-agent"));
    }


    @Test
    void agentStructureSummaryHonorsWorkspaceIgnoreWithoutChangingUserFileCount() throws Exception {
        Files.writeString(workspace.resolve("application.log"), "log");
        Files.writeString(workspace.resolve(".labex-agentignore"), "application.log\n");
        StudentProject project = ownedProject();
        StudentProjectServiceImpl service = spy(new StudentProjectServiceImpl());
        doReturn(project).when(service).getOwnedProject(7, 12);
        doReturn(true).when(service).updateById(project);

        service.refreshProjectMetadata(7, 12);

        assertEquals(2, project.getFileCount());
        assertFalse(project.getStructureJson().contains("application.log"));
    }



    @Test
    void createsDefaultAgentIgnoreFileWithPreviousDependencyRestrictions() throws Exception {
        StudentProjectServiceImpl service = spy(new StudentProjectServiceImpl());
        ReflectionTestUtils.setField(service, "projectBasePath", workspace.toString());
        doReturn(true).when(service).save(any(StudentProject.class));

        StudentProject project = service.createEmptyProject(7, "sample");
        Path ignoreFile = Path.of(project.getWorkspacePath()).resolve(".labex-agentignore");

        assertTrue(Files.isRegularFile(ignoreFile));
        String content = Files.readString(ignoreFile);
        assertTrue(content.contains("node_modules/"));
        assertTrue(content.contains("# Agent never automatically reads files larger than 20 MiB."));
    }

    @Test
    void preventsAgentReadsAboveTwentyMiB() throws Exception {
        Files.write(workspace.resolve("oversized.log"), new byte[20 * 1024 * 1024 + 1]);
        StudentProject project = ownedProject();
        StudentProjectServiceImpl service = spy(new StudentProjectServiceImpl());
        doReturn(project).when(service).getOwnedProject(7, 12);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.readProjectFile(7, 12, "oversized.log"));

        assertEquals("File exceeds max_agent_read_file_bytes=20971520", error.getMessage());
    }



    @Test
    void createsDefaultAgentIgnoreFileWhenAnExistingWorkspaceIsFirstBrowsed() throws Exception {
        StudentProject project = ownedProject();
        StudentProjectServiceImpl service = spy(new StudentProjectServiceImpl());
        doReturn(project).when(service).getOwnedProject(7, 12);

        service.listProjectTree(7, 12, null);

        assertTrue(Files.isRegularFile(workspace.resolve(".labex-agentignore")));
    }



    @Test
    void returnsDirectoryEntriesInBoundedPages() throws Exception {
        for (int index = 0; index < 5; index++) {
            Files.writeString(workspace.resolve("file-" + index + ".txt"), "content");
        }
        StudentProject project = ownedProject();
        StudentProjectServiceImpl service = spy(new StudentProjectServiceImpl());
        doReturn(project).when(service).getOwnedProject(7, 12);

        StudentProjectService.ProjectTreePage first = service.listProjectTreePage(7, 12, "", 0, 2);
        StudentProjectService.ProjectTreePage second = service.listProjectTreePage(7, 12, "", first.nextOffset(), 2);

        assertEquals(2, first.entries().size());
        assertEquals(2, first.nextOffset());
        assertEquals(2, second.entries().size());
        assertTrue(first.entries().stream().noneMatch(entry -> second.entries().contains(entry)));
    }

}
