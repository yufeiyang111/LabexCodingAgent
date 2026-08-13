package com.labex.labexagent.lsp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Eagerly starts language server sessions for recently active projects after application startup.
 * Runs on a dedicated background pool so prewarming never blocks Agent sessions or startup.
 */
@Service
public class LspPrewarmService {
    private static final Logger log = LoggerFactory.getLogger(LspPrewarmService.class);
    private static final ExecutorService PREWARM_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "lsp-prewarm");
        thread.setDaemon(true);
        return thread;
    });
    private static final int MAX_SCAN_FILES = 300;
    private static final int MAX_SCAN_DEPTH = 3;
    private static final int MAX_PROJECTS_LIMIT = 10;

    private final LspSessionManager lspSessionManager;
    private final StudentProjectService studentProjectService;

    @Value("${labex-agent.lsp.prewarm.enabled:true}")
    private boolean enabled;

    @Value("${labex-agent.lsp.prewarm.max-projects:3}")
    private int maxProjects;

    public LspPrewarmService(LspSessionManager lspSessionManager, StudentProjectService studentProjectService) {
        this.lspSessionManager = lspSessionManager;
        this.studentProjectService = studentProjectService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prewarmAfterStartup() {
        if (!enabled) {
            return;
        }
        List<StudentProject> projects = recentProjects();
        if (projects == null || projects.isEmpty()) {
            return;
        }
        for (StudentProject project : projects) {
            PREWARM_EXECUTOR.execute(() -> prewarmProject(project));
        }
    }

    private List<StudentProject> recentProjects() {
        try {
            int limit = Math.max(1, Math.min(maxProjects, MAX_PROJECTS_LIMIT));
            return studentProjectService.list(new LambdaQueryWrapper<StudentProject>()
                    .orderByDesc(StudentProject::getUpdateTime)
                    .last("LIMIT " + limit));
        } catch (RuntimeException failure) {
            log.warn("LSP_PREWARM_PROJECT_LIST_FAILED reason={}", failure.getMessage());
            return List.of();
        }
    }

    void prewarmProject(StudentProject project) {
        if (project == null || project.getProjectId() == null) {
            return;
        }
        try {
            Path root = ProjectWorkspace.paths(project).workspaceRoot();
            if (!Files.isDirectory(root)) {
                return;
            }
            for (String languageId : detectLanguages(root)) {
                lspSessionManager.prewarm(root, languageId);
            }
        } catch (Exception failure) {
            log.debug("LSP_PREWARM_PROJECT_SKIPPED projectId={} reason={}",
                    project.getProjectId(), failure.getMessage());
        }
    }

    private Set<String> detectLanguages(Path root) {
        Set<String> languages = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH)) {
            stream.limit(MAX_SCAN_FILES).forEach(path -> {
                String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
                if (name.endsWith(".java")) {
                    languages.add("java");
                } else if (name.endsWith(".vue")) {
                    languages.add("vue");
                } else if (name.endsWith(".js") || name.endsWith(".jsx")
                        || name.endsWith(".ts") || name.endsWith(".tsx")) {
                    languages.add("typescript");
                } else if (name.endsWith(".py")) {
                    languages.add("python");
                }
            });
        } catch (IOException failure) {
            log.debug("LSP_PREWARM_SCAN_FAILED root={} reason={}", root, failure.getMessage());
        }
        return languages;
    }
}
