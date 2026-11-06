package com.labex.controller.student;

import com.labex.common.Result;
import com.labex.entity.StudentProject;
import com.labex.labexagent.git.ProjectGitCloneTokenService;
import com.labex.labexagent.git.ProjectGitDtos;
import com.labex.labexagent.git.ProjectGitService;
import com.labex.service.StudentProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 源码管理面板的后端接口：未提交改动、暂存、提交、历史、diff、回滚、clone 地址。
 *
 * <p>这里只有"用户的 git"，没有 Agent 概念。Agent 改动的 diff/undo 走
 * {@code /agent/changes} 与 {@code /diff/**}，两套系统在 API 层就分开。
 *
 * <p>controller 只做参数装配与错误映射；权限、路径边界、索引隔离都在
 * {@link ProjectGitService} 里。
 */
@RestController
@RequestMapping(value = {"/student/projects"})
public class ProjectGitController {
    private static final Logger log = LoggerFactory.getLogger(ProjectGitController.class);

    private final ProjectGitService gitService;
    private final ProjectGitCloneTokenService cloneTokens;
    private final StudentProjectService studentProjectService;

    @Value("${labex-agent.project-git.public-base-url:}")
    private String configuredPublicBaseUrl;

    public ProjectGitController(ProjectGitService gitService,
                                ProjectGitCloneTokenService cloneTokens,
                                StudentProjectService studentProjectService) {
        this.gitService = gitService;
        this.cloneTokens = cloneTokens;
        this.studentProjectService = studentProjectService;
    }

    @GetMapping(value = {"/{projectId}/git/status"})
    public Result<ProjectGitDtos.StatusView> status(@PathVariable Integer projectId, Authentication auth) {
        try {
            return Result.success(gitService.status(ownedProject(projectId, auth)));
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

    @PostMapping(value = {"/{projectId}/git/stage"})
    public Result<ProjectGitDtos.StatusView> stage(@PathVariable Integer projectId,
                                                   @RequestBody(required = false) StageRequest request,
                                                   Authentication auth) {
        try {
            StudentProject project = ownedProject(projectId, auth);
            gitService.stage(project, request == null ? java.util.List.of() : request.paths());
            return Result.success(gitService.status(project));
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

    @PostMapping(value = {"/{projectId}/git/commit"})
    public Result<ProjectGitDtos.CommitResult> commit(@PathVariable Integer projectId,
                                                      @RequestBody(required = false) CommitRequest request,
                                                      Authentication auth) {
        try {
            return Result.success(gitService.commit(ownedProject(projectId, auth),
                    request == null ? null : request.message()));
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

    @GetMapping(value = {"/{projectId}/git/commits"})
    public Result<ProjectGitDtos.CommitPage> commits(@PathVariable Integer projectId,
                                                     @RequestParam(required = false) String before,
                                                     @RequestParam(required = false) Integer limit,
                                                     Authentication auth) {
        try {
            return Result.success(gitService.commits(ownedProject(projectId, auth), before, limit));
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

    @GetMapping(value = {"/{projectId}/git/commits/{sha}"})
    public Result<ProjectGitDtos.CommitDetail> commitDetail(@PathVariable Integer projectId,
                                                            @PathVariable String sha,
                                                            Authentication auth) {
        try {
            return Result.success(gitService.commitDetail(ownedProject(projectId, auth), sha));
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

    @GetMapping(value = {"/{projectId}/git/commits/{sha}/diff"})
    public Result<java.util.Map<String, String>> commitDiff(@PathVariable Integer projectId,
                                                            @PathVariable String sha,
                                                            @RequestParam(required = false) String path,
                                                            Authentication auth) {
        try {
            String diff = gitService.commitDiff(ownedProject(projectId, auth), sha, path);
            return Result.success(java.util.Map.of("diff", diff));
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

    @GetMapping(value = {"/{projectId}/git/diff"})
    public Result<java.util.Map<String, String>> workingDiff(@PathVariable Integer projectId,
                                                             @RequestParam String path,
                                                             Authentication auth) {
        try {
            return Result.success(java.util.Map.of("diff",
                    gitService.workingDiff(ownedProject(projectId, auth), path)));
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

    @PostMapping(value = {"/{projectId}/git/revert"})
    public Result<ProjectGitDtos.CommitResult> revert(@PathVariable Integer projectId,
                                                      @RequestBody(required = false) RevertRequest request,
                                                      Authentication auth) {
        try {
            if (request == null || request.sha() == null || request.sha().isBlank()) {
                return Result.error("请指定要回滚到的提交");
            }
            return Result.success(gitService.revert(ownedProject(projectId, auth), request.sha()));
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

    /** 生成带短期令牌的 clone 地址。令牌只读、限定本项目、默认一小时过期。 */
    @GetMapping(value = {"/{projectId}/git/clone-url"})
    public Result<ProjectGitDtos.CloneUrl> cloneUrl(@PathVariable Integer projectId,
                                                    Authentication auth,
                                                    HttpServletRequest request) {
        try {
            ownedProject(projectId, auth);
            return Result.success(cloneTokens.issue(projectId, publicBaseUrl(request)));
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

    private String publicBaseUrl(HttpServletRequest request) {
        if (configuredPublicBaseUrl != null && !configuredPublicBaseUrl.isBlank()) {
            return configuredPublicBaseUrl.trim();
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String scheme = forwardedProto == null || forwardedProto.isBlank() ? request.getScheme() : forwardedProto;
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        String host = forwardedHost == null || forwardedHost.isBlank() ? request.getHeader("Host") : forwardedHost;
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        // clone 端点挂在根路径 /git/{id}.git 上，不在 /api 之下：git 客户端不会走前端代理。
        String contextPath = request.getContextPath();
        String base = scheme + "://" + host;
        if (contextPath != null && !contextPath.isBlank() && !"/".equals(contextPath)) {
            base = base + contextPath;
        }
        return base;
    }

    private StudentProject ownedProject(Integer projectId, Authentication auth) {
        return studentProjectService.getOwnedProject(Integer.parseInt(auth.getName()), projectId);
    }

    private <T> Result<T> errorResult(RuntimeException e) {
        String message = e.getMessage();
        return Result.error(message == null || message.isBlank() ? "版本库操作失败" : message);
    }

    public record StageRequest(java.util.List<String> paths) {
    }

    public record CommitRequest(String message) {
    }

    public record RevertRequest(String sha) {
    }
}
