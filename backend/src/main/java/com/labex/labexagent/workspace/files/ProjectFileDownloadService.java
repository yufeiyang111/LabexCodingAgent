package com.labex.labexagent.workspace.files;

import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.ProtectedWorkspacePaths;
import com.labex.labexagent.workspace.WorkspaceFileOperationProperties;
import com.labex.labexagent.workspace.WorkspaceOperationGuard;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/**
 * 工作区单文件/文件夹下载的唯一实现。
 * 安全契约：所有权校验 → SecureWorkspacePath 解析 → 拒绝项目根（走既有整包导出）→
 * 保护区条目一律跳过 → 字节预算预检 + 流式硬上限双保险。
 * 单文件固定 application/octet-stream + attachment，杜绝内联渲染风险。
 */
@Service
public class ProjectFileDownloadService {

    private final StudentProjectService studentProjectService;
    private final WorkspaceFileOperationProperties properties;
    private final WorkspaceOperationGuard guard;

    public ProjectFileDownloadService(StudentProjectService studentProjectService,
                                      WorkspaceFileOperationProperties properties,
                                      WorkspaceOperationGuard guard) {
        this.studentProjectService = studentProjectService;
        this.properties = properties;
        this.guard = guard;
    }

    /** zipped=true 时 downloadName 是不带扩展名的文件夹名，调用方补 ".zip"。 */
    public record DownloadTarget(Path file, String downloadName, boolean zipped, SecureWorkspacePath paths) {
    }

    /**
     * 校验并产出下载描述；调用方据此设置响应头后调用 {@link #write}。
     * 预检在写任何响应字节之前完成，超限时能干净地返回错误而不是半截文件。
     */
    public DownloadTarget prepare(Integer studentId, Integer projectId, String path) {
        try (WorkspaceOperationGuard.Slot slot = guard.acquireSlot(WorkspaceOperationGuard.FileOp.DOWNLOAD)) {
            guard.checkRate(WorkspaceOperationGuard.FileOp.DOWNLOAD, studentId);
            StudentProject project = requireOwnedProject(studentId, projectId);
            SecureWorkspacePath paths = ProjectWorkspace.paths(project);
            Path root = paths.workspaceRoot();

            String normalized = ProjectFileTransferService.normalizeRelative(path);
            ProtectedWorkspacePaths.rejectProtectedTargets(normalized, null);
            Path target = paths.resolveExisting(normalized);
            if (target.equals(root)) {
                throw new IllegalArgumentException("请使用项目导出功能下载整个项目");
            }
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                enforceBudget(paths, target);
                return new DownloadTarget(target, String.valueOf(target.getFileName()), true, paths);
            }
            long size = sizeOf(target);
            if (size > properties.getDownloadMaxBytes()) {
                throw new IllegalArgumentException("文件超过下载大小上限 "
                        + (properties.getDownloadMaxBytes() / 1024 / 1024) + "MB");
            }
            return new DownloadTarget(target, String.valueOf(target.getFileName()), false, paths);
        }
    }

    /** 向输出流写入内容；zip 模式以文件夹自身为根目录。 */
    public void write(DownloadTarget target, OutputStream output) {
        try (output) {
            if (!target.zipped()) {
                copyLimited(target.file(), output);
                return;
            }
            writeZip(target, output);
        } catch (IOException e) {
            throw new RuntimeException("下载失败: " + e.getMessage(), e);
        }
    }

    /** Content-Disposition 头值；ASCII 兜底 + RFC 5987 编码，与项目导出保持一致。 */
    public String contentDispositionValue(String fileName) {
        boolean ascii = fileName.chars().allMatch(c -> c < 128);
        String asciiName = ascii ? fileName : "download";
        String encoded = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encoded;
    }

    private void enforceBudget(SecureWorkspacePath paths, Path folder) {
        long[] bytes = {0L};
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink() || attrs.isOther()
                            || ProtectedWorkspacePaths.isProtectedEntry(paths.workspaceRoot(), directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink() || attrs.isOther()
                            || ProtectedWorkspacePaths.isProtectedEntry(paths.workspaceRoot(), file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    bytes[0] += attrs.size();
                    if (bytes[0] > properties.getDownloadMaxBytes()) {
                        throw new IllegalArgumentException("下载内容超过大小上限 "
                                + (properties.getDownloadMaxBytes() / 1024 / 1024) + "MB");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("读取文件夹失败: " + e.getMessage(), e);
        }
    }

    private void writeZip(DownloadTarget target, OutputStream output) throws IOException {
        Path folder = target.file();
        SecureWorkspacePath paths = target.paths();
        Path parent = folder.getParent() == null ? folder : folder.getParent();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                        throws IOException {
                    if (attrs.isSymbolicLink() || attrs.isOther()
                            || ProtectedWorkspacePaths.isProtectedEntry(paths.workspaceRoot(), directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    zip.putNextEntry(new ZipEntry(toEntryName(parent, directory) + "/"));
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || attrs.isOther()
                            || ProtectedWorkspacePaths.isProtectedEntry(paths.workspaceRoot(), file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    zip.putNextEntry(new ZipEntry(toEntryName(parent, file)));
                    copyLimited(file, zip);
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private void copyLimited(Path file, OutputStream output) throws IOException {
        long remaining = properties.getDownloadMaxBytes();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                remaining -= read;
                if (remaining < 0) {
                    throw new IOException("下载内容超过大小预算");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private static String toEntryName(Path root, Path entry) {
        return root.relativize(entry).toString().replace('\\', '/');
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    private StudentProject requireOwnedProject(Integer studentId, Integer projectId) {
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        return project;
    }
}
