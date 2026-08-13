package com.labex.labexagent.lsp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.labex.entity.StudentProject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LspPrewarmServiceTest {

    @TempDir
    Path workspace;

    private LspPrewarmService service(LspSessionManager lspSessionManager) {
        return new LspPrewarmService(lspSessionManager, null);
    }

    @Test
    void prewarmProjectDetectsLanguagesAndWarmsThem() throws Exception {
        Files.writeString(workspace.resolve("Example.java"), "class Example {}");
        Files.writeString(workspace.resolve("app.js"), "export default 1");
        Files.writeString(workspace.resolve("App.vue"), "<template></template>");
        Files.writeString(workspace.resolve("main.py"), "print(1)");
        StudentProject project = new StudentProject();
        project.setProjectId(1);
        project.setWorkspacePath(workspace.toString());

        LspSessionManager lspSessionManager = mock(LspSessionManager.class);
        service(lspSessionManager).prewarmProject(project);

        verify(lspSessionManager).prewarm(eq(workspace), eq("java"));
        verify(lspSessionManager).prewarm(eq(workspace), eq("typescript"));
        verify(lspSessionManager).prewarm(eq(workspace), eq("vue"));
        verify(lspSessionManager).prewarm(eq(workspace), eq("python"));
    }

    @Test
    void prewarmProjectSkipsMissingWorkspace() {
        StudentProject project = new StudentProject();
        project.setProjectId(2);
        project.setWorkspacePath(workspace.resolve("does-not-exist").toString());

        LspSessionManager lspSessionManager = mock(LspSessionManager.class);
        service(lspSessionManager).prewarmProject(project);

        verify(lspSessionManager, never()).prewarm(any(), any());
    }

    @Test
    void prewarmProjectSkipsNullProject() {
        LspSessionManager lspSessionManager = mock(LspSessionManager.class);
        service(lspSessionManager).prewarmProject(null);

        verify(lspSessionManager, never()).prewarm(any(), any());
    }
}
