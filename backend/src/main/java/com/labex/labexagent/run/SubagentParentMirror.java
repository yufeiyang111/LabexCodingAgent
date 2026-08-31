package com.labex.labexagent.run;

import com.labex.entity.AgentSubagent;
import java.util.LinkedHashMap;
import java.util.Map;

/** 子代理→父任务派生事件 payload 构造器；字段形状是前端 reducer 依赖的稳定协议。 */
public final class SubagentParentMirror {

    private SubagentParentMirror() {
    }

    /** START 进度镜像：与旧 SubagentScheduler.appendParentProgress 完全同形。 */
    public static Map<String, Object> startPayload(AgentSubagent row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subagentId", row.getSubagentId());
        payload.put("identity", row.getIdentity());
        payload.put("eventSequence", 1L);
        payload.put("eventType", "START");
        payload.put("payload", row.getIdentity() == null ? "" : row.getIdentity());
        payload.put("status", row.getStatus());
        if (row.getParentToolCallId() != null && !row.getParentToolCallId().isBlank()) {
            payload.put("toolCallId", row.getParentToolCallId());
        }
        return payload;
    }

    public static String startKey(AgentSubagent row) {
        return "subagent-" + row.getSubagentId() + "-progress-1";
    }
}
