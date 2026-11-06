package com.labex.controller.student;

import com.labex.entity.StudentProject;
import com.labex.labexagent.git.ProjectGitCloneTokenService;
import com.labex.labexagent.git.ProjectGitHttpBackend;
import com.labex.service.StudentProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * git 客户端访问的只读端点：{@code /git/{projectId}.git}。
 *
 * <p>{@code git clone} 会先 {@code GET /info/refs?service=git-upload-pack}，再
 * {@code POST /git-upload-pack}，两者都由 {@code git http-backend} 处理。
 *
 * <p>鉴权用 URL 里的短期令牌（用户名任意、密码是令牌）：git 客户端无法附加 JWT 头。
 * 令牌由 {@link ProjectGitCloneTokenService} 签发，只读、限定本项目、默认一小时过期。
 * 这个路径在 {@code SecurityConfig} 里放行，安全性完全由令牌承担。
 */
@RestController
public class ProjectGitHttpController {
    private static final Logger log = LoggerFactory.getLogger(ProjectGitHttpController.class);

    private final ProjectGitCloneTokenService cloneTokens;
    private final ProjectGitHttpBackend httpBackend;
    private final StudentProjectService studentProjectService;

    public ProjectGitHttpController(ProjectGitCloneTokenService cloneTokens,
                                    ProjectGitHttpBackend httpBackend,
                                    StudentProjectService studentProjectService) {
        this.cloneTokens = cloneTokens;
        this.httpBackend = httpBackend;
        this.studentProjectService = studentProjectService;
    }

    @RequestMapping(value = {"/git/{projectId}.git", "/git/{projectId}.git/**"})
    public void handle(@PathVariable Integer projectId,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        String token = basicAuthPassword(request);
        if (!cloneTokens.verify(projectId, token)) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"labex-git\"");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        StudentProject project = studentProjectService.getById(projectId);
        if (project == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String pathInfo = pathInfo(request, projectId);
        log.debug("GIT_HTTP projectId={} method={} path={} query={}",
                projectId, request.getMethod(), pathInfo, request.getQueryString());
        httpBackend.handle(project, pathInfo, request.getQueryString(), request, response);
    }

    /** 从 servletPath / requestURI 里取出仓库之后的部分，例如 {@code /info/refs}。 */
    private String pathInfo(HttpServletRequest request, Integer projectId) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            return "/";
        }
        String marker = "/git/" + projectId + ".git";
        int index = uri.indexOf(marker);
        if (index < 0) {
            return "/";
        }
        String suffix = uri.substring(index + marker.length());
        return suffix.isBlank() ? "/" : suffix;
    }

    private String basicAuthPassword(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon < 0 ? decoded : decoded.substring(colon + 1);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
