package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentRunEvent;
import com.labex.labexagent.runtime.ToolExposureSnapshot;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.mapper.AgentRunEventMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 从权威 AgentRunEvent 恢复本 task 的工具暴露快照。 */
@Service
public class AgentToolExposureSnapshotService {
    private static final Gson GSON = new Gson();
    private final AgentRunEventMapper eventMapper;

    public AgentToolExposureSnapshotService(AgentRunEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    public Optional<ToolExposureSnapshot> findLatest(Long taskId, AgentRuntimeProfile profile, String mode) {
        if (taskId == null || taskId <= 0L) {
            return Optional.empty();
        }
        List<AgentRunEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AgentRunEvent>()
                .eq(AgentRunEvent::getTaskId, taskId)
                .eq(AgentRunEvent::getEventType, "TOOL_EXPOSURE")
                .orderByDesc(AgentRunEvent::getSequenceNumber)
                .last("LIMIT 20"));
        for (AgentRunEvent event : events == null ? List.<AgentRunEvent>of() : events) {
            Optional<ToolExposureSnapshot> snapshot = parse(event == null ? null : event.getPayload());
            if (snapshot.isPresent() && snapshot.orElseThrow().matches(profile, mode)) {
                return snapshot;
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<ToolExposureSnapshot> parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            Object decoded = GSON.fromJson(payload, Object.class);
            if (!(decoded instanceof Map<?, ?> map)) {
                return Optional.empty();
            }
            return ToolExposureSnapshot.fromPayload((Map<String, ?>) map);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }
}
