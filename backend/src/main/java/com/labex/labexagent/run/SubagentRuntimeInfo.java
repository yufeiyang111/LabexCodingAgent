package com.labex.labexagent.run;

import com.labex.entity.AgentSubagent;

/** 引擎运行期为子代理任务解析出的类型/深度快照；全部来自持久化行，非请求输入。 */
public record SubagentRuntimeInfo(AgentSubagent row, SubagentType type, int depth, int maxDepth) {

    public SubagentRuntimeInfo {
        type = type == null ? SubagentType.GENERAL : type;
        depth = Math.max(1, depth);
        maxDepth = Math.max(1, maxDepth);
    }

    public boolean readOnly() {
        return type.readOnly();
    }

    public boolean canSpawnNested() {
        return depth < maxDepth;
    }

    public static SubagentRuntimeInfo from(AgentSubagent row, int maxDepth) {
        SubagentType type = SubagentType.parse(row == null ? null : row.getAgentType());
        int depth = row == null || row.getSpawnDepth() == null ? 1 : row.getSpawnDepth();
        return new SubagentRuntimeInfo(row, type, depth, maxDepth);
    }
}
