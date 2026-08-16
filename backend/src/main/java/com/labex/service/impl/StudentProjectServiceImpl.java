package com.labex.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.labex.entity.StudentProject;
import com.labex.mapper.StudentProjectMapper;
import com.labex.rag.config.RagConfig;
import com.labex.rag.llm.LLMChat;
import com.labex.rag.llm.MiniMaxChat;
import com.labex.rag.llm.OllamaChat;
import com.labex.labexagent.commandsecurity.AgentProjectMetadataRefreshScheduler;
import com.labex.labexagent.service.ProjectScanPolicy;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.service.StudentProjectService;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StudentProjectServiceImpl
extends ServiceImpl<StudentProjectMapper, StudentProject>
implements StudentProjectService {
    private static final Logger log = LoggerFactory.getLogger(StudentProjectServiceImpl.class);
    private static final int MAX_FILES = 5000;
    private static final long MAX_TOTAL_SIZE = 314572800L;
    private static final long MAX_EDIT_FILE_SIZE = 0x200000L;
    private static final int MAX_FILE_PREVIEW_BYTES = 512 * 1024;
    private static final Gson GSON = new Gson();
    @Value(value="${labex-agent.project-base-path:./student_projects}")
    private String projectBasePath;
    @Lazy
    @Autowired
    private MiniMaxChat miniMaxChat;
    @Lazy
    @Autowired
    private OllamaChat ollamaChat;
    @Autowired
    private RagConfig ragConfig;
    @Lazy
    @Autowired(required = false)
    private AgentProjectMetadataRefreshScheduler metadataRefreshScheduler;

    public StudentProject uploadProject(Integer studentId, MultipartFile file, String projectName) {
        String originalName;
        if (studentId == null) {
            throw new IllegalArgumentException("Invalid student id");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please upload a project archive");
        }
        String string = originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "project.zip";
        if (!originalName.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("Only .zip project archives are supported currently");
        }
        String safeProjectName = this.sanitizeName(projectName != null && !projectName.isBlank() ? projectName : this.stripExtension(originalName));
        String projectToken = UUID.randomUUID().toString().replace("-", "");
        Path studentRoot = Path.of(this.projectBasePath, String.valueOf(studentId)).toAbsolutePath().normalize();
        Path projectRoot = studentRoot.resolve(projectToken).normalize();
        Path archivePath = projectRoot.resolve("archive").resolve(this.sanitizeName(originalName));
        Path workspacePath = projectRoot.resolve("workspace");
        try {
            Files.createDirectories(archivePath.getParent(), new FileAttribute[0]);
            Files.createDirectories(workspacePath, new FileAttribute[0]);
            file.transferTo(archivePath);
            ExtractStats stats = this.unzipSecurely(archivePath, workspacePath);
            this.initializeAgentIgnoreFile(workspacePath);
            StudentProject project = new StudentProject();
            project.setStudentId(studentId);
            project.setProjectName(safeProjectName);
            project.setOriginalFileName(originalName);
            project.setArchivePath(archivePath.toString());
            project.setWorkspacePath(workspacePath.toString());
            project.setStructureJson(null);
            project.setFileCount(Integer.valueOf(stats.fileCount));
            project.setTotalSize(Long.valueOf(stats.totalSize));
            project.setStatus(Integer.valueOf(1));
            project.setCreateTime(LocalDateTime.now());
            project.setUpdateTime(LocalDateTime.now());
            this.save(project);
            this.scheduleMetadataRefresh(project);
            return project;
        }
        catch (Exception e) {
            this.cleanup(projectRoot);
            throw new RuntimeException("Project upload failed: " + e.getMessage(), e);
        }
    }

    public StudentProject createEmptyProject(Integer studentId, String projectName) {
        return this.createProject(studentId, projectName, "blank");
    }

    public StudentProject createProject(Integer studentId, String projectName, String template) {
        if (studentId == null) {
            throw new IllegalArgumentException("Invalid student id");
        }
        String safeProjectName = this.sanitizeName(projectName != null && !projectName.isBlank() ? projectName : "new-project");
        String safeTemplate = template == null || template.isBlank() ? "blank" : template.trim().toLowerCase();
        String projectToken = UUID.randomUUID().toString().replace("-", "");
        Path studentRoot = Path.of(this.projectBasePath, String.valueOf(studentId)).toAbsolutePath().normalize();
        Path projectRoot = studentRoot.resolve(projectToken).normalize();
        Path workspacePath = projectRoot.resolve("workspace");
        try {
            Files.createDirectories(workspacePath, new FileAttribute[0]);
            this.writeStarterTemplate(workspacePath, safeProjectName, safeTemplate);
            this.initializeAgentIgnoreFile(workspacePath);
            StudentProject project = new StudentProject();
            project.setStudentId(studentId);
            project.setProjectName(safeProjectName);
            project.setOriginalFileName("created:" + safeTemplate);
            project.setArchivePath("");
            project.setWorkspacePath(workspacePath.toString());
            project.setStructureJson(null);
            project.setFileCount(Integer.valueOf(0));
            project.setTotalSize(Long.valueOf(0L));
            project.setStatus(Integer.valueOf(1));
            project.setCreateTime(LocalDateTime.now());
            project.setUpdateTime(LocalDateTime.now());
            this.save(project);
            this.scheduleMetadataRefresh(project);
            return project;
        }
        catch (Exception e) {
            this.cleanup(projectRoot);
            throw new RuntimeException("Empty project creation failed: " + e.getMessage(), e);
        }
    }

    public StudentProject getOwnedProject(Integer studentId, Integer projectId) {
        return this.lambdaQuery().eq(StudentProject::getProjectId, projectId).eq(StudentProject::getStudentId, studentId).one();
    }

    public String readProjectFile(Integer studentId, Integer projectId, String relativePath) {
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        Path filePath = this.resolveProjectFile(project, relativePath);
        try {
            byte[] bytes;
            if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Please select a file");
            }
            long size = Files.size(filePath);
            if (size > ProjectScanPolicy.MAX_AGENT_READ_FILE_BYTES) {
                throw new IllegalArgumentException("File exceeds max_agent_read_file_bytes="
                        + ProjectScanPolicy.MAX_AGENT_READ_FILE_BYTES);
            }
            for (byte b : bytes = Files.readAllBytes(filePath)) {
                if (b != 0) continue;
                throw new IllegalArgumentException(this.buildUnsupportedFileMessage(relativePath));
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to read project file: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> readProjectFileForEditor(Integer studentId, Integer projectId, String relativePath) {
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        Path filePath = this.resolveProjectFile(project, relativePath);
        try {
            if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Please select a file");
            }
            long size = Files.size(filePath);
            int previewLength = (int) Math.min(size, MAX_FILE_PREVIEW_BYTES);
            byte[] bytes = this.readFilePrefix(filePath, previewLength);
            for (byte b : bytes) {
                if (b == 0) {
                    throw new IllegalArgumentException(this.buildUnsupportedFileMessage(relativePath));
                }
            }
            boolean truncated = size > bytes.length;
            Map<String, Object> result = new HashMap<>();
            result.put("path", relativePath);
            result.put("content", new String(bytes, StandardCharsets.UTF_8));
            result.put("readOnly", truncated);
            result.put("truncated", truncated);
            result.put("totalSize", size);
            return result;
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to read project file: " + e.getMessage(), e);
        }
    }

    private byte[] readFilePrefix(Path filePath, int maxBytes) throws IOException {
        try (InputStream input = Files.newInputStream(filePath); ByteArrayOutputStream output = new ByteArrayOutputStream(maxBytes)) {
            byte[] buffer = new byte[8192];
            int remaining = maxBytes;
            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            return output.toByteArray();
        }
    }

    public StudentProject saveProjectFile(Integer studentId, Integer projectId, String relativePath, String content) {
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        Path filePath = this.resolveProjectFile(project, relativePath);
        String safeContent = content != null ? content : "";
        byte[] bytes = safeContent.getBytes(StandardCharsets.UTF_8);
        if ((long)bytes.length > 0x200000L) {
            throw new IllegalArgumentException("File is too large to save online");
        }
        try {
            if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Please select an existing file");
            }
            Files.write(filePath, bytes, new OpenOption[0]);
            this.scheduleMetadataRefresh(project);
            return project;
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to save project file: " + e.getMessage(), e);
        }
    }

    public StudentProject createProjectItem(Integer studentId, Integer projectId, String parentPath, String name, String type) {
        String normalizedType;
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        Path parent = this.resolveProjectPath(project, parentPath, true);
        String safeName = this.validateItemName(name);
        String string = normalizedType = type != null ? type.trim().toLowerCase() : "file";
        if (!"file".equals(normalizedType) && !"directory".equals(normalizedType)) {
            throw new IllegalArgumentException("type must be file or directory");
        }
        try {
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Parent path is not a directory");
            }
            Path target = parent.resolve(safeName).normalize();
            this.ensureInsideWorkspace(project, target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("A file or folder with this name already exists");
            }
            if ("directory".equals(normalizedType)) {
                Files.createDirectory(target, new FileAttribute[0]);
            } else {
                Files.createFile(target, new FileAttribute[0]);
            }
            this.scheduleMetadataRefresh(project);
            return project;
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to create project item: " + e.getMessage(), e);
        }
    }

    public StudentProject deleteProjectItem(Integer studentId, Integer projectId, String relativePath) {
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        Path target = this.resolveProjectPath(project, relativePath, false);
        try {
            Path root = this.workspaceRoot(project);
            if (target.equals(root)) {
                throw new IllegalArgumentException("Cannot delete project root");
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("File or folder does not exist");
            }
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                FileUtils.deleteDirectory((File)target.toFile());
            } else {
                Files.delete(target);
            }
            this.scheduleMetadataRefresh(project);
            return project;
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to delete project item: " + e.getMessage(), e);
        }
    }

    public StudentProject renameProjectItem(Integer studentId, Integer projectId, String relativePath, String newName) {
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        Path source = this.resolveProjectPath(project, relativePath, false);
        String safeName = this.validateItemName(newName);
        try {
            Path root = this.workspaceRoot(project);
            if (source.equals(root)) {
                throw new IllegalArgumentException("Cannot rename project root");
            }
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("File or folder does not exist");
            }
            Path target = source.getParent().resolve(safeName).normalize();
            this.ensureInsideWorkspace(project, target);
            boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            boolean sameEntry = targetExists && Files.isSameFile(source, target);
            if (targetExists && !sameEntry) {
                throw new IllegalArgumentException("A file or folder with this name already exists");
            }
            if (sameEntry && source.getFileName().toString().equals(safeName)) {
                return project;
            }
            if (sameEntry) {
                Path temporary = source.getParent().resolve(".labex-rename-" + java.util.UUID.randomUUID()).normalize();
                this.ensureInsideWorkspace(project, temporary);
                Files.move(source, temporary, new CopyOption[0]);
                Files.move(temporary, target, new CopyOption[0]);
            } else {
                Files.move(source, target, new CopyOption[0]);
            }
            this.scheduleMetadataRefresh(project);
            return project;
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to rename project item: " + e.getMessage(), e);
        }
    }

    public StudentProject refreshProjectMetadata(Integer studentId, Integer projectId) {
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        try {
            this.refreshProjectMetadataInternal(project);
            this.updateById(project);
            return project;
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to refresh project metadata: " + e.getMessage(), e);
        }
    }

    /**
     * 用户文件操作/终端命令后的非关键 metadata 刷新：交给合并调度器异步执行，
     * 避免每次增删改都同步全量扫描拖慢 HTTP 请求；文件树接口直读磁盘，不依赖该字段。
     */
    public void refreshProjectMetadataAsync(Integer studentId, Integer projectId, String reason) {
        if (studentId == null || projectId == null) {
            return;
        }
        if (this.metadataRefreshScheduler != null) {
            this.metadataRefreshScheduler.schedule(studentId, projectId, reason);
            return;
        }
        try {
            StudentProject project = this.requireOwnedProject(studentId, projectId);
            this.refreshProjectMetadataInternal(project);
            this.updateById(project);
        } catch (Exception failure) {
            log.warn("PROJECT_METADATA_REFRESH_SYNC_FALLBACK_FAILED projectId={} errorType={} error={}",
                    projectId, failure.getClass().getSimpleName(), failure.getMessage());
        }
    }

    private void scheduleMetadataRefresh(StudentProject project) {
        this.refreshProjectMetadataAsync(project.getStudentId(), project.getProjectId(), "student_file_operation");
    }

    public String askProjectAgent(Integer studentId, Integer projectId, String relativePath, String question, String mode) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question is required");
        }
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        String context = this.buildAgentContext(project, relativePath);
        String prompt = this.buildAgentPrompt(mode);
        return this.getLlmChat().chat(prompt, context, question.trim());
    }

    public List<Map<String, Object>> listProjectTree(Integer studentId, Integer projectId, String path) {
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        SecureWorkspacePath paths = this.workspacePaths(project);
        Path workspacePath = paths.workspaceRoot();
        Path targetPath = path == null || path.isEmpty() || path.equals("/") ? workspacePath : this.resolveProjectFile(project, path);
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        try {
            this.initializeAgentIgnoreFile(workspacePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize Agent ignore file", e);
        }
        try (Stream<Path> stream = Files.list(targetPath);){
            List<Path> children = stream.filter(child -> this.isBrowsableWorkspaceEntry(paths, child))
                    .sorted(Comparator.comparing(p -> !this.isSafeBrowsableDirectory(paths, p))).toList();
            for (Path child : children) {
                HashMap<String, Object> node = new HashMap<String, Object>();
                node.put("name", child.getFileName().toString());
                node.put("path", workspacePath.relativize(child).toString().replace('\\', '/'));
                boolean isDir = this.isSafeBrowsableDirectory(paths, child);
                node.put("type", isDir ? "directory" : "file");
                if (isDir) {
                    node.put("children", new ArrayList());
                } else {
                    try {
                        node.put("size", Files.size(child));
                    }
                    catch (IOException e) {
                        node.put("size", 0L);
                    }
                }
                result.add(node);
            }
        }
        catch (IOException e) {
            log.warn("Failed to list project tree for project {} at {}: {}", projectId, path, e.getMessage());
            throw new IllegalStateException("Failed to list project tree", e);
        }
        return result;
    }

    public StudentProjectService.ProjectTreePage listProjectTreePage(Integer studentId, Integer projectId, String path,
                                                                       int offset, int limit) {
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        SecureWorkspacePath paths = this.workspacePaths(project);
        Path workspacePath = paths.workspaceRoot();
        Path targetPath = path == null || path.isEmpty() || path.equals("/")
                ? workspacePath : this.resolveProjectFile(project, path);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(200, Math.max(1, limit));
        try {
            this.initializeAgentIgnoreFile(workspacePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize Agent ignore file", e);
        }
        try (Stream<Path> stream = Files.list(targetPath)) {
            List<Path> candidates = stream.filter(child -> this.isBrowsableWorkspaceEntry(paths, child))
                    .skip(safeOffset)
                    .limit((long) safeLimit + 1)
                    .collect(Collectors.toCollection(ArrayList::new));
            boolean hasMore = candidates.size() > safeLimit;
            if (hasMore) {
                candidates = new ArrayList<>(candidates.subList(0, safeLimit));
            }
            candidates.sort(Comparator.comparing((Path child) -> !this.isSafeBrowsableDirectory(paths, child))
                    .thenComparing(child -> child.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Path child : candidates) {
                entries.add(this.projectTreeNode(paths, workspacePath, child));
            }
            return new StudentProjectService.ProjectTreePage(entries, hasMore ? safeOffset + safeLimit : null);
        } catch (IOException e) {
            log.warn("Failed to list project tree page for project {} at {}: {}", projectId, path, e.getMessage());
            throw new IllegalStateException("Failed to list project tree", e);
        }
    }

    private Map<String, Object> projectTreeNode(SecureWorkspacePath paths, Path workspacePath, Path child) {
        HashMap<String, Object> node = new HashMap<>();
        node.put("name", child.getFileName().toString());
        node.put("path", workspacePath.relativize(child).toString().replace('\\', '/'));
        boolean isDir = this.isSafeBrowsableDirectory(paths, child);
        node.put("type", isDir ? "directory" : "file");
        if (isDir) {
            node.put("children", new ArrayList<>());
        } else {
            try {
                node.put("size", Files.size(child));
            } catch (IOException e) {
                node.put("size", 0L);
            }
        }
        return node;
    }

    public void exportProject(Integer studentId, Integer projectId, OutputStream outputStream) {
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        SecureWorkspacePath paths = this.workspacePaths(project);
        Path workspacePath = paths.workspaceRoot();
        try (ZipOutputStream zos = new ZipOutputStream(outputStream);){
            Files.walkFileTree(workspacePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!isSafeWorkspaceEntry(paths, file) || attrs.isSymbolicLink() || attrs.isOther()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = workspacePath.relativize(file).toString().replace('\\', '/');
                    ZipEntry entry = new ZipEntry(relative);
                    zos.putNextEntry(entry);
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!isSafeWorkspaceEntry(paths, dir) || attrs.isSymbolicLink() || attrs.isOther()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String relative = workspacePath.relativize(dir).toString().replace('\\', '/');
                    if (!relative.isEmpty()) {
                        ZipEntry entry = new ZipEntry(relative + "/");
                        zos.putNextEntry(entry);
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException e) {
            throw new RuntimeException("Project export failed: " + e.getMessage(), e);
        }
    }

    public void deleteOwnedProject(Integer studentId, Integer projectId) {
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        Path root = this.workspaceRoot(project).getParent();
        this.cleanup(root);
        this.removeById(project.getProjectId());
    }

    public StudentProject renameProject(Integer studentId, Integer projectId, String newName) {
        StudentProject project = this.requireOwnedProject(studentId, projectId);
        String safeName = this.sanitizeName(newName);
        if (safeName.isBlank()) {
            throw new IllegalArgumentException("Project name cannot be empty");
        }
        project.setProjectName(safeName);
        project.setUpdateTime(LocalDateTime.now());
        this.updateById(project);
        return project;
    }

    private ExtractStats unzipSecurely(Path archivePath, Path targetDir) throws IOException {
        ExtractStats stats = new ExtractStats();
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (InputStream inputStream = Files.newInputStream(archivePath, new OpenOption[0]);
             ZipInputStream zipInputStream = new ZipInputStream(inputStream);){
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    Path dir = this.resolveZipEntry(normalizedTarget, entry.getName());
                    Files.createDirectories(dir, new FileAttribute[0]);
                    continue;
                }
                ++stats.fileCount;
                if (stats.fileCount > 5000) {
                    throw new IllegalArgumentException("Project contains too many files");
                }
                Path outputPath = this.resolveZipEntry(normalizedTarget, entry.getName());
                Files.createDirectories(outputPath.getParent(), new FileAttribute[0]);
                long copied = Files.copy(zipInputStream, outputPath, new CopyOption[0]);
                stats.totalSize += copied;
                if (stats.totalSize > 314572800L) {
                    throw new IllegalArgumentException("Project archive exceeds size limit after extraction");
                }
                zipInputStream.closeEntry();
            }
        }
        return stats;
    }

    private void initializeAgentIgnoreFile(Path workspacePath) throws IOException {
        Path ignoreFile = workspacePath.resolve(ProjectScanPolicy.AGENT_IGNORE_FILE);
        if (Files.exists(ignoreFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(ignoreFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Agent ignore path is not a regular file");
            }
            return;
        }
        Files.writeString(ignoreFile, ProjectScanPolicy.defaultIgnoreFileContent(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private void writeStarterTemplate(Path workspacePath, String projectName, String template) throws IOException {
        Files.createDirectories(workspacePath, new FileAttribute[0]);
        Files.writeString(workspacePath.resolve("README.md"), ("# " + projectName + "\n\nThis project was created in LabexAgent project space.\nYou can edit files, run terminal commands, and ask the programming assistant to continue building it.\n"), StandardCharsets.UTF_8, new OpenOption[0]);
        switch (template) {
            case "web": {
                Files.writeString(workspacePath.resolve("index.html"), "<!doctype html>\n<html lang=\"zh-CN\">\n<head>\n  <meta charset=\"UTF-8\" />\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n  <title>Labex Web Project</title>\n  <link rel=\"stylesheet\" href=\"./style.css\" />\n</head>\n<body>\n  <main>\n    <h1>Hello Labex</h1>\n    <p>Start building your web project here.</p>\n    <button id=\"action\">Click me</button>\n  </main>\n  <script src=\"./script.js\"></script>\n</body>\n</html>\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("style.css"), "body {\n  margin: 0;\n  font-family: Arial, sans-serif;\n  background: #f6f8fb;\n  color: #172033;\n}\n\nmain {\n  max-width: 720px;\n  margin: 80px auto;\n  padding: 32px;\n}\n\nbutton {\n  padding: 10px 16px;\n  border: 0;\n  border-radius: 8px;\n  background: #2563eb;\n  color: #fff;\n  cursor: pointer;\n}\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("script.js"), "document.getElementById('action')?.addEventListener('click', () => {\n  alert('Labex project is ready.');\n});\n", StandardCharsets.UTF_8, new OpenOption[0]);
                break;
            }
            case "python": {
                Files.writeString(workspacePath.resolve("main.py"), "def main():\n    print(\"Hello Labex\")\n\n\nif __name__ == \"__main__\":\n    main()\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("requirements.txt"), "", StandardCharsets.UTF_8, new OpenOption[0]);
                break;
            }
            case "node": {
                Files.writeString(workspacePath.resolve("package.json"), "  {\n  \"name\": \"labex-node-project\",\n  \"version\": \"1.0.0\",\n  \"type\": \"module\",\n  \"scripts\": {\n    \"start\": \"node index.js\"\n  }\n}\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("index.js"), "console.log(\"Hello Labex\");\n", StandardCharsets.UTF_8, new OpenOption[0]);
                break;
            }
            case "react": {
                Files.createDirectories(workspacePath.resolve("src"), new FileAttribute[0]);
                Files.writeString(workspacePath.resolve("package.json"), "{\n  \"name\": \"labex-react-project\",\n  \"version\": \"1.0.0\",\n  \"type\": \"module\",\n  \"scripts\": {\n    \"dev\": \"vite --host 0.0.0.0\",\n    \"build\": \"vite build\",\n    \"preview\": \"vite preview --host 0.0.0.0\"\n  },\n  \"dependencies\": {\n    \"@vitejs/plugin-react\": \"latest\",\n    \"vite\": \"latest\",\n    \"react\": \"latest\",\n    \"react-dom\": \"latest\"\n  },\n  \"devDependencies\": {}\n}\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("index.html"), "<div id=\"root\"></div>\n<script type=\"module\" src=\"/src/main.jsx\"></script>\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("src").resolve("main.jsx"), "import React from 'react'\nimport { createRoot } from 'react-dom/client'\nimport './style.css'\n\nfunction App() {\n  return (\n    <main>\n      <h1>Labex React Project</h1>\n      <p>Edit src/main.jsx to start building.</p>\n    </main>\n  )\n}\n\ncreateRoot(document.getElementById('root')).render(<App />)\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("src").resolve("style.css"), "body { margin: 0; font-family: Arial, sans-serif; background: #f6f8fb; color: #172033; }\nmain { max-width: 760px; margin: 80px auto; padding: 32px; }\n", StandardCharsets.UTF_8, new OpenOption[0]);
                break;
            }
            case "vue": {
                Files.createDirectories(workspacePath.resolve("src"), new FileAttribute[0]);
                Files.writeString(workspacePath.resolve("package.json"), "{\n  \"name\": \"labex-vue-project\",\n  \"version\": \"1.0.0\",\n  \"type\": \"module\",\n  \"scripts\": {\n    \"dev\": \"vite --host 0.0.0.0\",\n    \"build\": \"vite build\",\n    \"preview\": \"vite preview --host 0.0.0.0\"\n  },\n  \"dependencies\": {\n    \"@vitejs/plugin-vue\": \"latest\",\n    \"vite\": \"latest\",\n    \"vue\": \"latest\"\n  },\n  \"devDependencies\": {}\n}\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("index.html"), "<div id=\"app\"></div>\n<script type=\"module\" src=\"/src/main.js\"></script>\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("src").resolve("main.js"), "import { createApp } from 'vue'\nimport App from './App.vue'\nimport './style.css'\n\ncreateApp(App).mount('#app')\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("src").resolve("App.vue"), "<template>\n  <main>\n    <h1>Labex Vue Project</h1>\n    <p>Edit src/App.vue to start building.</p>\n  </main>\n</template>\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("src").resolve("style.css"), "body { margin: 0; font-family: Arial, sans-serif; background: #f6f8fb; color: #172033; }\nmain { max-width: 760px; margin: 80px auto; padding: 32px; }\n", StandardCharsets.UTF_8, new OpenOption[0]);
                break;
            }
            case "flask": {
                Files.createDirectories(workspacePath.resolve("app"), new FileAttribute[0]);
                Files.writeString(workspacePath.resolve("requirements.txt"), "Flask>=3.0.0\npython-dotenv>=1.0.0\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("run.py"), "from app import create_app\n\napp = create_app()\n\nif __name__ == \"__main__\":\n    app.run(host=\"0.0.0.0\", port=5000, debug=True)\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("app").resolve("__init__.py"), "from flask import Flask, jsonify\n\n\ndef create_app():\n    app = Flask(__name__)\n\n    @app.get(\"/\")\n    def index():\n        return jsonify({\"message\": \"Labex Flask Project\"})\n\n    return app\n", StandardCharsets.UTF_8, new OpenOption[0]);
                break;
            }
            case "springboot": {
                Files.createDirectories(workspacePath.resolve("src/main/java/com/labex/demo"), new FileAttribute[0]);
                Files.createDirectories(workspacePath.resolve("src/main/resources"), new FileAttribute[0]);
                Files.writeString(workspacePath.resolve("pom.xml"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n  <modelVersion>4.0.0</modelVersion>\n  <groupId>com.labex</groupId>\n  <artifactId>demo</artifactId>\n  <version>0.0.1-SNAPSHOT</version>\n  <parent>\n    <groupId>org.springframework.boot</groupId>\n    <artifactId>spring-boot-starter-parent</artifactId>\n    <version>3.2.5</version>\n    <relativePath/>\n  </parent>\n  <properties>\n    <java.version>17</java.version>\n  </properties>\n  <dependencies>\n    <dependency>\n      <groupId>org.springframework.boot</groupId>\n      <artifactId>spring-boot-starter-web</artifactId>\n    </dependency>\n  </dependencies>\n  <build>\n    <plugins>\n      <plugin>\n        <groupId>org.springframework.boot</groupId>\n        <artifactId>spring-boot-maven-plugin</artifactId>\n      </plugin>\n    </plugins>\n  </build>\n</project>\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("src/main/resources").resolve("application.yml"), "server:\n  port: 8080\n", StandardCharsets.UTF_8, new OpenOption[0]);
                Files.writeString(workspacePath.resolve("src/main/java/com/labex/demo").resolve("DemoApplication.java"), "package com.labex.demo;\n\nimport org.springframework.boot.SpringApplication;\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\nimport org.springframework.web.bind.annotation.GetMapping;\nimport org.springframework.web.bind.annotation.RestController;\n\n@SpringBootApplication\npublic class DemoApplication {\n    public static void main(String[] args) {\n        SpringApplication.run(DemoApplication.class, args);\n    }\n}\n\n@RestController\nclass HelloController {\n    @GetMapping(\"/\")\n    public String hello() {\n        return \"Labex Spring Boot Project\";\n    }\n}\n", StandardCharsets.UTF_8, new OpenOption[0]);
                break;
            }
            case "blank": {
                break;
            }
            default: {
                throw new IllegalArgumentException("Unsupported project template: " + template);
            }
        }
    }

    private StudentProject requireOwnedProject(Integer studentId, Integer projectId) {
        StudentProject project = this.getOwnedProject(studentId, projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        return project;
    }

    private Path resolveProjectFile(StudentProject project, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("File path is required");
        }
        return this.resolveProjectPath(project, relativePath, false);
    }

    private Path resolveProjectPath(StudentProject project, String relativePath, boolean allowRoot) {
        SecureWorkspacePath paths = this.workspacePaths(project);
        if (relativePath == null || relativePath.isBlank()) {
            if (allowRoot) {
                return paths.workspaceRoot();
            }
            throw new IllegalArgumentException("File path is required");
        }
        return paths.resolveExisting(relativePath);
    }

    private Path workspaceRoot(StudentProject project) {
        return this.workspacePaths(project).workspaceRoot();
    }

    private void ensureInsideWorkspace(StudentProject project, Path path) {
        SecureWorkspacePath paths = this.workspacePaths(project);
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(paths.workspaceRoot())) {
            throw new IllegalArgumentException("Unsafe project file path");
        }
        String relativePath = paths.workspaceRoot().relativize(normalized).toString();
        try {
            paths.resolveForCreate(relativePath.isBlank() ? "." : relativePath);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsafe project file path", e);
        }
    }

    private SecureWorkspacePath workspacePaths(StudentProject project) {
        if (project == null || project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
            throw new IllegalArgumentException("Project workspace is unavailable");
        }
        return new SecureWorkspacePath(Path.of(project.getWorkspacePath()));
    }

    private String validateItemName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        String trimmed = name.trim();
        if (".".equals(trimmed) || "..".equals(trimmed)) {
            throw new IllegalArgumentException("Invalid name");
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.matches(".*[<>:\"|?*].*")) {
            throw new IllegalArgumentException("Name contains unsupported characters");
        }
        return trimmed;
    }

    private void refreshProjectMetadataInternal(StudentProject project) throws IOException {
        Path workspacePath = this.workspaceRoot(project);
        ExtractStats stats = this.countProjectFiles(workspacePath);
        project.setStructureJson(GSON.toJson(this.buildTree(workspacePath, workspacePath, 0)));
        project.setFileCount(Integer.valueOf(stats.fileCount));
        project.setTotalSize(Long.valueOf(stats.totalSize));
        project.setUpdateTime(LocalDateTime.now());
    }

    private ExtractStats countProjectFiles(Path workspacePath) throws IOException {
        SecureWorkspacePath paths = new SecureWorkspacePath(workspacePath);
        ExtractStats stats = new ExtractStats();
        Files.walkFileTree(workspacePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (ProjectScanPolicy.isInternalRuntimeEntry(paths.workspaceRoot(), dir)
                            || !isSafeWorkspaceEntry(paths, dir)
                            || attrs.isSymbolicLink()
                            || attrs.isOther()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (ProjectScanPolicy.isInternalRuntimeEntry(paths.workspaceRoot(), file)
                            || !isSafeWorkspaceEntry(paths, file)
                            || attrs.isSymbolicLink()
                            || attrs.isOther()) {
                        return FileVisitResult.CONTINUE;
                    }
                    stats.fileCount++;
                    stats.totalSize += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                    if (ProjectScanPolicy.isInternalRuntimeEntry(paths.workspaceRoot(), file)) {
                        log.debug("Skipping unreadable internal runtime path {}: {}", file, error.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                    throw error;
                }
            });
        return stats;
    }

    private LLMChat getLlmChat() {
        return "ollama".equalsIgnoreCase(this.ragConfig.getLlmProvider()) ? this.ollamaChat : this.miniMaxChat;
    }

    private String buildAgentContext(StudentProject project, String relativePath) {
        StringBuilder context = new StringBuilder();
        context.append("\u9879\u76ee\u540d\u79f0: ").append(project.getProjectName()).append('\n');
        context.append("\u9879\u76ee\u7ed3\u6784 JSON:\n").append(this.limitText(project.getStructureJson(), 20000)).append("\n\n");
        if (relativePath != null && !relativePath.isBlank()) {
            try {
                String content = this.readProjectFile(project.getStudentId(), project.getProjectId(), relativePath);
                context.append("\u5f53\u524d\u6587\u4ef6: ").append(relativePath).append('\n');
                context.append("\u5f53\u524d\u6587\u4ef6\u5185\u5bb9:\n```\n").append(this.limitText(content, 50000)).append("\n```");
            }
            catch (Exception e) {
                context.append("\u5f53\u524d\u6587\u4ef6\u8bfb\u53d6\u5931\u8d25: ").append(e.getMessage());
            }
        }
        return context.toString();
    }

    private String buildAgentPrompt(String mode) {
        String normalizedMode = mode != null ? mode.trim().toLowerCase() : "ask";
        String base = "\u4f60\u662f LabexAgent\uff0c\u4e00\u4e2a\u9762\u5411\u7f16\u7a0b\u5b9e\u9a8c\u7684\u4ee3\u7801\u52a9\u624b\u3002\u4f60\u53ea\u80fd\u57fa\u4e8e\u7528\u6237\u62e5\u6709\u7684\u9879\u76ee\u4e0a\u4e0b\u6587\u8fdb\u884c\u5206\u6790\uff0c\u4f18\u5148\u7ed9\u51fa\u53ef\u6267\u884c\u3001\u7b80\u6d01\u7684\u4e2d\u6587\u5efa\u8bae\u3002\u5f53\u524d\u7cfb\u7edf\u8fd8\u6ca1\u6709\u5f00\u653e\u81ea\u52a8\u5199\u6587\u4ef6\u80fd\u529b\uff0c\u5982\u679c\u9700\u8981\u4fee\u6539\u4ee3\u7801\uff0c\u53ea\u80fd\u7ed9\u51fa\u65b9\u6848\u3001\u4ee3\u7801\u7247\u6bb5\u548c\u98ce\u9669\uff0c\u4e0d\u8981\u58f0\u79f0\u5df2\u7ecf\u4fee\u6539\u6587\u4ef6\u3002";
        return switch (normalizedMode) {
            case "agent" -> base + "\u5f53\u524d\u6a21\u5f0f\u662f Agent\uff1a\u8bf7\u628a\u7528\u6237\u76ee\u6807\u62c6\u6210\u4efb\u52a1\u6e05\u5355\uff0c\u8bf4\u660e\u5c06\u5982\u4f55\u5206\u6790\u548c\u4fee\u6539\uff0c\u5e76\u7ed9\u51fa\u5173\u952e\u4ee3\u7801\u5efa\u8bae\u3002";
            case "plan" -> base + "\u5f53\u524d\u6a21\u5f0f\u662f Plan\uff1a\u8bf7\u53ea\u505a\u89c4\u5212\uff0c\u8f93\u51fa\u5206\u6b65\u9aa4\u5b9e\u65bd\u8ba1\u5212\u3001\u5f71\u54cd\u6587\u4ef6\u548c\u9a8c\u8bc1\u65b9\u5f0f\u3002";
            case "debug" -> base + "\u5f53\u524d\u6a21\u5f0f\u662f Debug\uff1a\u8bf7\u5b9a\u4f4d\u53ef\u80fd\u7684\u9519\u8bef\u539f\u56e0\uff0c\u7ed9\u51fa\u6392\u67e5\u987a\u5e8f\u3001\u5173\u952e\u65e5\u5fd7/\u65ad\u70b9\u4f4d\u7f6e\u548c\u4fee\u590d\u5efa\u8bae\u3002";
            case "test" -> base + "\u5f53\u524d\u6a21\u5f0f\u662f Test\uff1a\u8bf7\u8bbe\u8ba1\u6d4b\u8bd5\u7528\u4f8b\u3001\u8fd0\u884c\u9a8c\u8bc1\u601d\u8def\u548c\u8fb9\u754c\u573a\u666f\uff0c\u5fc5\u8981\u65f6\u7ed9\u51fa\u6d4b\u8bd5\u4ee3\u7801\u3002";
            default -> base + "\u5f53\u524d\u6a21\u5f0f\u662f Ask\uff1a\u8bf7\u76f4\u63a5\u56de\u7b54\u7528\u6237\u5173\u4e8e\u5f53\u524d\u9879\u76ee\u6216\u5f53\u524d\u6587\u4ef6\u7684\u95ee\u9898\u3002";
        };
    }

    private String limitText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text != null ? text : "";
        }
        return text.substring(0, maxLength) + "\n...\u5185\u5bb9\u5df2\u622a\u65ad...";
    }

    private String buildUnsupportedFileMessage(String relativePath) {
        int dotIndex;
        String ext = "";
        int n = dotIndex = relativePath != null ? relativePath.lastIndexOf(46) : -1;
        if (dotIndex >= 0 && dotIndex < relativePath.length() - 1) {
            ext = relativePath.substring(dotIndex + 1).toLowerCase();
        }
        Map<String, String> typeNames = Map.ofEntries(Map.entry("pyc", "Python \u5b57\u8282\u7801\u6587\u4ef6"), Map.entry("pkl", "Python pickle \u6a21\u578b/\u5e8f\u5217\u5316\u6587\u4ef6"), Map.entry("db", "\u6570\u636e\u5e93\u6587\u4ef6"), Map.entry("sqlite", "SQLite \u6570\u636e\u5e93\u6587\u4ef6"), Map.entry("sqlite3", "SQLite \u6570\u636e\u5e93\u6587\u4ef6"), Map.entry("class", "Java class \u5b57\u8282\u7801\u6587\u4ef6"), Map.entry("jar", "Java JAR \u5305"), Map.entry("zip", "\u538b\u7f29\u5305\u6587\u4ef6"), Map.entry("png", "\u56fe\u7247\u6587\u4ef6"), Map.entry("jpg", "\u56fe\u7247\u6587\u4ef6"), Map.entry("jpeg", "\u56fe\u7247\u6587\u4ef6"), Map.entry("pdf", "PDF \u6587\u6863"), Map.entry("exe", "\u53ef\u6267\u884c\u6587\u4ef6"), Map.entry("dll", "\u52a8\u6001\u5e93\u6587\u4ef6"));
        String typeName = typeNames.getOrDefault(ext, "\u4e8c\u8fdb\u5236\u6587\u4ef6");
        return "\u6b64\u6587\u4ef6\u4e3a" + typeName + "\uff0c\u65e0\u6cd5\u5728\u4ee3\u7801\u7f16\u8f91\u5668\u4e2d\u4f5c\u4e3a\u6587\u672c\u76f4\u63a5\u6253\u5f00";
    }

    private Path resolveZipEntry(Path targetDir, String entryName) {
        String normalizedEntryName = entryName.replace('\\', '/');
        Path resolved = targetDir.resolve(normalizedEntryName).normalize();
        if (!resolved.startsWith(targetDir)) {
            throw new IllegalArgumentException("Unsafe archive entry detected: " + entryName);
        }
        return resolved;
    }

    private Map<String, Object> buildTree(Path root, Path current, int depth) throws IOException {
        SecureWorkspacePath paths = new SecureWorkspacePath(root);
        return this.buildTree(paths, ProjectScanPolicy.loadIgnoreRules(paths), paths.workspaceRoot(), current.toAbsolutePath().normalize(), depth);
    }

    private Map<String, Object> buildTree(SecureWorkspacePath paths, ProjectScanPolicy.ScanIgnoreRules ignoreRules,
                                           Path root, Path current, int depth) throws IOException {
        if (!this.isSafeWorkspaceEntry(paths, current)) {
            throw new IOException("Unsafe workspace path");
        }
        HashMap<String, Object> node = new HashMap<String, Object>();
        node.put("name", current.equals(root) ? root.getFileName().toString() : current.getFileName().toString());
        node.put("path", root.relativize(current).toString().replace('\\', '/'));
        try {
            node.put("type", Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) ? "directory" : "file");
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                node.put("size", Files.size(current));
                return node;
            }
        }
        catch (IOException e) {
            log.debug("Skipping unreadable path {}: {}", current, e.getMessage());
            node.put("type", "broken");
            node.put("size", 0L);
            return node;
        }
        ArrayList<Map> children = new ArrayList<Map>();
        if (depth < 6) {
            try (Stream<Path> stream = Files.list(current);){
                List<Path> childrenPaths = stream.filter(child -> this.isVisibleAgentStructureEntry(paths, ignoreRules, child))
                        .sorted((a, b) -> Boolean.compare(!this.isSafeAgentStructureDirectory(paths, ignoreRules, a), !this.isSafeAgentStructureDirectory(paths, ignoreRules, b)))
                        .limit(200L).toList();
                for (Path child : childrenPaths) {
                    try {
                        children.add(this.buildTree(paths, ignoreRules, root, child, depth + 1));
                    }
                    catch (IOException e) {
                        log.debug("Skipping unreadable child {}: {}", child, e.getMessage());
                    }
                }
            }
            catch (IOException e) {
                log.debug("Cannot list directory {}: {}", current, e.getMessage());
            }
        }
        node.put("children", children);
        return node;
    }

    private boolean isBrowsableWorkspaceEntry(SecureWorkspacePath paths, Path entry) {
        return this.isSafeWorkspaceEntry(paths, entry);
    }

    private boolean isSafeBrowsableDirectory(SecureWorkspacePath paths, Path path) {
        return this.isBrowsableWorkspaceEntry(paths, path)
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isVisibleAgentStructureEntry(SecureWorkspacePath paths, ProjectScanPolicy.ScanIgnoreRules ignoreRules,
                                                 Path entry) {
        if (!this.isSafeWorkspaceEntry(paths, entry)) {
            return false;
        }
        return !ignoreRules.shouldSkipEntry(entry, Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS));
    }

    private boolean isSafeAgentStructureDirectory(SecureWorkspacePath paths, ProjectScanPolicy.ScanIgnoreRules ignoreRules,
                                                  Path path) {
        return this.isVisibleAgentStructureEntry(paths, ignoreRules, path)
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isSafeWorkspaceEntry(SecureWorkspacePath paths, Path entry) {
        try {
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

    private void cleanup(Path path) {
        if (path == null || !Files.exists(path, new LinkOption[0])) {
            return;
        }
        try {
            FileUtils.deleteDirectory((File)path.toFile());
        }
        catch (IOException e) {
            log.warn("Failed to cleanup project path {}: {}", path, e.getMessage());
        }
    }

    private String sanitizeName(String name) {
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return sanitized.isBlank() ? "project" : sanitized;
    }

    private String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf(46);
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }

    private static class ExtractStats {
        int fileCount;
        long totalSize;
    }
}
