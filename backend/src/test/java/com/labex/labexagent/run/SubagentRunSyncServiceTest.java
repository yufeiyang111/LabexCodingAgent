package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentSubagent;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 回归测试：修复"子代理已完整执行、父任务却看到 queued 失败"的根因。
 * 子任务到达真终态时，finisher 必须把子代理行收束到终态并返回 true，
 * 父任务才能解除阻塞拿到最终报告。
 */
class SubagentRunSyncServiceTest {

    @Test
    void finalizesCompletedChildTaskWithFinalReport() {
        AgentSubagentService subagents = mock(AgentSubagentService.class);
        SubagentResultSummaryService summaries = mock(SubagentResultSummaryService.class);
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunMessageService runMessages = mock(AgentRunMessageService.class);

        AgentTask task = new AgentTask();
        task.setTaskId(99L);
        task.setStatus("completed");
        when(taskMapper.selectById(99L)).thenReturn(task);
        AgentSubagent row = new AgentSubagent();
        row.setSubagentId(7L);
        row.setStatus("running");
        when(subagents.findByChildTaskId(99L)).thenReturn(row);
        AgentRunMessage finalMessage = new AgentRunMessage();
        finalMessage.setMessageKey("assistant:final");
        finalMessage.setContent("## Findings\nall good");
        when(runMessages.history(99L)).thenReturn(List.of(finalMessage));

        SubagentRunSyncService service = new SubagentRunSyncService(subagents, summaries, taskMapper, runMessages);

        assertTrue(service.finalizeFromTask(99L), "completed child task must finalize the subagent row");
        verify(summaries).complete(row, "## Findings\nall good", true);
    }

    @Test
    void doesNotFinalizeRecoverableWaitingStates() {
        AgentSubagentService subagents = mock(AgentSubagentService.class);
        SubagentResultSummaryService summaries = mock(SubagentResultSummaryService.class);
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunMessageService runMessages = mock(AgentRunMessageService.class);

        AgentTask task = new AgentTask();
        task.setTaskId(100L);
        task.setStatus("waiting_approval");
        when(taskMapper.selectById(100L)).thenReturn(task);
        when(subagents.findByChildTaskId(100L)).thenReturn(new AgentSubagent());

        SubagentRunSyncService service = new SubagentRunSyncService(subagents, summaries, taskMapper, runMessages);

        assertFalse(service.finalizeFromTask(100L),
                "recoverable waiting state must keep the parent waiting, not fake a terminal state");
        verifyNoInteractions(summaries);
    }

    @Test
    void toleratesQueuedRowByAdvancingToRunningFirst() {
        AgentSubagentService subagents = mock(AgentSubagentService.class);
        SubagentResultSummaryService summaries = mock(SubagentResultSummaryService.class);
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunMessageService runMessages = mock(AgentRunMessageService.class);

        AgentTask task = new AgentTask();
        task.setTaskId(101L);
        task.setStatus("completed");
        when(taskMapper.selectById(101L)).thenReturn(task);
        AgentSubagent row = new AgentSubagent();
        row.setSubagentId(8L);
        row.setStatus("queued");
        when(subagents.findByChildTaskId(101L)).thenReturn(row);
        AgentRunMessage finalMessage = new AgentRunMessage();
        finalMessage.setMessageKey("assistant:final");
        finalMessage.setContent("ok");
        when(runMessages.history(101L)).thenReturn(List.of(finalMessage));

        SubagentRunSyncService service = new SubagentRunSyncService(subagents, summaries, taskMapper, runMessages);

        assertTrue(service.finalizeFromTask(101L));
        verify(subagents).transition(row, SubagentState.RUNNING);
        verify(summaries).complete(row, "ok", true);
    }

    @Test
    void treatsAlreadyTerminalRowAsFinalized() {
        AgentSubagentService subagents = mock(AgentSubagentService.class);
        SubagentResultSummaryService summaries = mock(SubagentResultSummaryService.class);
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunMessageService runMessages = mock(AgentRunMessageService.class);

        AgentTask task = new AgentTask();
        task.setTaskId(102L);
        task.setStatus("failed");
        when(taskMapper.selectById(102L)).thenReturn(task);
        AgentSubagent row = new AgentSubagent();
        row.setSubagentId(9L);
        row.setStatus("failed");
        when(subagents.findByChildTaskId(102L)).thenReturn(row);
        when(runMessages.history(102L)).thenReturn(List.of());
        // 行已处于终态：重复收束时状态机拒绝迁移，但父任务仍应解除阻塞（幂等）。
        org.mockito.Mockito.doThrow(new IllegalStateException("illegal subagent transition"))
                .when(summaries).complete(any(), any(), eq(false));

        SubagentRunSyncService service = new SubagentRunSyncService(subagents, summaries, taskMapper, runMessages);

        assertTrue(service.finalizeFromTask(102L));
        verify(summaries).complete(row, "Subagent run failed", false);
    }

    @Test
    void ignoresUnknownChildTask() {
        AgentSubagentService subagents = mock(AgentSubagentService.class);
        SubagentResultSummaryService summaries = mock(SubagentResultSummaryService.class);
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunMessageService runMessages = mock(AgentRunMessageService.class);
        when(taskMapper.selectById(999L)).thenReturn(null);

        SubagentRunSyncService service = new SubagentRunSyncService(subagents, summaries, taskMapper, runMessages);

        assertFalse(service.finalizeFromTask(999L));
        verifyNoInteractions(summaries);
    }
}