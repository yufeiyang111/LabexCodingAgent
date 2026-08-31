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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectImagePreviewServiceTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @TempDir
    Path workspace;

    private StudentProjectService projectService;
    private WorkspaceFileOperationProperties properties;
    private ProjectImagePreviewService service;

    @BeforeEach
    void setUp() {
        projectService = mock(StudentProjectService.class);
        AuthRedisStore redisStore = mock(AuthRedisStore.class);
        when(redisStore.increment(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        properties = new WorkspaceFileOperationProperties();
        service = new ProjectImagePreviewService(projectService, properties,
                new WorkspaceOperationGuard(redisStore, properties));
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        when(projectService.getOwnedProject(7, 12)).thenReturn(project);
    }

    @Test
    void resolvesRealPngByMagicBytesEvenWithWrongExtensionCase() throws Exception {
        Files.write(workspace.resolve("logo.PNG"), PNG_MAGIC);

        var content = service.resolve(7, 12, "logo.PNG");

        assertEquals("image/png", content.contentType());
        assertEquals(PNG_MAGIC.length, content.sizeBytes());
    }

    @Test
    void rejectsSvgExplicitlyAsSecurityMeasure() throws Exception {
        Files.writeString(workspace.resolve("evil.svg"), "<svg xmlns='http://www.w3.org/2000/svg'></svg>");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.resolve(7, 12, "evil.svg"));

        assertTrue(error.getMessage().contains("SVG"));
    }

    @Test
    void rejectsDisguisedNonImageContentByMagicByteMismatch() throws Exception {
        Files.writeString(workspace.resolve("payload.png"), "<html><script>alert(1)</script></html>");

        assertThrows(IllegalArgumentException.class, () -> service.resolve(7, 12, "payload.png"));
    }

    @Test
    void rejectsUnsupportedExtensionsBeforeReadingContent() throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "just text");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.resolve(7, 12, "notes.txt"));

        assertTrue(error.getMessage().contains("PNG"));
    }

    @Test
    void enforcesConfiguredSizeCap() throws Exception {
        properties.setImageMaxBytes(4L);
        Files.write(workspace.resolve("big.png"), PNG_MAGIC);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.resolve(7, 12, "big.png"));

        assertTrue(error.getMessage().contains("上限"));
    }
}
