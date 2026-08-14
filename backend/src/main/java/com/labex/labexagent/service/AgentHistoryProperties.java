package com.labex.labexagent.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 历史页加载预算：控制单页返回的事件总数与 Part 输出长度，
 * 避免长任务（大量 tool call）把整段运行记录全量灌回前端。
 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.history")
public class AgentHistoryProperties {

    /** 单页历史最多返回的 run event 总数（跨任务累计，超出部分留给更早的页）。 */
    private int maxPageEventsBudget = 1_000;

    /** 历史页 Part 工具输出截断长度（字符）；超出部分以截断标记返回，完整内容仅保留在持久化层。 */
    private int maxPartOutputChars = 4_000;

    public int getMaxPageEventsBudget() {
        return maxPageEventsBudget;
    }

    public void setMaxPageEventsBudget(int maxPageEventsBudget) {
        this.maxPageEventsBudget = maxPageEventsBudget;
    }

    public int getMaxPartOutputChars() {
        return maxPartOutputChars;
    }

    public void setMaxPartOutputChars(int maxPartOutputChars) {
        this.maxPartOutputChars = maxPartOutputChars;
    }
}
