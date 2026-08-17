package com.labex.labexagent.run;

import org.springframework.stereotype.Component;

@Component
public class SubagentPolicy {
    public static final int MAX_PARALLEL_SUBAGENTS = 4;

    public void validateSpawn(Long parentSubagentId, int activeSiblings, int tokenBudget) {
        if (parentSubagentId != null) throw new IllegalStateException("nested subagent fan-out is disabled");
        if (activeSiblings >= MAX_PARALLEL_SUBAGENTS) throw new IllegalStateException("subagent parallelism limit reached");
        if (tokenBudget <= 0) throw new IllegalArgumentException("subagent token budget must be positive");
    }
}