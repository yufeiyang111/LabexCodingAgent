package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.workspace.ProjectCheckoutLeaseService;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.StudentProjectService;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Re-admits queued task segments after a conflicting project checkout is released. */
@Service
public class AgentWorkspaceAdmissionScheduler {
    private static final Logger log = LoggerFactory.getLogger(AgentWorkspaceAdmissionScheduler.class);
    private static final int BATCH_SIZE = 100;

    private final AgentTaskMapper taskMapper;
    private final AgentTaskService taskService;
    private final AgentLoopEngine agentLoopEngine;
    private final StudentProjectService studentProjectService;
    private final ProjectCheckoutLeaseService checkoutLeaseService;

    public AgentWorkspaceAdmissionScheduler(AgentTaskMapper taskMapper, AgentTaskService taskService,
                                            @Lazy AgentLoopEngine agentLoopEngine,
                                            StudentProjectService studentProjectService,
                                            ProjectCheckoutLeaseService checkoutLeaseService) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.agentLoopEngine = agentLoopEngine;
        this.studentProjectService = studentProjectService;
        this.checkoutLeaseService = checkoutLeaseService;
    }

    @Scheduled(fixedDelayString = "${labex-agent.workspace-admission-poll-interval-ms:1000}")
    public void resumeWaitingTasks() {
        List<AgentTask> tasks = taskMapper.selectList(new QueryWrapper<AgentTask>()
                .eq("status", AgentRunState.WAITING_WORKSPACE.persistedStatus())
                .orderByAsc("submitted_at")
                .last("LIMIT " + BATCH_SIZE));
        if (tasks == null) return;
        for (AgentTask task : tasks) {
            resume(task);
        }
    }

    boolean resume(AgentTask task) {
        if (task == null || task.getTaskId() == null || task.getStudentId() == null || task.getProjectId() == null
                || task.getConversationId() == null || task.getConversationId().isBlank()) return false;
        StudentProject project = studentProjectService.getOwnedProject(task.getStudentId(), task.getProjectId());
        if (project == null) return false;
        Path workspace = ProjectWorkspace.paths(project).workspaceRoot();
        if (task.getBackgroundWorktree() != null && !task.getBackgroundWorktree().isBlank()) {
            workspace = BackgroundRunWorkspaceResolver.resolve(workspace, task.getBackgroundWorktree());
        }
        if (!checkoutLeaseService.isAvailable(task.getProjectId(), workspace)) return false;
        if (!taskService.beginWorkspaceResume(task.getTaskId())) return false;
        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task,
                "The shared project checkout is available again. Reassess the current workspace before making further changes.");
        try {
            agentLoopEngine.resume(task.getStudentId(), task.getProjectId(), request, task.getTaskId(), true);
            return true;
        } catch (RuntimeException exception) {
            log.warn("Unable to enqueue workspace-waiting taskId={}; it will remain recoverable", task.getTaskId(), exception);
            taskService.waitForWorkspace(task.getTaskId(), "Waiting for project checkout",
                    "Agent queue rejected workspace continuation", null);
            return false;
        }
    }
}
