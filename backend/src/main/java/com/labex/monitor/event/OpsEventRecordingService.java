package com.labex.monitor.event;

import com.labex.entity.OpsEvent;
import com.labex.mapper.OpsEventMapper;
import org.springframework.stereotype.Service;

/** 运维事件记录：告警、故障、受控操作、应用启动等统一落库，供事件流与故障时间线投影。 */
@Service
public class OpsEventRecordingService {

    private final OpsEventMapper eventMapper;

    public OpsEventRecordingService(OpsEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    public OpsEvent record(String eventType, String severity, String source, String targetType,
                           String targetId, String message, String detail, String operator) {
        OpsEvent event = new OpsEvent();
        event.setEventType(eventType);
        event.setSeverity(severity == null || severity.isBlank() ? "info" : severity);
        event.setSource(truncate(source, 64));
        event.setTargetType(truncate(targetType, 32));
        event.setTargetId(truncate(targetId, 64));
        event.setMessage(truncate(message, 1024));
        event.setDetail(truncate(detail, 8192));
        event.setOperator(truncate(operator, 160));
        eventMapper.insert(event);
        return event;
    }

    private String truncate(String input, int max) {
        if (input == null) {
            return null;
        }
        return input.length() <= max ? input : input.substring(0, max);
    }
}