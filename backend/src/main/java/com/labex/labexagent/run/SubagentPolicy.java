package com.labex.labexagent.run;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SubagentPolicy {
    private final AgentSubagentProperties properties;

    public SubagentPolicy() {
        this(new AgentSubagentProperties());
    }

    @Autowired
    public SubagentPolicy(AgentSubagentProperties properties) {
        this.properties = properties == null ? new AgentSubagentProperties() : properties;
    }

    /**
     * 深度制嵌套校验：parentDepth 为主 Agent=0 的派生深度；达到上限后禁止继续派生。
     * 并行与预算约束保持不变。
     */
    public void validateSpawn(Integer parentDepth, int activeSiblings, int tokenBudget) {
        int effectiveDepth = parentDepth == null ? 0 : Math.max(0, parentDepth);
        if (effectiveDepth >= properties.getMaxSpawnDepth()) {
            throw new IllegalStateException("subagent nesting limit reached (max depth "
                    + properties.getMaxSpawnDepth() + ")");
        }
        if (activeSiblings >= properties.getMaxParallel()) throw new IllegalStateException("subagent parallelism limit reached");
        if (tokenBudget <= 0) throw new IllegalArgumentException("subagent token budget must be positive");
    }
}