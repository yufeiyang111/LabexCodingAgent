package com.labex.labexagent.run;

import com.labex.entity.AgentSubagent;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentTaskMapper;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 子任务终态 → t_agent_subagent 行同步：状态迁移、最终报告提取、父级 SUBAGENT_SUMMARY 镜像
 * （复用 {@link SubagentResultSummaryService}，保持前端协议不变）。
 *
 * <p>作为 {@link SubagentCompletionRegistry.TaskFinisher} 的唯一实现被
 * {@link SubagentLaunchService} 注册：子任务运行 finally 触发本类，只有子任务到达
 * 真终态（completed/failed/cancelled）时才迁移子代理行并返回 true；恢复型等待态
 * （waiting_approval / waiting_user / waiting_workspace / retry_backoff）返回 false，
 * 让父任务继续等待（前台超时后转后台，恢复路径接管）。返回值即"父任务能否解除阻塞"的
 * 权威信号，避免出现"子代理已跑完、父任务却看到 queued"这类状态撕裂。</p>
 */
@Service
public class SubagentRunSyncService {

    private final AgentSubagentService subagents;
    private final SubagentResultSummaryService summaries;
    private final AgentTaskMapper taskMapper;
    private final com.labex.labexagent.run.AgentRunMessageService runMessages;

    public SubagentRunSyncService(AgentSubagentService subagents,
                                  SubagentResultSummaryService summaries,
                                  AgentTaskMapper taskMapper,
                                  AgentRunMessageService runMessages) {
        this.subagents = subagents;
        this.summaries = summaries;
        this.taskMapper = taskMapper;
        this.runMessages = runMessages;
    }

    /**
     * 子任务运行结束时尝试收束子代理行。
     *
     * @param childTaskId 子代理的独立 AgentTask ID
     * @return true 表示已把子代理行迁移到真终态（父任务可解除阻塞）；
     *         false 表示子任务仍处于可恢复等待态（父任务继续等待或转后台）。
     */
    public boolean finalizeFromTask(Long childTaskId) {
        if (childTaskId == null || childTaskId <= 0) return false;
        AgentTask task = this.taskMapper.selectById(childTaskId);
        AgentSubagent row = this.subagents.findByChildTaskId(childTaskId);
        if (task == null || row == null) return false;

        String status = task.getStatus() == null ? "" : task.getStatus().toLowerCase(Locale.ROOT);
        boolean terminal = switch (status) {
            case "completed", "failed", "cancelled" -> true;
            default -> false;
        };
        if (!terminal) {
            // 租约丢失/等待审批等可恢复态：保持行状态，由恢复路径接管；父级继续等待。
            return false;
        }
        boolean success = "completed".equals(status);
        String report = this.extractFinalReport(childTaskId);
        String summary = report == null || report.isBlank() ? defaultReason(status) : report;
        try {
            // 防御：若派发后行状态未及时进入 running（旧版本遗留行），先补齐合法迁移。
            if (SubagentState.QUEUED.persisted().equalsIgnoreCase(row.getStatus())) {
                this.subagents.transition(row, SubagentState.RUNNING);
            }
            this.summaries.complete(row, summary, success);
            return true;
        } catch (RuntimeException illegalTransition) {
            // 行已处于终态（重复收束）时静默返回 true，父任务无需再等待。
            return SubagentState.COMPLETED.persisted().equalsIgnoreCase(row.getStatus())
                    || SubagentState.FAILED.persisted().equalsIgnoreCase(row.getStatus())
                    || SubagentState.CANCELLED.persisted().equalsIgnoreCase(row.getStatus());
        }
    }

    private String extractFinalReport(Long childTaskId) {
        try {
            return this.runMessages.history(childTaskId).stream()
                    .filter(message -> message != null && "assistant:final".equals(message.getMessageKey()))
                    .reduce((first, second) -> second)
                    .map(message -> message.getContent())
                    .orElse("");
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String defaultReason(String status) {
        return switch (status) {
            case "failed" -> "Subagent run failed";
            case "cancelled" -> "Subagent run was cancelled";
            default -> "Subagent ended in state " + status;
        };
    }
}
