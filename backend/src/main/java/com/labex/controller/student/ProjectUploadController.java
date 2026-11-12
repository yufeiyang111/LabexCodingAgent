package com.labex.controller.student;

import com.labex.common.Result;
import com.labex.labexagent.workspace.ProjectArchiveLimits;
import com.labex.labexagent.workspace.ProjectUploadSessionService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * 分片上传的项目压缩包入口。
 *
 * <p>整包一次 POST 会被反向代理或 CDN 按单请求体截断（Cloudflare Free/Pro 为 100 MB），
 * 应用层看到的只是网关的 413，{@code maxArchiveBytes} 形同虚设。这里把压缩包切成固定大小的片，
 * 每片都是独立的原始字节请求，网关无从拒绝，同时拿到「第几片 / 共几片」的真实进度。
 */
@RestController
@RequestMapping(value={"/student/projects/uploads"})
public class ProjectUploadController {

    private static final Logger log = LoggerFactory.getLogger(ProjectUploadController.class);

    @Autowired
    private ProjectUploadSessionService uploadSessions;
    @Autowired
    private ProjectArchiveLimits archiveLimits;

    /** 建会话：只声明大小与文件名，不传字节。返回分片总数与片大小，由前端据此切片。 */
    @PostMapping
    public Result<ProjectUploadSessionService.SessionView> create(@RequestBody UploadStartRequest request,
                                                                  Authentication auth) {
        try {
            if (request == null) {
                return Result.error("请求体不能为空");
            }
            boolean skipDependencies = request.getSkipDependencyDirectories() == null
                    ? archiveLimits.isSkipDependencyDirectoriesByDefault()
                    : request.getSkipDependencyDirectories().booleanValue();
            return Result.success(uploadSessions.create(studentId(auth), request.getFileName(),
                    request.getSize() == null ? 0L : request.getSize().longValue(),
                    request.getProjectName(), skipDependencies));
        } catch (Exception e) {
            log.info("UPLOAD_SESSION_CREATE_REJECTED reason={}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    /** 上传一片。原始字节体，不带 multipart 包装，避开 multipart 的整包上限。 */
    @PutMapping(value={"/{sessionId}/chunks/{index}"})
    public Result<ProjectUploadSessionService.SessionView> writeChunk(@PathVariable String sessionId,
                                                                     @PathVariable int index,
                                                                     @RequestBody(required=false) byte[] data,
                                                                     Authentication auth) {
        try {
            return Result.success(uploadSessions.writeChunk(studentId(auth), sessionId, index, data));
        } catch (Exception e) {
            log.info("UPLOAD_CHUNK_REJECTED sessionId={} index={} reason={}", sessionId, index, e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    /** 全部到齐后开始导入（解压 + 建项目）。导入是异步的，进度靠下面的 GET 轮询。 */
    @PostMapping(value={"/{sessionId}/complete"})
    public Result<ProjectUploadSessionService.SessionView> complete(@PathVariable String sessionId,
                                                                    Authentication auth) {
        try {
            return Result.success(uploadSessions.complete(studentId(auth), sessionId));
        } catch (Exception e) {
            log.info("UPLOAD_COMPLETE_REJECTED sessionId={} reason={}", sessionId, e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @GetMapping(value={"/{sessionId}"})
    public Result<ProjectUploadSessionService.SessionView> status(@PathVariable String sessionId,
                                                                  @RequestParam(required=false) Boolean silent,
                                                                  Authentication auth) {
        try {
            return Result.success(uploadSessions.get(studentId(auth), sessionId));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping(value={"/{sessionId}"})
    public Result<ProjectUploadSessionService.SessionView> cancel(@PathVariable String sessionId,
                                                                  Authentication auth) {
        try {
            return Result.success(uploadSessions.cancel(studentId(auth), sessionId));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private Integer studentId(Authentication auth) {
        return Integer.parseInt(auth.getName());
    }

    public static class UploadStartRequest {
        private String fileName;
        private Long size;
        private String projectName;
        private Boolean skipDependencyDirectories;

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public Long getSize() {
            return size;
        }

        public void setSize(Long size) {
            this.size = size;
        }

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public Boolean getSkipDependencyDirectories() {
            return skipDependencyDirectories;
        }

        public void setSkipDependencyDirectories(Boolean skipDependencyDirectories) {
            this.skipDependencyDirectories = skipDependencyDirectories;
        }
    }
}
