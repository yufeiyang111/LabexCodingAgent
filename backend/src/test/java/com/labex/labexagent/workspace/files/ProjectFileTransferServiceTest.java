package com.labex.labexagent.workspace.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.auth.redis.AuthRedisStore;
import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.WorkspaceFileOperationProperties;
import com.labex.labexagent.workspace.WorkspaceOperationGuard;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFileTransferServiceTest {

    @TempDir
    Path workspace;

    private StudentProjectService projectService;
    private WorkspaceFileOperationProperties properties;
    private ProjectFileTransferService service;

    @BeforeEach
    void setUp() {
        projectService = mock(StudentProjectService.class);
        AuthRedisStore redisStore = mock(AuthRedisStore.class);
        when(redisStore.increment(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        properties = new WorkspaceFileOperationProperties();
        WorkspaceOperationGuard guard = new WorkspaceOperationGuard(redisStore, properties);
        service = new ProjectFileTransferService(projectService, properties, guard);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        when(projectService.getOwnedProject(7, 12)).thenReturn(project);
    }

    private ProjectFileTransferService.TransferRequest request(String source, String targetParent,
                                                               Map<String, String> decisions) {
        return new ProjectFileTransferService.TransferRequest(7, 12, source, targetParent, decisions);
    }

    @Test
    void copiesSingleFileAndDirectoryRecursively() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello");
        Path sourceDir = Files.createDirectories(workspace.resolve("src"));
        Files.writeString(sourceDir.resolve("Main.java"), "class Main {}");
        Files.createDirectories(sourceDir.resolve("sub"));
        Files.writeString(sourceDir.resolve("sub").resolve("b.txt"), "nested");
        Files.createDirectories(workspace.resolve("backup"));

        var fileCopy = service.copy(request("a.txt", "backup", null));
        assertEquals("done", fileCopy.status());
        assertEquals("backup/a.txt", fileCopy.targetPath());
        assertEquals("hello", Files.readString(workspace.resolve("backup").resolve("a.txt")));

        var dirCopy = service.copy(request("src", "backup", null));
        assertEquals("done", dirCopy.status());
        assertEquals("class Main {}", Files.readString(workspace.resolve("backup").resolve("src").resolve("Main.java")));
        assertEquals("nested", Files.readString(workspace.resolve("backup").resolve("src").resolve("sub").resolve("b.txt")));
    }

    @Test
    void reportsConflictWithoutWritingWhenDecisionMissing() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "new content");
        Path backup = Files.createDirectories(workspace.resolve("backup"));
        Files.writeString(backup.resolve("a.txt"), "old content");

        var outcome = service.copy(request("a.txt", "backup", null));

        assertEquals("conflict", outcome.status());
        assertEquals(1, outcome.conflicts().size());
        assertEquals("backup/a.txt", outcome.conflicts().get(0).path());
        assertEquals("file", outcome.conflicts().get(0).type());
        assertEquals("old content", Files.readString(backup.resolve("a.txt")));
    }

    @Test
    void overwriteReplacesExistingFileOnlyForSameType() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "new");
        Path backup = Files.createDirectories(workspace.resolve("backup"));
        Files.writeString(backup.resolve("a.txt"), "old");

        var done = service.copy(request("a.txt", "backup",
                Map.of("backup/a.txt", ProjectFileTransferService.ACTION_OVERWRITE)));
        assertEquals("done", done.status());
        assertEquals("new", Files.readString(backup.resolve("a.txt")));

        // 类型不一致（文件 → 已存在的同名文件夹）：即使选择覆盖也必须拒绝。
        Files.writeString(workspace.resolve("b.txt"), "text");
        Files.createDirectories(backup.resolve("b.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> service.copy(request("b.txt", "backup",
                        Map.of("backup/b.txt", ProjectFileTransferService.ACTION_OVERWRITE))));
    }

    @Test
    void skipKeepsExistingTargetIntact() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "new");
        Path backup = Files.createDirectories(workspace.resolve("backup"));
        Files.writeString(backup.resolve("a.txt"), "old");

        var outcome = service.copy(request("a.txt", "backup",
                Map.of("backup/a.txt", ProjectFileTransferService.ACTION_SKIP)));

        assertEquals("skipped", outcome.status());
        assertEquals("old", Files.readString(backup.resolve("a.txt")));
    }

    @Test
    void rejectsProtectedWorkspacePathsOnBothEnds() throws Exception {
        Files.createDirectories(workspace.resolve(".labex"));
        Files.writeString(workspace.resolve(".labex").resolve("runtime.env"), "secret=1");
        Files.writeString(workspace.resolve("a.txt"), "data");

        assertThrows(IllegalArgumentException.class, () -> service.copy(request(".labex/runtime.env", "", null)));
        assertThrows(IllegalArgumentException.class, () -> service.copy(request("a.txt", ".labex", null)));
        assertThrows(IllegalArgumentException.class, () -> service.move(request(".labex/runtime.env", "out", null)));
    }

    @Test
    void rejectsSelfCopyAndMovingFolderIntoItsOwnSubtree() throws Exception {
        Path folder = Files.createDirectories(workspace.resolve("project"));
        Files.writeString(folder.resolve("readme.md"), "# hi");
        Files.createDirectories(workspace.resolve("project").resolve("deep"));

        assertThrows(IllegalArgumentException.class, () -> service.copy(request("project/readme.md", "project", null)));
        assertThrows(IllegalArgumentException.class, () -> service.copy(request("project", "project/deep", null)));
        assertThrows(IllegalArgumentException.class, () -> service.move(request("project", "project/deep", null)));

        var noopMove = service.move(request("project/readme.md", "project", null));
        assertEquals("done", noopMove.status());
        assertTrue(Files.exists(folder.resolve("readme.md")));
    }

    @Test
    void enforcesByteFileCountAndDepthBudgetBeforeCopying() throws Exception {
        Files.createDirectories(workspace.resolve("out"));
        properties.setTransferMaxTotalBytes(10L);

        Path big = Files.createDirectories(workspace.resolve("big"));
        Files.writeString(big.resolve("payload.bin"), "x".repeat(64));
        IllegalArgumentException byteLimit = assertThrows(IllegalArgumentException.class,
                () -> service.copy(request("big", "out", null)));
        assertTrue(byteLimit.getMessage().contains("总大小"), byteLimit.getMessage());

        properties.setTransferMaxTotalBytes(1024L * 1024L);
        properties.setTransferMaxFiles(2);
        Path many = Files.createDirectories(workspace.resolve("many"));
        for (int i = 0; i < 3; i++) {
            Files.writeString(many.resolve("f" + i + ".txt"), "content" + i);
        }
        IllegalArgumentException countLimit = assertThrows(IllegalArgumentException.class,
                () -> service.copy(request("many", "out", null)));
        assertTrue(countLimit.getMessage().contains("文件数量"), countLimit.getMessage());

        properties.setTransferMaxFiles(2000);
        properties.setTransferMaxDepth(1);
        // 深度语义：相对源根的目录层数。maxDepth=1 允许一级子目录，二级即超限。
        Path level3 = Files.createDirectories(workspace.resolve("deep").resolve("level2").resolve("level3"));
        Files.writeString(level3.resolve("leaf.txt"), "leaf");
        IllegalArgumentException depthLimit = assertThrows(IllegalArgumentException.class,
                () -> service.copy(request("deep", "out", null)));
        assertTrue(depthLimit.getMessage().contains("层级"), depthLimit.getMessage());
    }

    @Test
    void movesAreNotBoundByByteBudgetButStillRateChecked() throws Exception {
        properties.setTransferMaxTotalBytes(4L);
        Path folder = Files.createDirectories(workspace.resolve("heavy"));
        Files.writeString(folder.resolve("blob.bin"), "x".repeat(512));
        Files.createDirectories(workspace.resolve("archive"));

        var outcome = service.move(request("heavy", "archive", null));

        assertEquals("done", outcome.status());
        assertTrue(Files.exists(workspace.resolve("archive").resolve("heavy").resolve("blob.bin")));
    }
}
