package com.labex.labexagent.runtime;

import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.tool.ToolResult;
import org.springframework.stereotype.Service;

@Service
public final class AgentInteractionPauser {
    private final AgentTaskService taskService;

    public AgentInteractionPauser(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    public Pause pause(Long taskId, ToolResult result, String language) {
        boolean permission = result != null && "permission".equals(result.getInteractionType());
        String state = permission ? "waiting_approval" : "waiting_user";
        String title = local(language, permission ? "等待用户批准" : "等待用户输入",
                permission ? "Awaiting approval" : "Awaiting user input");
        String detail = local(language, permission ? "继续执行前需要用户批准。" : "继续执行前需要用户输入。",
                permission ? "User approval is required before execution can continue."
                        : "User input is required before execution can continue.");
        taskService.updateTask(taskId, state, title, detail);
        return new Pause(state, title, detail);
    }

    private String local(String language, String zh, String en) {
        return "en".equalsIgnoreCase(language) ? en : zh;
    }

    public record Pause(String state, String title, String detail) {}
}
