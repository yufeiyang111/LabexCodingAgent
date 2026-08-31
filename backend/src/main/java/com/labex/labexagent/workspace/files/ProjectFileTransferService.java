package com.labex.labexagent.workspace.files;

import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.ProtectedWorkspacePaths;
import com.labex.labexagent.workspace.WorkspaceFileOperationProperties;
import com.labex.labexagent.workspace.WorkspaceOperationGuard;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;

/**
 * 用户侧文件/文件夹复制与移动的唯一实现。
 * 安全契约：所有权校验 → SecureWorkspacePath 解析 → 保护区双向拒绝 →
 * 自包含/同名冲突处理 → 复制预算预检（字节/数量/深度）→ 执行 → 异步元数据刷新。
 * 冲突协议：目标已存在且无对应 decision 时返回冲突清单不执行；
 * decision 为 skip（保留现状）或 overwrite（类型一致才允许替换）。
 */
@Service
public class ProjectFileTransferService {

    public static final String ACTION_SKIP = "skip";
    public static final String ACTION_OVERWRITE = "overwrite";

    private final StudentProjectService studentProjectService;
    private final WorkspaceFileOperationProperties properties;
    private final WorkspaceOperationGuard guard;

    public ProjectFileTransferService(StudentProjectService studentProjectService,
                                      WorkspaceFileOperationProperties properties,
                                      WorkspaceOperationGuard guard) {
        this.studentProjectService = studentProjectService;
        this.properties = properties;
        this.guard = guard;
    }

    public record Conflict(String path, String type) {
    }

    /** status: done | skipped | conflict */
    public record TransferOutcome(String status, List<Conflict> conflicts, String targetPath) {
    }

    public record TransferRequest(Integer studentId, Integer projectId, String sourcePath,
                                  String targetParentPath, Map<String, String> decisions) {
    }

    public TransferOutcome copy(TransferRequest request) {
        try (WorkspaceOperationGuard.Slot slot = guard.acquireSlot(WorkspaceOperationGuard.FileOp.TRANSFER)) {
            guard.checkRate(WorkspaceOperationGuard.FileOp.TRANSFER, request.studentId());
            return transfer(request, true);
        }
    }

    public TransferOutcome move(TransferRequest request) {
        try (WorkspaceOperationGuard.Slot slot = guard.acquireSlot(WorkspaceOperationGuard.FileOp.TRANSFER)) {
            guard.checkRate(WorkspaceOperationGuard.FileOp.TRANSFER, request.studentId());
            return transfer(request, false);
        }
    }

    private TransferOutcome transfer(TransferRequest request, boolean copyMode) {
        StudentProject project = requireOwnedProject(request.studentId(), request.projectId());
        SecureWorkspacePath paths = ProjectWorkspace.paths(project);

        String sourceRelative = normalizeRelative(request.sourcePath());
        ProtectedWorkspacePaths.rejectProtectedTargets(sourceRelative, null);
        Path source = paths.resolveExisting(sourceRelative);
        Path root = paths.workspaceRoot();
        if (source.equals(root)) {
            throw new IllegalArgumentException("不能对项目根目录执行此操作");
        }

        String parentRelative = normalizeNullable(request.targetParentPath());
        Path parent = resolveDirectory(paths, parentRelative);
        Path target = parent.resolve(source.getFileName()).normalize();
        String targetRelative = toRelative(root, target);
        ProtectedWorkspacePaths.rejectProtectedTargets(null, targetRelative);
        verifyInsideWorkspace(paths, target);

        boolean sourceIsDirectory = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        if (target.equals(source)) {
            if (copyMode) {
                throw new IllegalArgumentException("目标目录下已存在同名条目");
            }
            return new TransferOutcome("done", List.of(), targetRelative);
        }
        if (target.startsWith(source)) {
            throw new IllegalArgumentException(copyMode ? "不能将文件夹复制到其自身内部" : "不能将文件夹移动到其自身内部");
        }

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            String action = request.decisions() == null ? null
                    : request.decisions().get(targetRelative);
            if (action == null) {
                String type = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? "directory" : "file";
                return new TransferOutcome("conflict", List.of(new Conflict(targetRelative, type)), null);
            }
            if (ACTION_SKIP.equalsIgnoreCase(action)) {
                return new TransferOutcome("skipped", List.of(), targetRelative);
            }
            if (!ACTION_OVERWRITE.equalsIgnoreCase(action)) {
                throw new IllegalArgumentException("未知的冲突处理方式: " + action);
            }
            boolean targetIsDirectory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
            if (targetIsDirectory != sourceIsDirectory) {
                throw new IllegalArgumentException("同名条目类型不同，无法覆盖，请选择跳过或取消");
            }
            deleteExisting(target);
        }

