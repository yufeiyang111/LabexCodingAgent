package com.labex.labexagent.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CreatePlanTool
implements AgentTool {
    public ToolDefinition definition() {
        Map<String, Object> taskItem = Map.of("type", "object", "properties", Map.of("title", Map.of("type", "string", "description", "\u4efb\u52a1\u6807\u9898"), "description", Map.of("type", "string", "description", "\u4efb\u52a1\u63cf\u8ff0")), "required", List.of("title"));
        return ToolDefinition.builder().name("create_plan").description("\u521b\u5efa/\u7ba1\u7406\u4efb\u52a1\u8ba1\u5212\u3002\u521b\u5efa\u8ba1\u5212\u540e\u6309\u987a\u5e8f\u6267\u884c\uff0c\u6bcf\u5b8c\u6210\u4e00\u6b65\u6807\u8bb0\u5b8c\u6210\u3002").stringProperty("action", "\u64cd\u4f5c\u7c7b\u578b: create\uff08\u521b\u5efa\uff09, complete\uff08\u6807\u8bb0\u5b8c\u6210\uff09, update\uff08\u66f4\u65b0\uff09", false).arrayProperty("tasks", "\u4efb\u52a1\u5217\u8868\uff08create\u65f6\u5fc5\u586b\uff09", taskItem, false).intProperty("task_index", "\u4efb\u52a1\u5e8f\u53f7\uff08complete\u65f6\u5fc5\u586b\uff0c\u4ece1\u5f00\u59cb\uff09", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String action;
        String string = action = args.has("action") ? args.get("action").getAsString() : "create";
        if ("complete".equalsIgnoreCase(action)) {
            return this.completeTask(context, args);
        }
        if ("update".equalsIgnoreCase(action)) {
            return this.updatePlan(context, args);
        }
        return this.createPlan(context, args);
    }

    private ToolResult createPlan(AgentContext context, JsonObject args) {
        if (!args.has("tasks") || !args.get("tasks").isJsonArray()) {
            return ToolResult.failed("tasks \u53c2\u6570\u5fc5\u987b\u662f\u6570\u7ec4");
        }
        JsonArray tasksArray = args.getAsJsonArray("tasks");
        if (tasksArray.size() == 0) {
            return ToolResult.failed("\u8ba1\u5212\u81f3\u5c11\u9700\u8981\u4e00\u4e2a\u4efb\u52a1");
        }
        if (tasksArray.size() > 30) {
            return ToolResult.failed("\u8ba1\u5212\u6700\u591a30\u4e2a\u4efb\u52a1");
        }
        ArrayList<AgentContext.PlanItem> planItems = new ArrayList<AgentContext.PlanItem>();
        StringBuilder summary = new StringBuilder("\u5df2\u521b\u5efa\u6267\u884c\u8ba1\u5212:\n");
        for (int i = 0; i < tasksArray.size(); ++i) {
            JsonElement el = tasksArray.get(i);
            if (!el.isJsonObject()) continue;
            JsonObject task = el.getAsJsonObject();
            Object title = task.has("title") ? task.get("title").getAsString() : "\u4efb\u52a1 " + (i + 1);
            String desc = task.has("description") ? task.get("description").getAsString() : "";
            planItems.add(new AgentContext.PlanItem((String)title, desc, false));
            summary.append(i + 1).append(". ").append((String)title);
            if (!desc.isBlank()) {
                summary.append(" - ").append(desc);
            }
            summary.append("\n");
        }
        context.setPlan(planItems);
        context.setCurrentPlanIndex(0);
        summary.append("\n\u8bf7\u6309\u987a\u5e8f\u6267\u884c\u6bcf\u4e2a\u4efb\u52a1\u3002\u5b8c\u6210\u4e00\u4e2a\u4efb\u52a1\u540e\u8c03\u7528 create_plan(action=\"complete\", task_index=N) \u6807\u8bb0\u5b8c\u6210\u3002");
        return ToolResult.ok((String)summary.toString());
    }

    private ToolResult completeTask(AgentContext context, JsonObject args) {
        if (!args.has("task_index")) {
            return ToolResult.failed("task_index \u53c2\u6570\u5fc5\u586b");
        }
        int index = args.get("task_index").getAsInt() - 1;
        List<AgentContext.PlanItem> plan = context.getPlan();
        if (plan == null || plan.isEmpty()) {
            return ToolResult.failed("\u5f53\u524d\u6ca1\u6709\u6267\u884c\u8ba1\u5212\uff0c\u8bf7\u5148\u521b\u5efa\u8ba1\u5212");
        }
        if (index < 0 || index >= plan.size()) {
            return ToolResult.failed((String)("task_index \u8d85\u51fa\u8303\u56f4\uff0c\u6709\u6548\u8303\u56f4: 1-" + plan.size()));
        }
        ((AgentContext.PlanItem)plan.get(index)).setCompleted(true);
        int nextIndex = this.findNextTask(plan);
        context.setCurrentPlanIndex(nextIndex);
        StringBuilder summary = new StringBuilder();
        summary.append("\u2705 \u4efb\u52a1 ").append(index + 1).append(" \u5df2\u5b8c\u6210: ").append(((AgentContext.PlanItem)plan.get(index)).getTitle()).append("\n\n");
        summary.append(context.getPlanSummary());
        if (nextIndex >= 0) {
            summary.append("\n\u4e0b\u4e00\u6b65\u8bf7\u6267\u884c\u4efb\u52a1 ").append(nextIndex + 1).append(": ").append(((AgentContext.PlanItem)plan.get(nextIndex)).getTitle());
        } else {
            summary.append("\n\ud83c\udf89 \u6240\u6709\u4efb\u52a1\u5df2\u5b8c\u6210\uff01\u8bf7\u7ed9\u51fa\u6700\u7ec8\u603b\u7ed3\u56de\u590d\u3002");
        }
        return ToolResult.ok((String)summary.toString());
    }

    private ToolResult updatePlan(AgentContext context, JsonObject args) {
        int index;
        List<AgentContext.PlanItem> plan = context.getPlan();
        if (plan == null || plan.isEmpty()) {
            return ToolResult.failed("\u5f53\u524d\u6ca1\u6709\u6267\u884c\u8ba1\u5212\uff0c\u8bf7\u5148\u521b\u5efa\u8ba1\u5212");
        }
        if (args.has("task_index") && args.has("title") && (index = args.get("task_index").getAsInt() - 1) >= 0 && index < plan.size()) {
            ((AgentContext.PlanItem)plan.get(index)).setTitle(args.get("title").getAsString());
            if (args.has("description")) {
                ((AgentContext.PlanItem)plan.get(index)).setDescription(args.get("description").getAsString());
            }
            return ToolResult.ok((String)("\u5df2\u66f4\u65b0\u4efb\u52a1 " + (index + 1) + "\n\n" + context.getPlanSummary()));
        }
        return ToolResult.ok((String)("\u5f53\u524d\u8ba1\u5212:\n" + context.getPlanSummary()));
    }

    private int findNextTask(List<AgentContext.PlanItem> plan) {
        for (int i = 0; i < plan.size(); ++i) {
            if (plan.get(i).isCompleted()) continue;
            return i;
        }
        return -1;
    }
}

