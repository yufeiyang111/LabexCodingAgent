package com.labex.labexagent.workspace.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.auth.redis.AuthRedisStore;
import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.WorkspaceFileOperationProperties;
import com.labex.labexagent.workspace.WorkspaceOperationGuard;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectExportJobServiceTest {

    @TempDir
    Path workspace;

    @TempDir
    Path storage;

    private StudentProjectService projectService;
    private WorkspaceFileOperationProperties properties;
    private ProjectExportJobService service;

    @BeforeEach
    void setUp() throws IOException {
        projectService = mock(StudentProjectService.class);
        AuthRedisStore redisStore = mock(AuthRedisStore.class);
        when(redisStore.increment(anyString(), any())).thenReturn(1L);
        properties = new WorkspaceFileOperationProperties();
        properties.setExportStorageDir(storage.toString());
        service = new ProjectExportJobService(projectService, properties,
                new WorkspaceOperationGuard(redisStore, properties));

        Files.writeString(workspace.resolve("README.md"), "# demo");
        Path src = Files.createDirectories(workspace.resolve("src"));
        Files.writeString(src.resolve("main.js"), "console.log(1)");
        Path deps = Files.createDirectories(workspace.resolve("node_modules").resolve("pkg"));
        Files.writeString(deps.resolve("index.js"), "module.exports = 1");
        Path platform = Files.createDirectories(workspace.resolve(".labex"));
        Files.writeString(platform.resolve("runtime.env"), "secret=1");

        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        project.setProjectName("demo-project");
        when(projectService.getOwnedProject(7, 12)).thenReturn(project);
        when(projectService.getOwnedProject(7, 99)).thenReturn(null);
    }

    private Set<String> zipEntryNames(Path zip) throws IOException {
        Set<String> names = new HashSet<>();
        try (InputStream input = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private ProjectExportJobService.ExportJobView awaitTerminal(Integer projectId, String jobId)
            throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            var view = service.getJob(7, projectId, jobId);
            if (!"PENDING".equals(view.status()) && !"RUNNING".equals(view.status())) {
                return view;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("export job did not reach terminal state");
    }

    @Test
    void excludesDependencyDirectoriesAndPlatformPathsByDefault() throws Exception {
        var view = service.createJob(7, 12, false);

        assertEquals("PENDING", view.status());
        var done = awaitTerminal(12, view.jobId());
        assertEquals("SUCCESS", done.status());
        assertEquals(100, done.progressPercent());

        Path zip = service.resolveDownload(7, 12, view.jobId());
        assertNotNull(zip);
        Set<String> names = zipEntryNames(zip);
        assertTrue(names.contains("src/main.js"));
        assertTrue(names.contains("README.md"));
        assertTrue(names.stream().noneMatch(name -> name.startsWith("node_modules/")));
        assertTrue(names.stream().noneMatch(name -> name.startsWith(".labex/")));

        assertEquals("demo-project.zip", service.projectZipName(7, 12, view.jobId()));
    }

    @Test
    void includeAllKeepsDependenciesButStillHidesPlatformPaths() throws Exception {
        var view = service.createJob(7, 12, true);
        var done = awaitTerminal(12, view.jobId());

        assertEquals("SUCCESS", done.status());
        Set<String> names = zipEntryNames(service.resolveDownload(7, 12, view.jobId()));
        assertTrue(names.stream().anyMatch(name -> name.startsWith("node_modules/pkg/")));
        assertTrue(names.stream().noneMatch(name -> name.startsWith(".labex/")));
    }

    @Test
    void enforcesTotalSizeBudgetDuringPreflight() throws IOException {
        properties.setExportMaxTotalBytes(4L);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createJob(7, 12, false));
        assertTrue(error.getMessage().contains("上限"));
    }

    @Test
    void rejectsForeignProjectsAndUnknownJobs() {
        assertThrows(IllegalArgumentException.class, () -> service.createJob(7, 99, false));
        assertThrows(IllegalArgumentException.class, () -> service.getJob(7, 12, "missing-job"));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveDownload(7, 12, "missing-job"));
    }

    @Test
    void cancellingAPendingJobReachesTerminalStateWithoutArtifact() throws Exception {
        // 直接注入一个 PENDING 任务，绕过异步执行以便确定性地验证取消语义。
        var createMethod = ProjectExportJobService.class.getDeclaredMethod("resolveJobFile", String.class);
        createMethod.setAccessible(true);
        Path zipPath = (Path) createMethod.invoke(service, "test-job-1");
        java.lang.reflect.Constructor<ProjectExportJobService.ExportJob> ctor =
                ProjectExportJobService.ExportJob.class.getDeclaredConstructor(
                        String.class, Integer.class, Integer.class, String.class,
                        boolean.class, Path.class);
        ctor.setAccessible(true);
        var jobField = ProjectExportJobService.class.getDeclaredField("jobs");
        jobField.setAccessible(true);
        @SuppressWarnings({"rawtypes", "unchecked"})
        java.util.Map map = (java.util.Map) jobField.get(service);
        map.put("test-job-1", ctor.newInstance("test-job-1", 7, 12, "demo-project", false, zipPath));

        var view = service.cancelJob(7, 12, "test-job-1");
        assertEquals("CANCELLED", view.status());

        assertThrows(IllegalArgumentException.class,
                () -> service.resolveDownload(7, 12, "test-job-1"));
    }
}