        if (copyMode) {
            enforceCopyBudget(paths, source);
            copyRecursively(source, target);
        } else {
            moveAtomically(source, target);
        }
        studentProjectService.refreshProjectMetadataAsync(request.studentId(), request.projectId(),
                copyMode ? "file_copy" : "file_move");
        return new TransferOutcome("done", List.of(), targetRelative);
    }

    private Path resolveDirectory(SecureWorkspacePath paths, String parentRelative) {
        Path parent;
        if (parentRelative == null || parentRelative.isBlank()) {
            parent = paths.workspaceRoot();
        } else {
            parent = paths.resolveExisting(parentRelative);
        }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标位置必须是文件夹");
        }
        return parent;
    }

    private void deleteExisting(Path target) {
        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                FileUtils.deleteDirectory(target.toFile());
            } else {
                Files.delete(target);
            }
        } catch (IOException e) {
            throw new RuntimeException("清理已有条目失败: " + e.getMessage(), e);
        }
    }

    /** 复制前的预算预检：超限直接失败，避免复制到一半留下半成品。 */
    private void enforceCopyBudget(SecureWorkspacePath paths, Path source) {
        long[] bytes = {0L};
        long[] files = {0L};
        Path sourceRoot = source.toAbsolutePath().normalize();
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink() || attrs.isOther()
                            || ProtectedWorkspacePaths.isProtectedEntry(paths.workspaceRoot(), directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (directory.equals(sourceRoot)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = toRelative(sourceRoot, directory);
                    if (relative.split("/").length > properties.getTransferMaxDepth()) {
                        throw new IllegalArgumentException("文件夹嵌套层级超过上限 " + properties.getTransferMaxDepth());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink() || attrs.isOther()
                            || ProtectedWorkspacePaths.isProtectedEntry(paths.workspaceRoot(), file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    files[0]++;
                    bytes[0] += attrs.size();
                    if (files[0] > properties.getTransferMaxFiles()) {
                        throw new IllegalArgumentException("文件数量超过单次复制上限 " + properties.getTransferMaxFiles());
                    }
                    if (bytes[0] > properties.getTransferMaxTotalBytes()) {
                        throw new IllegalArgumentException("总大小超过单次复制上限 "
                                + (properties.getTransferMaxTotalBytes() / 1024 / 1024) + "MB");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("读取源文件夹失败: " + e.getMessage(), e);
        }
    }

    private void copyRecursively(Path source, Path target) {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                        throws IOException {
                    if (attrs.isSymbolicLink() || attrs.isOther()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path destination = target.resolve(source.relativize(directory).toString()).normalize();
                    Files.createDirectories(destination);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || attrs.isOther()) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path destination = target.resolve(source.relativize(file).toString()).normalize();
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("复制失败: " + e.getMessage(), e);
        }
    }

    private void moveAtomically(Path source, Path target) {
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(source, target);
            }
        } catch (IOException e) {
            throw new RuntimeException("移动失败: " + e.getMessage(), e);
        }
    }

    private void verifyInsideWorkspace(SecureWorkspacePath paths, Path target) {
        Path relative = paths.workspaceRoot().relativize(target.toAbsolutePath().normalize());
        paths.resolveForCreate(relative.toString().replace('\\', '/'));
    }

    static String normalizeRelative(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        return normalized;
    }

    static String normalizeNullable(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    static String toRelative(Path root, Path entry) {
        return root.relativize(entry.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private StudentProject requireOwnedProject(Integer studentId, Integer projectId) {
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        return project;
    }
}
