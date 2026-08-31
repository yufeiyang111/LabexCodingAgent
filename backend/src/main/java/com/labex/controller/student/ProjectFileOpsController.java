package com.labex.controller.student;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.labex.common.Result;
import com.labex.labexagent.service.WorkspaceTextSearchService;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.WorkspaceOperationGuard;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.labexagent.workspace.files.ProjectExportJobService;
import com.labex.labexagent.workspace.files.ProjectFileDownloadService;
import com.labex.labexagent.workspace.files.ProjectFileTransferService;
import com.labex.labexagent.workspace.files.ProjectFileUploadService;
import com.labex.labexagent.workspace.files.ProjectImagePreviewService;
import com.labex.service.StudentProjectService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户侧工作区文件增强操作：复制/移动/上传/下载/图片预览/目录搜索。
 * 与既有 CRUD（StudentProjectController）分离，保持单一职责；
 * 所有权、路径边界、限流与冲突协议均下沉到各服务实现，controller 只做参数装配与错误映射。
 */
@RestController
@RequestMapping(value = {"/student/projects"})
public class ProjectFileOpsController {
    private static final Logger log = LoggerFactory.getLogger(ProjectFileOpsController.class);
    private static final Gson GSON = new Gson();
    private static final int SEARCH_RESULT_LIMIT = 200;

    private final ProjectFileTransferService transferService;
    private final ProjectFileUploadService uploadService;
    private final ProjectImagePreviewService imagePreviewService;
    private final ProjectFileDownloadService downloadService;
    private final ProjectExportJobService exportJobService;
    private final WorkspaceTextSearchService searchService;
    private final WorkspaceOperationGuard operationGuard;
    private final StudentProjectService studentProjectService;

    public ProjectFileOpsController(ProjectFileTransferService transferService,
                                    ProjectFileUploadService uploadService,
                                    ProjectImagePreviewService imagePreviewService,
                                    ProjectFileDownloadService downloadService,
                                    ProjectExportJobService exportJobService,
                                    WorkspaceTextSearchService searchService,
                                    WorkspaceOperationGuard operationGuard,
                                    StudentProjectService studentProjectService) {
        this.transferService = transferService;
        this.uploadService = uploadService;
        this.imagePreviewService = imagePreviewService;
        this.downloadService = downloadService;
        this.exportJobService = exportJobService;
        this.searchService = searchService;
        this.operationGuard = operationGuard;
        this.studentProjectService = studentProjectService;
    }

    /** status: done | skipped | conflict；conflict 时 conflicts 为待用户裁决清单。 */
    public static class TransferBody {
        private String sourcePath;
        private String targetParentPath;
        private Map<String, String> decisions;

        public String getSourcePath() {
            return sourcePath;
        }

        public String getTargetParentPath() {
            return targetParentPath;
        }

        public Map<String, String> getDecisions() {
            return decisions;
        }
    }

    @PostMapping(value = {"/{projectId}/files/copy"})
    public Result<?> copy(@PathVariable Integer projectId, @RequestBody TransferBody body, Authentication auth) {
        try {
            if (body == null || body.getSourcePath() == null || body.getSourcePath().isBlank()) {
                return Result.error("sourcePath is required");
            }
            ProjectFileTransferService.TransferOutcome outcome = transferService.copy(
                    new ProjectFileTransferService.TransferRequest(studentId(auth), projectId,
                            body.getSourcePath(), body.getTargetParentPath(), body.getDecisions()));
            return Result.success(transferPayload(outcome));
        } catch (Exception e) {
            log.info("FILE_COPY_FAILED projectId={} reason={}", projectId, e.getMessage());
            return errorResult(e);
        }
    }

    @PutMapping(value = {"/{projectId}/files/item/move"})
    public Result<?> move(@PathVariable Integer projectId, @RequestBody TransferBody body, Authentication auth) {
        try {
            if (body == null || body.getSourcePath() == null || body.getSourcePath().isBlank()) {
                return Result.error("sourcePath is required");
            }
            ProjectFileTransferService.TransferOutcome outcome = transferService.move(
                    new ProjectFileTransferService.TransferRequest(studentId(auth), projectId,
                            body.getSourcePath(), body.getTargetParentPath(), body.getDecisions()));
            return Result.success(transferPayload(outcome));
        } catch (Exception e) {
            log.info("FILE_MOVE_FAILED projectId={} reason={}", projectId, e.getMessage());
            return errorResult(e);
        }
    }

