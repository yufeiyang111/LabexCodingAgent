package com.labex.labexagent.workspace;

import com.labex.entity.StudentProject;
import java.nio.file.Path;

/**
 * Builds a validated workspace resolver from an owned project record.
 */
public final class ProjectWorkspace {
    private ProjectWorkspace() {
    }

    public static SecureWorkspacePath paths(StudentProject project) {
        if (project == null || project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
            throw new IllegalArgumentException("Project workspace is unavailable");
        }
        return new SecureWorkspacePath(Path.of(project.getWorkspacePath()));
    }

    /**
     * 解析项目工作区根；项目缺失或工作区不可用时返回 null。
     *
     * <p>用于"工作区可有可无"的调用点（如读取项目级 Skill 目录、判断 Skill 能力是否可用）：
     * 这些场景下拿不到工作区应当退化成"没有项目级内容"，而不是让整个请求失败。
     * 需要强校验的调用点应直接用 {@link #paths}。
     */
    public static Path workspaceRootOrNull(StudentProject project) {
        if (project == null) {
            return null;
        }
        try {
            return paths(project).workspaceRoot();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
