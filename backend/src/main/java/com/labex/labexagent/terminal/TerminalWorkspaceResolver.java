package com.labex.labexagent.terminal;

import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class TerminalWorkspaceResolver {
    private final StudentProjectService projectService;

    public TerminalWorkspaceResolver(StudentProjectService projectService) {
        this.projectService = projectService;
    }

    public TerminalWorkspace resolveWorkspace(Integer studentId, Integer projectId) {
        if (studentId == null || projectId == null || studentId <= 0 || projectId <= 0) {
            throw new IllegalArgumentException("Project not found or access denied");
        }
        StudentProject project = projectService.getOwnedProject(studentId, projectId);
        if (project == null || project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
            throw new IllegalArgumentException("Project not found or access denied");
        }
        Path workspace;
        try {
            workspace = new SecureWorkspacePath(Path.of(project.getWorkspacePath())).workspaceRoot();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Project workspace is unavailable");
        }
        return new TerminalWorkspace(studentId, projectId, workspace);
    }

    public Path resolveWorkingDirectory(TerminalWorkspace workspace, String cwd) {
        if (workspace == null) {
            throw new IllegalArgumentException("Project workspace is required");
        }
        SecureWorkspacePath paths = new SecureWorkspacePath(workspace.workspaceRoot());
        if (cwd == null || cwd.isBlank() || ".".equals(cwd.trim())) {
            return paths.workspaceRoot();
        }

        String normalized = cwd.trim().replace('\\', '/');
        try {
            Path requested = Path.of(normalized);
            if (requested.isAbsolute() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
                throw new IllegalArgumentException("cwd must be workspace-relative");
            }
            Path resolved = paths.resolveExisting(normalized);
            if (!Files.isDirectory(resolved, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("cwd is not a project directory");
            }
            return resolved;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("cwd is invalid", e);
        } catch (IllegalArgumentException e) {
            if ("cwd must be workspace-relative".equals(e.getMessage())
                    || "cwd is not a project directory".equals(e.getMessage())) {
                throw e;
            }
            throw new IllegalArgumentException("cwd escapes project workspace", e);
        }
    }

    public record TerminalWorkspace(Integer studentId, Integer projectId, Path workspaceRoot) {
    }
}