    @PostMapping(value = {"/{projectId}/files/upload"})
    public Result<?> upload(@PathVariable Integer projectId,
                            @RequestParam("files") List<MultipartFile> files,
                            @RequestParam(required = false) String targetDir,
                            @RequestParam(required = false) String relativePathsJson,
                            @RequestParam(required = false) String decisionsJson,
                            Authentication auth) {
        try {
            List<String> relativePaths = parseJsonList(relativePathsJson);
            Map<String, String> decisions = parseJsonMap(decisionsJson);
            ProjectFileUploadService.UploadOutcome outcome = uploadService.upload(
                    new ProjectFileUploadService.UploadRequest(studentId(auth), projectId, targetDir,
                            files, relativePaths, decisions));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", outcome.status());
            payload.put("conflicts", outcome.conflicts());
            payload.put("savedCount", outcome.savedCount());
            payload.put("skippedCount", outcome.skippedCount());
            return Result.success(payload);
        } catch (Exception e) {
            log.info("FILE_UPLOAD_FAILED projectId={} reason={}", projectId, e.getMessage());
            return errorResult(e);
        }
    }

    @GetMapping(value = {"/{projectId}/files/search"})
    public Result<?> search(@PathVariable Integer projectId,
                            @RequestParam(required = false) String dir,
                            @RequestParam String q,
                            @RequestParam(defaultValue = "false") boolean regex,
                            @RequestParam(defaultValue = "false") boolean caseSensitive,
                            @RequestParam(required = false) String include,
                            Authentication auth) {
        try {
            Integer studentId = studentId(auth);
            operationGuard.checkRate(WorkspaceOperationGuard.FileOp.SEARCH, studentId);
            try (WorkspaceOperationGuard.Slot slot =
                         operationGuard.acquireSlot(WorkspaceOperationGuard.FileOp.SEARCH)) {
                SecureWorkspacePath paths = ownedPaths(studentId, projectId);
                var searchRoot = (dir == null || dir.isBlank())
                        ? paths.workspaceRoot()
                        : paths.resolveExisting(dir.trim().replace('\\', '/'));
                WorkspaceTextSearchService.SearchResult result = searchService.search(paths, searchRoot,
                        new WorkspaceTextSearchService.SearchQuery(q, regex, caseSensitive,
                                include == null ? "" : include.trim(), SEARCH_RESULT_LIMIT));
                List<Map<String, Object>> hits = new ArrayList<>(result.hits().size());
                for (var hit : result.hits()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", hit.path());
                    item.put("line", hit.lineNumber());
                    item.put("text", hit.lineText());
                    hits.add(item);
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("hits", hits);
                payload.put("truncated", !result.complete());
                payload.put("elapsedMs", result.elapsedMillis());
                return Result.success(payload);
            }
        } catch (Exception e) {
            log.info("FILE_SEARCH_FAILED projectId={} reason={}", projectId, e.getMessage());
            return errorResult(e);
        }
    }

    @GetMapping(value = {"/{projectId}/files/image"})
    public void image(@PathVariable Integer projectId, @RequestParam String path,
                      Authentication auth, HttpServletResponse response) throws IOException {
        try {
            var content = imagePreviewService.resolve(studentId(auth), projectId, path);
            response.setContentType(content.contentType());
            response.setContentLengthLong(content.sizeBytes());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Security-Policy", "sandbox");
            response.setHeader("Cache-Control", "private, max-age=60");
            Files.copy(content.file(), response.getOutputStream());
        } catch (Exception e) {
            log.info("FILE_IMAGE_FAILED projectId={} reason={}", projectId, e.getMessage());
            writeJsonError(response, e);
        }
    }

    @GetMapping(value = {"/{projectId}/files/download"})
    public void download(@PathVariable Integer projectId, @RequestParam String path,
                         Authentication auth, HttpServletResponse response) throws IOException {
        ProjectFileDownloadService.DownloadTarget target = null;
        try {
            target = downloadService.prepare(studentId(auth), projectId, path);
            String name = target.zipped() ? target.downloadName() + ".zip" : target.downloadName();
            response.setContentType(target.zipped() ? "application/zip" : "application/octet-stream");
            response.setHeader("Content-Disposition", downloadService.contentDispositionValue(name));
            if (!target.zipped()) {
                response.setContentLengthLong(Files.size(target.file()));
            }
            downloadService.write(target, response.getOutputStream());
        } catch (Exception e) {
            if (target == null) {
                log.info("FILE_DOWNLOAD_REJECTED projectId={} reason={}", projectId, e.getMessage());
                writeJsonError(response, e);
                return;
            }
            // 响应已提交，无法再改状态码；中断连接让前端感知失败。
            throw e;
        }
    }

    // ─── 项目异步导出：创建任务 / 轮询进度 / 取消 / 下载成品 ───

    public static class ExportJobBody {
        private Boolean includeAll;

        public Boolean getIncludeAll() {
            return includeAll;
        }
    }

    @PostMapping(value = {"/{projectId}/export/jobs"})
    public Result<?> createExportJob(@PathVariable Integer projectId,
                                     @RequestBody(required = false) ExportJobBody body,
                                     Authentication auth) {
        try {
            boolean includeAll = body != null && Boolean.TRUE.equals(body.getIncludeAll());
            return Result.success(exportJobService.createJob(studentId(auth), projectId, includeAll));
        } catch (Exception e) {
            log.info("EXPORT_JOB_CREATE_FAILED projectId={} reason={}", projectId, e.getMessage());
            return errorResult(e);
        }
    }

    @GetMapping(value = {"/{projectId}/export/jobs/{jobId}"})
    public Result<?> exportJobStatus(@PathVariable Integer projectId, @PathVariable String jobId,
                                     Authentication auth) {
        try {
            return Result.success(exportJobService.getJob(studentId(auth), projectId, jobId));
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    @DeleteMapping(value = {"/{projectId}/export/jobs/{jobId}"})
    public Result<?> cancelExportJob(@PathVariable Integer projectId, @PathVariable String jobId,
                                     Authentication auth) {
        try {
            return Result.success(exportJobService.cancelJob(studentId(auth), projectId, jobId));
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    @GetMapping(value = {"/{projectId}/export/jobs/{jobId}/download"})
    public void downloadExportJob(@PathVariable Integer projectId, @PathVariable String jobId,
                                  Authentication auth, HttpServletResponse response) throws IOException {
        Path file = null;
        String fileName = null;
        try {
            Integer student = studentId(auth);
            file = exportJobService.resolveDownload(student, projectId, jobId);
            fileName = exportJobService.projectZipName(student, projectId, jobId);
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", downloadService.contentDispositionValue(fileName));
            response.setContentLengthLong(Files.size(file));
            Files.copy(file, response.getOutputStream());
        } catch (Exception e) {
            if (file == null) {
                log.info("EXPORT_JOB_DOWNLOAD_REJECTED projectId={} reason={}", projectId, e.getMessage());
                writeJsonError(response, e);
                return;
            }
            throw e;
        }
    }

    private Map<String, Object> transferPayload(ProjectFileTransferService.TransferOutcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", outcome.status());
        payload.put("conflicts", outcome.conflicts());
        payload.put("targetPath", outcome.targetPath());
        return payload;
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return GSON.fromJson(json, new TypeToken<List<String>>() {
        }.getType());
    }

    private Map<String, String> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return GSON.fromJson(json, new TypeToken<Map<String, String>>() {
        }.getType());
    }

    private SecureWorkspacePath ownedPaths(Integer studentId, Integer projectId) {
        var project = studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        return ProjectWorkspace.paths(project);
    }

    private Integer studentId(Authentication auth) {
        return Integer.parseInt(auth.getName());
    }

    private Result<?> errorResult(Exception e) {
        if (e instanceof WorkspaceOperationGuard.FileOpsRejectedException rejected) {
            return Result.error(429, rejected.getMessage());
        }
        String message = e.getMessage();
        return Result.error(message == null || message.isBlank() ? "操作失败" : message);
    }

    /** 二进制端点无法复用统一 Result 包装，错误以 JSON 状态码表达。 */
    private void writeJsonError(HttpServletResponse response, Exception e) throws IOException {
        int status = e instanceof WorkspaceOperationGuard.FileOpsRejectedException ? 429 : 400;
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String message = (e.getMessage() == null ? "操作失败" : e.getMessage()).replace("\"", "'");
        response.getWriter().write("{\"code\":" + (status == 429 ? 429 : -1)
                + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
