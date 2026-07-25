package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class AgentRunEventReplayService {
    private static final long MAX_REPLAY_EVENTS = 1000L;

    private final AgentTaskMapper taskMapper;
    private final AgentRunEventMapper eventMapper;

    public AgentRunEventReplayService(AgentTaskMapper taskMapper, AgentRunEventMapper eventMapper) {
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
    }

    public List<AgentRunEvent> eventsAfter(Integer studentId, Integer projectId, Long taskId, long sequenceNumber) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null
                || !Objects.equals(task.getStudentId(), studentId)
                || !Objects.equals(task.getProjectId(), projectId)) {
            throw new IllegalArgumentException("Agent run not found");
        }
        long after = Math.max(0L, sequenceNumber);
        List<AgentRunEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AgentRunEvent>()
                .eq(AgentRunEvent::getTaskId, taskId)
                .gt(AgentRunEvent::getSequenceNumber, after)
                .orderByAsc(AgentRunEvent::getSequenceNumber)
                .last("LIMIT " + MAX_REPLAY_EVENTS));
        return events == null ? List.of() : events;
    }
}
