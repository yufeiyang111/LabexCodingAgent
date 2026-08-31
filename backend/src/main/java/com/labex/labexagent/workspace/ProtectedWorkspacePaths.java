package com.labex.labexagent.workspace;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 平台保护区判定：工作区内被运行时/快照机制占用的路径，用户侧文件操作
 * （复制/移动/上传/下载/搜索）一律不得写入或打包带出。
 * 保护区：`.labex/`（runtime.env + git 快照私有索引）、`.labex-agent/`，
 * 以及重命名流程的临时条目 `.labex-rename-*`。
 */
public final class ProtectedWorkspacePaths {

    private static final String RUNTIME_DIR = ".labex";
    private static final String AGENT_DIR = ".labex-agent";
    private static final String RENAME_TEMP_PREFIX = ".labex-rename-";

    private ProtectedWorkspacePaths() {
    }

    /** 工作区相对路径（`a/b/c` 形式）是否落在保护区内。根目录本身不受保护。 */
    public static boolean isProtectedRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = relativePath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || ".".equals(normalized)) {
            return false;
        }
        for (String segment : normalized.split("/")) {
            if (isProtectedSegment(segment)) {
                return true;
            }
        }
        return false;
    }

    /** 已解析的绝对条目是否落在 workspace 根的保护区内。 */
    public static boolean isProtectedEntry(Path workspaceRoot, Path entry) {
        if (workspaceRoot == null || entry == null) {
            return false;
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path normalized = entry.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            return false;
        }
        Path relative = root.relativize(normalized);
        for (Path segment : relative) {
            if (isProtectedSegment(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /** 源或目标任一端涉及保护区即拒绝；用于 copy/move 的双向校验。 */
    public static void rejectProtectedTargets(String sourceRelative, String targetRelative) {
        if (isProtectedRelativePath(sourceRelative)) {
            throw new IllegalArgumentException("源路径位于平台保留区，不允许此操作");
        }
        if (isProtectedRelativePath(targetRelative)) {
            throw new IllegalArgumentException("目标路径位于平台保留区，不允许此操作");
        }
    }

    private static boolean isProtectedSegment(String segment) {
        String lower = segment.toLowerCase(Locale.ROOT);
        return RUNTIME_DIR.equals(lower)
                || AGENT_DIR.equals(lower)
                || lower.startsWith(RENAME_TEMP_PREFIX);
    }
}
