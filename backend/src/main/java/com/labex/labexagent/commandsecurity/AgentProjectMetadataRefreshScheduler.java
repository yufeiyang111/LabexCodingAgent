package com.labex.labexagent.commandsecurity;

import com.labex.service.StudentProjectService;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Coalesces non-critical project metadata refreshes so a large generated workspace cannot block
 * an Agent edit or a one-time command approval from resuming the durable task.
 */
@Service
public class AgentProjectMetadataRefreshScheduler {
    private static final Logger log = LoggerFactory.getLogger(AgentProjectMetadataRefreshScheduler.class);

    private final StudentProjectService projectService;
    private final ExecutorService executor;
    private final Set<String> pendingProjects = ConcurrentHashMap.newKeySet();

    @Autowired
    public AgentProjectMetadataRefreshScheduler(StudentProjectService projectService) {
        this(projectService, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "labex-project-metadata-refresh");
            thread.setDaemon(true);
            return thread;
        }));
    }

    AgentProjectMetadataRefreshScheduler(StudentProjectService projectService, ExecutorService executor) {
        this.projectService = projectService;
        this.executor = executor;
    }

    public void schedule(Integer studentId, Integer projectId, String reason) {
        if (studentId == null || projectId == null) {
            return;
        }
        String key = studentId + ":" + projectId;
        if (!pendingProjects.add(key)) {
            log.info("PROJECT_METADATA_REFRESH_COALESCED studentId={} projectId={} reason={}", studentId, projectId, reason);
            return;
        }
        log.info("PROJECT_METADATA_REFRESH_QUEUED studentId={} projectId={} reason={}", studentId, projectId, reason);
        try {
            executor.execute(() -> refresh(key, studentId, projectId, reason));
        } catch (RuntimeException schedulingFailure) {
            pendingProjects.remove(key);
            log.warn("PROJECT_METADATA_REFRESH_QUEUE_FAILED studentId={} projectId={} reason={} errorType={}",
                    studentId, projectId, reason, schedulingFailure.getClass().getSimpleName());
        }
    }

    private void refresh(String key, Integer studentId, Integer projectId, String reason) {
        long startedNanos = System.nanoTime();
        try {
            log.info("PROJECT_METADATA_REFRESH_START studentId={} projectId={} reason={}", studentId, projectId, reason);
            projectService.refreshProjectMetadata(studentId, projectId);
            log.info("PROJECT_METADATA_REFRESH_COMPLETE studentId={} projectId={} reason={} elapsedMs={}",
                    studentId, projectId, reason, elapsedMs(startedNanos));
        } catch (Exception failure) {
            log.warn("PROJECT_METADATA_REFRESH_FAILED studentId={} projectId={} reason={} elapsedMs={} errorType={} error={}",
                    studentId, projectId, reason, elapsedMs(startedNanos), failure.getClass().getSimpleName(), failure.getMessage());
        } finally {
            pendingProjects.remove(key);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
