package com.labex.labexagent.controller;

import com.labex.common.Result;
import com.labex.entity.AgentSubagent;
import com.labex.entity.AgentTask;
import com.labex.labexagent.run.AgentSubagentEventService;
import com.labex.labexagent.run.AgentSubagentService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.mapper.AgentTaskMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 子代理只读查询：归属校验走父任务 (studentId, projectId)，事件回放复用子任务订阅链路。 */
@RestController
@RequestMapping("/student/projects/{projectId}/agent")
public class AgentSubagentController {

    private static final int MAX_REPLAY_EVENTS = 500;

    private final AgentTaskService taskService;
    private final AgentSubagentService subagentService;
    private final AgentSubagentEventService eventService;

    @Autowired(required = false)
    private AgentTaskMapper taskMapper;

    public AgentSubagentController(AgentTaskService taskService,
                                   AgentSubagentService subagentService,
                                   AgentSubagentEventService eventService) {
        this.taskService = taskService;
        this.subagentService = subagentService;
        this.eventService = eventService;
    }

    @GetMapping("/tasks/{taskId}/subagents")
    public Result<List<Map<String, Object>>> listByParentTask(@PathVariable Integer projectId,
                                                              @PathVariable Long taskId,
                                                              Authentication auth) {
        Integer studentId = Integer.parseInt(auth.getName());
        if (this.taskService.getOwnedTask(studentId, projectId, taskId) == null) {
            return Result.error(404, "Agent task not found");
        }
        List<Map<String, Object>> payload = new ArrayList<>();
        for (AgentSubagent row : this.subagentService.listByParentTask(taskId)) {
            payload.add(publicRow(row, studentId, projectId));
        }
        return Result.success(payload);
    }

    @GetMapping("/subagents/{subagentId}")
    public Result<Map<String, Object>> detail(@PathVariable Integer projectId,
                                              @PathVariable Long subagentId,
                                              @RequestParam(required = false) Long afterSequence,
                                              Authentication auth) {
        Integer studentId = Integer.parseInt(auth.getName());
        AgentSubagent row = this.subagentService.findById(subagentId);
        if (row == null || !owned(studentId, projectId, row)) {
            return Result.error(404, "Agent subagent not found");
        }
        Map<String, Object> payload = publicRow(row, studentId, projectId);
        long after = afterSequence == null ? 0L : Math.max(0L, afterSequence);
        List<Object> events = new ArrayList<>();
        for (com.labex.entity.AgentSubagentEvent event : this.eventService.replay(subagentId, after)) {
            if (events.size() >= MAX_REPLAY_EVENTS) break;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventId", event.getEventId());
            item.put("sequence", event.getSequenceNo());
            item.put("type", event.getEventType());
            item.put("payload", event.getPayload() == null ? "" : event.getPayload());
            item.put("createdAt", event.getCreateTime());
            events.add(item);
        }
        payload.put("events", events);
        return Result.success(payload);
    }

    private boolean owned(Integer studentId, Integer projectId, AgentSubagent row) {
        AgentTask parent = this.taskService.getOwnedTask(studentId, projectId, row.getTaskId());
        return parent != null;
    }

    private Map<String, Object> publicRow(AgentSubagent row, Integer studentId, Integer projectId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subagentId", row.getSubagentId());
        payload.put("taskId", row.getTaskId());
        payload.put("childTaskId", row.getChildTaskId());
        String conversationId = null;
        if (row.getChildTaskId() != null) {
            AgentTask childTask = null;
            if (studentId != null && projectId != null) {
                childTask = this.taskService.getOwnedTask(studentId, projectId, row.getChildTaskId());
            }
            if (childTask == null && this.taskMapper != null) {
                childTask = this.taskMapper.selectById(row.getChildTaskId());
            }
            if (childTask != null) {
                conversationId = childTask.getConversationId();
            }
        }
        payload.put("conversationId", conversationId);
        payload.put("childConversationId", conversationId);
        payload.put("identity", row.getIdentity());
        payload.put("agentType", row.getAgentType());
        payload.put("status", row.getStatus());
        payload.put("spawnDepth", row.getSpawnDepth());
        payload.put("tokenBudget", row.getTokenBudget());
        payload.put("tokensUsed", row.getTokensUsed());
        payload.put("background", row.getBackground());
        payload.put("parentToolCallId", row.getParentToolCallId());
        payload.put("summary", row.getSummary() == null ? "" : row.getSummary());
        payload.put("createTime", row.getCreateTime());
        payload.put("updateTime", row.getUpdateTime());
        return payload;
    }
}
