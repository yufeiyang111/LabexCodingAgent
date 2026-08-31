package com.labex.labexagent.workspace.files;

import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.WorkspaceFileOperationProperties;
import com.labex.labexagent.workspace.WorkspaceOperationGuard;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 工作区图片预览的唯一实现。
 * 安全契约：所有权校验 → SecureWorkspacePath 解析 → 扩展名白名单快筛（显式拒绝 SVG，
 * 防止存储型 XSS）→ 大小上限 → magic byte 嗅探定案 Content-Type（不信任扩展名）。
 * 响应头由 controller 附加 nosniff 与 CSP sandbox；前端必须以鉴权 blob 方式加载，禁止 token 进 URL。
 */
@Service
public class ProjectImagePreviewService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico");

    private final StudentProjectService studentProjectService;
    private final WorkspaceFileOperationProperties properties;
    private final WorkspaceOperationGuard guard;

    public ProjectImagePreviewService(StudentProjectService studentProjectService,
                                      WorkspaceFileOperationProperties properties,
                                      WorkspaceOperationGuard guard) {
        this.studentProjectService = studentProjectService;
        this.properties = properties;
        this.guard = guard;
    }

    /** contentType 已由 magic byte 确认，可直接作为响应头。 */
    public record ImageContent(Path file, String contentType, long sizeBytes) {
    }

    public ImageContent resolve(Integer studentId, Integer projectId, String path) {
        guard.checkRate(WorkspaceOperationGuard.FileOp.IMAGE, studentId);
        StudentProject project = requireOwnedProject(studentId, projectId);
        SecureWorkspacePath paths = ProjectWorkspace.paths(project);

        String normalized = ProjectFileTransferService.normalizeRelative(path);
        Path file = paths.resolveExisting(normalized);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("请选择一个图片文件");
        }
        String extension = extensionOf(normalized);
        if ("svg".equals(extension) || "svgz".equals(extension)) {
            throw new IllegalArgumentException("SVG 不支持在线预览（安全限制）");
        }
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅支持 PNG/JPEG/GIF/WEBP/BMP/ICO 图片预览");
        }
        try {
            long size = Files.size(file);
            if (size > properties.getImageMaxBytes()) {
                throw new IllegalArgumentException("图片超过预览大小上限 "
                        + (properties.getImageMaxBytes() / 1024 / 1024) + "MB");
            }
            String contentType = detectContentType(file);
            if (contentType == null) {
                throw new IllegalArgumentException("文件内容不是受支持的图片格式");
            }
            return new ImageContent(file, contentType, size);
        } catch (IOException e) {
            throw new RuntimeException("读取图片失败: " + e.getMessage(), e);
        }
    }

    /** magic byte 是唯一事实来源；扩展名只用于提前拒绝明显不支持的类型。 */
    private String detectContentType(Path file) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(file)) {
            header = input.readNBytes(16);
        }
        if (header.length < 4) {
            return null;
        }
        if ((header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
            return "image/png";
        }
        if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8') {
            return "image/gif";
        }
        if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header.length >= 12 && header[8] == 'W' && header[9] == 'E' && header[10] == 'B'
                && header[11] == 'P') {
            return "image/webp";
        }
        if (header[0] == 'B' && header[1] == 'M') {
            return "image/bmp";
        }
        if ((header[0] & 0xFF) == 0x00 && (header[1] & 0xFF) == 0x00
                && (header[2] & 0xFF) == 0x01 && (header[3] & 0xFF) == 0x00) {
            return "image/x-icon";
        }
        return null;
    }

    private String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private StudentProject requireOwnedProject(Integer studentId, Integer projectId) {
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        return project;
    }
}
