package com.labex.labexagent.run;

import com.labex.entity.AgentSubagent;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentTaskMapper;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 子任务终态 → t_agent_subagent 行同步：状态迁移、最终报告提取、父级 SUBAGENT_SUMMARY 镜像
 * （复用 {@link SubagentResultSummaryService}，保持前端协议不变）。
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

    public void finalizeFromTask(Long childTaskId) {
        if (childTaskId == null || childTaskId <= 0) return;
        AgentTask task = this.taskMapper.selectById(childTaskId);
        AgentSubagent row = this.subagents.findByChildTaskId(childTaskId);
        if (task == null || row == null) return;

        String status = task.getStatus() == null ? "" : task.getStatus().toLowerCase(Locale.ROOT);
        boolean terminal = switch (status) {
            case "completed", "failed", "cancelled" -> true;
            default -> false;
        };
        if (!terminal) {
            // 租约丢失等非终态退出：保持行状态，由恢复路径接管；父级按当前状态投影失败。
            return;
        }
        boolean success = "completed".equals(status);
        String report = this.extractFinalReport(childTaskId);
        String summary = report == null || report.isBlank() ? defaultReason(status) : report;
        try {
            this.summaries.complete(row, summary, success);
        } catch (RuntimeException illegalTransition) {
            // 已终态的行不重复迁移。
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
