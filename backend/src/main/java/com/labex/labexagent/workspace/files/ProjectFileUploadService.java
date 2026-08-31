package com.labex.labexagent.workspace.files;

import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.ProtectedWorkspacePaths;
import com.labex.labexagent.workspace.WorkspaceFileOperationProperties;
import com.labex.labexagent.workspace.WorkspaceItemNameValidator;
import com.labex.labexagent.workspace.WorkspaceOperationGuard;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文件到指定文件夹的唯一实现。
 * 安全契约：所有权校验 → 目标目录校验（必须已存在且非保护区）→ 文件名/相对路径逐段校验 →
 * 单文件与整批大小上限 → 冲突协议（与复制一致：无 decision 返回冲突清单）→ 流式写盘。
 * 大小校验在读取字节流之前依据 part.getSize() 判定，避免把超大文件读进内存。
 */
@Service
public class ProjectFileUploadService {

    private final StudentProjectService studentProjectService;
    private final WorkspaceFileOperationProperties properties;
    private final WorkspaceOperationGuard guard;

    public ProjectFileUploadService(StudentProjectService studentProjectService,
                                    WorkspaceFileOperationProperties properties,
                                    WorkspaceOperationGuard guard) {
        this.studentProjectService = studentProjectService;
        this.properties = properties;
        this.guard = guard;
    }

    public record UploadConflict(String path, String type) {
    }

    /** status: done | skipped | conflict */
    public record UploadOutcome(String status, List<UploadConflict> conflicts,
                                int savedCount, int skippedCount) {
    }

    public record UploadRequest(Integer studentId, Integer projectId, String targetDir,
                                List<MultipartFile> files, List<String> relativePaths,
                                Map<String, String> decisions) {
    }

    public UploadOutcome upload(UploadRequest request) {
        guard.checkRate(WorkspaceOperationGuard.FileOp.UPLOAD, request.studentId());
        StudentProject project = requireOwnedProject(request.studentId(), request.projectId());
        SecureWorkspacePath paths = ProjectWorkspace.paths(project);

        List<MultipartFile> files = request.files() == null ? List.of() : request.files();
        if (files.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        if (files.size() > properties.getUploadMaxFilesPerRequest()) {
            throw new IllegalArgumentException("单次上传文件数超过上限 " + properties.getUploadMaxFilesPerRequest());
        }
        long totalBytes = 0L;
        for (MultipartFile file : files) {
            totalBytes += file.getSize();
        }
        if (totalBytes > properties.getUploadMaxBytesPerRequest()) {
            throw new IllegalArgumentException("单次上传总大小超过上限 "
                    + (properties.getUploadMaxBytesPerRequest() / 1024 / 1024) + "MB");
        }

        String targetRelative = ProjectFileTransferService.normalizeNullable(request.targetDir());
        if (!targetRelative.isBlank()) {
            Path targetDir = paths.resolveExisting(targetRelative);
            if (!Files.isDirectory(targetDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("目标位置必须是已存在的文件夹");
            }
        }
        ProtectedWorkspacePaths.rejectProtectedTargets(null, targetRelative);

        List<PlannedEntry> entries = planEntries(files, request.relativePaths(), targetRelative);
        List<UploadConflict> conflicts = detectConflicts(paths, entries, request.decisions());
        if (!conflicts.isEmpty()) {
            return new UploadOutcome("conflict", conflicts, 0, 0);
        }

        int saved = 0;
        int skipped = 0;
        for (PlannedEntry entry : entries) {
            String action = request.decisions() == null ? null : request.decisions().get(entry.relativePath());
            if (action != null && ProjectFileTransferService.ACTION_SKIP.equalsIgnoreCase(action)) {
                skipped++;
                continue;
            }
            writeToDisk(paths, entry, isOverwrite(action));
            saved++;
        }
        studentProjectService.refreshProjectMetadataAsync(request.studentId(), request.projectId(), "file_upload");
        boolean anySkipped = skipped > 0;
        return new UploadOutcome(anySkipped ? "skipped" : "done", List.of(), saved, skipped);
    }

    private List<PlannedEntry> planEntries(List<MultipartFile> files, List<String> relativePaths,
                                           String targetRelative) {
        List<PlannedEntry> entries = new ArrayList<>(files.size());
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            String declaredName = (relativePaths != null && index < relativePaths.size()
                    && relativePaths.get(index) != null && !relativePaths.get(index).isBlank())
                    ? relativePaths.get(index)
                    : file.getOriginalFilename();
            if (declaredName == null || declaredName.isBlank()) {
                throw new IllegalArgumentException("第 " + (index + 1) + " 个文件缺少文件名");
            }
            String safeRelative = WorkspaceItemNameValidator.validateRelativeSegments(declaredName);
            if (ProtectedWorkspacePaths.isProtectedRelativePath(safeRelative)) {
                throw new IllegalArgumentException("上传路径包含平台保留目录名，已拒绝: " + declaredName);
            }
            String entryRelative = targetRelative.isBlank()
                    ? safeRelative
                    : targetRelative + "/" + safeRelative;
            if (!seen.add(entryRelative)) {
                throw new IllegalArgumentException("上传列表中存在重复路径: " + entryRelative);
            }
            if (file.getSize() > properties.getUploadMaxBytesPerFile()) {
                throw new IllegalArgumentException("文件 \"" + declaredName + "\" 超过单文件大小上限 "
                        + (properties.getUploadMaxBytesPerFile() / 1024 / 1024) + "MB");
            }
            entries.add(new PlannedEntry(file, entryRelative));
        }
        return entries;
    }

    /** 返回需要用户裁决的冲突清单；decisions 已覆盖全部冲突时返回空。 */
    private List<UploadConflict> detectConflicts(SecureWorkspacePath paths, List<PlannedEntry> entries,
                                                 Map<String, String> decisions) {
        LinkedHashMap<String, UploadConflict> conflicts = new LinkedHashMap<>();
        for (PlannedEntry entry : entries) {
            Path target;
            try {
                target = paths.resolveForCreate(entry.relativePath());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("目标路径不可用: " + entry.relativePath());
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String action = decisions == null ? null : decisions.get(entry.relativePath());
            if (action == null) {
                String type = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? "directory" : "file";
                if (type.equals("directory")) {
                    // 目录与文件同名无法覆盖，直接拒绝并说明原因。
                    throw new IllegalArgumentException("目标位置已存在同名文件夹，无法上传: " + entry.relativePath());
                }
                conflicts.putIfAbsent(entry.relativePath(), new UploadConflict(entry.relativePath(), type));
            }
        }
        return new ArrayList<>(conflicts.values());
    }

    private void writeToDisk(SecureWorkspacePath paths, PlannedEntry entry, boolean overwrite) {
        Path target = paths.resolveForCreate(entry.relativePath());
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (InputStream input = entry.file().getInputStream()) {
                if (overwrite) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.copy(input, target);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + entry.relativePath(), e);
        }
    }

    private boolean isOverwrite(String action) {
        return action != null && ProjectFileTransferService.ACTION_OVERWRITE.equalsIgnoreCase(action);
    }

    private StudentProject requireOwnedProject(Integer studentId, Integer projectId) {
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        return project;
    }

    private record PlannedEntry(MultipartFile file, String relativePath) {
    }
}
