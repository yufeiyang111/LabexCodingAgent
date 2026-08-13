package com.labex.labexagent.tool;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/*
 * Exception performing whole class analysis ignored.
 */
public final class ToolSupport {
    public static final long MAX_TEXT_MUTATION_FILE_BYTES = 1_000_000;
    private static final int BINARY_PROBE_BYTES = 8192;

    private ToolSupport() {
    }

    public static String stringArg(JsonObject args, String name, String defaultValue) {
        return args != null && args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsString() : defaultValue;
    }

    public static String stringArgMulti(JsonObject args, String defaultValue, String ... names) {
        if (args == null) {
            return defaultValue;
        }
        for (String name : names) {
            String v;
            if (!args.has(name) || args.get(name).isJsonNull() || (v = args.get(name).getAsString()) == null || v.isEmpty()) continue;
            return v;
        }
        return defaultValue;
    }

    public static int intArg(JsonObject args, String name, int defaultValue) {
        try {
            return args != null && args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsInt() : defaultValue;
        }
        catch (Exception e) {
            return defaultValue;
        }
    }

    public static Path resolve(AgentContext context, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        return workspacePaths(context).resolveExisting(normalizeRelativePath(relativePath));
    }

    public static Path resolveForCreate(AgentContext context, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        return workspacePaths(context).resolveForCreate(normalizeRelativePath(relativePath));
    }

    public static SecureWorkspacePath workspacePaths(AgentContext context) {
        if (context == null || context.getWorkspaceRoot() == null) {
            throw new IllegalArgumentException("workspace is required");
        }
        return new SecureWorkspacePath(context.getWorkspaceRoot());
    }

    /**
     * 为 workspace 内的 Shell 输出分配确定性的 artifact 路径，使用 task/toolCallId 隔离。
     * 完整 stdout/stderr 写入 artifact，普通结果只返回摘要。
     */
    public static ProcessOutputArtifact processOutputArtifact(
            AgentContext context, String toolName, String toolCallId) {
        SecureWorkspacePath paths = workspacePaths(context);
        String taskSegment = context.getTaskId() == null
                ? "session-" + artifactSegment(context.getSessionId(), "unknown")
                : "task-" + context.getTaskId();
        String toolSegment = artifactSegment(toolName, "tool");
        String callSegment = artifactSegment(toolCallId, "standalone");
        String relativePath = ".labex-agent/artifacts/" + taskSegment + "/"
                + toolSegment + "-" + callSegment + ".log";
        return new ProcessOutputArtifact(relativePath, paths.resolveForCreate(relativePath));
    }

    private static String artifactSegment(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        if (normalized.isBlank() || ".".equals(normalized) || "..".equals(normalized)) {
            return fallback;
        }
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }

    public record ProcessOutputArtifact(String relativePath, Path absolutePath) {
        public ProcessOutputArtifact {
            if (relativePath == null || relativePath.isBlank() || absolutePath == null) {
                throw new IllegalArgumentException("process output artifact path is required");
            }
            absolutePath = absolutePath.toAbsolutePath().normalize();
        }
    }

    public static boolean isSafeExistingWorkspaceEntry(AgentContext context, Path entry) {
        if (entry == null) {
            return false;
        }
        try {
            SecureWorkspacePath paths = workspacePaths(context);
            Path normalized = entry.toAbsolutePath().normalize();
            if (!normalized.startsWith(paths.workspaceRoot())) {
                return false;
            }
            String relativePath = paths.workspaceRoot().relativize(normalized).toString();
            paths.resolveExisting(relativePath.isBlank() ? "." : relativePath);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String normalizeRelativePath(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        String cleaned = relativePath.trim().replace('\\', '/');
        if ("/workspace".equals(cleaned) || "workspace".equals(cleaned)) {
            return ".";
        }
        if (cleaned.startsWith("/workspace/")) {
            cleaned = cleaned.substring("/workspace/".length());
        } else if (cleaned.startsWith("workspace/")) {
            cleaned = cleaned.substring("workspace/".length());
        }
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        return cleaned;
    }

    public static void requireEditableTextContent(String content) {
        long contentBytes = content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > MAX_TEXT_MUTATION_FILE_BYTES) {
            throw new IllegalArgumentException("File content exceeds max_file_bytes=" + MAX_TEXT_MUTATION_FILE_BYTES);
        }
    }

    public static String readEditableText(Path file) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("File target is not a regular file");
        }
        if (Files.size(file) > MAX_TEXT_MUTATION_FILE_BYTES) {
            throw new IllegalArgumentException("File target exceeds max_file_bytes=" + MAX_TEXT_MUTATION_FILE_BYTES);
        }
        if (isLikelyBinary(file)) {
            throw new IllegalArgumentException("File target appears to be binary");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public static boolean isLikelyBinary(Path file) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        byte[] bytes = new byte[BINARY_PROBE_BYTES];
        int length;
        try (InputStream input = Files.newInputStream(file)) {
            length = input.read(bytes);
        }
        for (int index = 0; index < Math.max(0, length); index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    public static String limit(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "\n...\u8f93\u51fa\u5df2\u622a\u65ad...";
    }
}
