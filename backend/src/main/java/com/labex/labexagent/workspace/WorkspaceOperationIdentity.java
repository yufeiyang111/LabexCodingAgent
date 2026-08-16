package com.labex.labexagent.workspace;

import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 一次 workspace 操作的稳定身份。
 *
 * <p>它用于把 task、epoch、受控相对路径与变更证据绑定到同一个 durable 事实，
 * 普通事件只投影根目录指纹，绝不暴露宿主绝对路径。</p>
 */
public record WorkspaceOperationIdentity(
        int schemaVersion,
        Integer studentId,
        Integer projectId,
        String conversationId,
        Long taskId,
        long executionEpoch,
        String workspaceFingerprint,
        String workingDirectory,
        List<String> relativePaths,
        String operationFingerprint) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorkspaceOperationIdentity {
        studentId = studentId == null ? 0 : studentId;
        projectId = projectId == null ? 0 : projectId;
        conversationId = conversationId == null ? "" : conversationId.trim();
        taskId = taskId == null ? 0L : taskId;
        executionEpoch = Math.max(0L, executionEpoch);
        workspaceFingerprint = workspaceFingerprint == null ? "" : workspaceFingerprint;
        workingDirectory = normalizeRelativePath(workingDirectory);
        if (workingDirectory.isBlank()) {
            workingDirectory = ".";
        }
        relativePaths = normalizeRelativePaths(relativePaths);
        operationFingerprint = operationFingerprint == null || operationFingerprint.isBlank()
                ? fingerprint(schemaVersion, studentId, projectId, conversationId, taskId, executionEpoch,
                workspaceFingerprint, workingDirectory, relativePaths)
                : operationFingerprint;
    }

    /**
     * 从已经解析过的 AgentContext 和 workdir 构建身份；workdir 必须位于 context workspace 内。
     */
    public static WorkspaceOperationIdentity forContext(AgentContext context, Path resolvedWorkdir,
                                                         List<String> relativePaths) {
        if (context == null || context.getWorkspaceRoot() == null) {
            throw new IllegalArgumentException("workspace context is required");
        }
        Path root = context.getWorkspaceRoot().toAbsolutePath().normalize();
        Path workdir = (resolvedWorkdir == null ? root : resolvedWorkdir).toAbsolutePath().normalize();
        if (!workdir.startsWith(root)) {
            throw new IllegalArgumentException("resolved workdir escapes workspace");
        }
        String relativeWorkdir = root.relativize(workdir).toString().replace('\\', '/');
        return new WorkspaceOperationIdentity(
                CURRENT_SCHEMA_VERSION,
                context.getStudentId(),
                context.getProject() == null ? null : context.getProject().getProjectId(),
                context.getConversationId(),
                context.getTaskId(),
                context.getExecutionEpoch(),
                sha256(root.toString()),
                relativeWorkdir.isBlank() ? "." : relativeWorkdir,
                relativePaths,
                "");
    }

    /**
     * 从已批准命令和其 durable task 构建身份。审批记录中的 workdir 仍会再次经过
     * SecureWorkspacePath 解析，避免把历史脏数据投影成可信 target。
     */
    public static WorkspaceOperationIdentity forCommandApproval(StudentProject project, CommandApproval approval,
                                                                AgentTask task, List<String> relativePaths) {
        if (project == null || approval == null) {
            throw new IllegalArgumentException("project and command approval are required");
        }
        if (task != null && approval.getTaskId() != null && task.getTaskId() != null
                && !approval.getTaskId().equals(task.getTaskId())) {
            throw new IllegalArgumentException("command approval task does not match workspace task");
        }
        SecureWorkspacePath paths = ProjectWorkspace.paths(project);
        Path root = paths.workspaceRoot();
        String requestedWorkdir = approval.getWorkingDirectory();
        Path workdir = paths.resolveExisting(requestedWorkdir == null || requestedWorkdir.isBlank()
                ? "." : requestedWorkdir);
        String relativeWorkdir = root.relativize(workdir).toString().replace('\\', '/');
        Integer studentId = task != null && task.getStudentId() != null ? task.getStudentId() : approval.getStudentId();
        Integer projectId = task != null && task.getProjectId() != null ? task.getProjectId() : approval.getProjectId();
        String conversationId = task != null && task.getConversationId() != null
                ? task.getConversationId() : approval.getConversationId();
        Long taskId = task != null && task.getTaskId() != null ? task.getTaskId() : approval.getTaskId();
        long epoch = task == null || task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch();
        return new WorkspaceOperationIdentity(CURRENT_SCHEMA_VERSION, studentId, projectId, conversationId, taskId,
                epoch, sha256(root.toAbsolutePath().normalize().toString()),
                relativeWorkdir.isBlank() ? "." : relativeWorkdir, relativePaths, "");
    }

    /** 返回可安全写入 Event、Part 或 SSE 的公开投影。 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("studentId", studentId);
        payload.put("projectId", projectId);
        payload.put("conversationId", conversationId);
        payload.put("taskId", taskId);
        payload.put("executionEpoch", executionEpoch);
        payload.put("workspaceFingerprint", workspaceFingerprint);
        payload.put("workingDirectory", workingDirectory);
        payload.put("relativePaths", relativePaths);
        payload.put("operationFingerprint", operationFingerprint);
        return Collections.unmodifiableMap(payload);
    }

    private static List<String> normalizeRelativePaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String path : paths) {
            String normalized = normalizeRelativePath(path);
            if (!normalized.isBlank() && !".".equals(normalized)) {
                result.add(normalized);
            }
        }
        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        return List.copyOf(sorted);
    }

    private static String normalizeRelativePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String candidate = value.trim().replace('\\', '/');
        while (candidate.startsWith("./")) {
            candidate = candidate.substring(2);
        }
        try {
            Path path = Path.of(candidate).normalize();
            if (path.isAbsolute() || candidate.startsWith("/") || candidate.matches("^[A-Za-z]:.*")) {
                throw new IllegalArgumentException("workspace identity path must be relative");
            }
            String normalized = path.toString().replace('\\', '/');
            if (normalized.equals("..") || normalized.startsWith("../")) {
                throw new IllegalArgumentException("workspace identity path escapes workspace");
            }
            return normalized;
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("workspace identity path is invalid", invalid);
        }
    }

    private static String fingerprint(int schemaVersion, Integer studentId, Integer projectId, String conversationId,
                                      Long taskId, long epoch, String workspaceFingerprint, String workingDirectory,
                                      List<String> paths) {
        String canonical = schemaVersion + "\n" + studentId + "\n" + projectId + "\n" + conversationId
                + "\n" + taskId + "\n" + epoch + "\n" + workspaceFingerprint + "\n"
                + workingDirectory + "\n" + String.join("\n", paths);
        return sha256(canonical);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte valueByte : bytes) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", valueByte));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
