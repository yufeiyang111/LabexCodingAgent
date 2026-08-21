package com.labex.monitor.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.OpsEvent;
import com.labex.mapper.OpsEventMapper;
import com.labex.monitor.dto.EventDto;
import java.util.List;
import org.springframework.stereotype.Service;

/** 事件流查询：类型/严重度/目标过滤 + 分页。 */
@Service
public class OpsEventQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OpsEventMapper eventMapper;

    public OpsEventQueryService(OpsEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    public List<EventDto> query(int page, int pageSize, String eventType, String severity,
                                String targetType, String targetId) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        LambdaQueryWrapper<OpsEvent> wrapper = new LambdaQueryWrapper<OpsEvent>()
                .eq(eventType != null && !eventType.isBlank(), OpsEvent::getEventType, eventType)
                .eq(severity != null && !severity.isBlank(), OpsEvent::getSeverity, severity)
                .eq(targetType != null && !targetType.isBlank(), OpsEvent::getTargetType, targetType)
                .eq(targetId != null && !targetId.isBlank(), OpsEvent::getTargetId, targetId)
                .orderByDesc(OpsEvent::getCreateTime);
        Page<OpsEvent> pageResult = eventMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return pageResult.getRecords().stream().map(this::toDto).toList();
    }

    public long count(String eventType, String severity, String targetType, String targetId) {
        LambdaQueryWrapper<OpsEvent> wrapper = new LambdaQueryWrapper<OpsEvent>()
                .eq(eventType != null && !eventType.isBlank(), OpsEvent::getEventType, eventType)
                .eq(severity != null && !severity.isBlank(), OpsEvent::getSeverity, severity)
                .eq(targetType != null && !targetType.isBlank(), OpsEvent::getTargetType, targetType)
                .eq(targetId != null && !targetId.isBlank(), OpsEvent::getTargetId, targetId);
        return eventMapper.selectCount(wrapper);
    }

    private EventDto toDto(OpsEvent event) {
        EventDto dto = new EventDto();
        dto.setEventId(event.getEventId());
        dto.setEventType(event.getEventType());
        dto.setSeverity(event.getSeverity());
        dto.setSource(event.getSource());
        dto.setTargetType(event.getTargetType());
        dto.setTargetId(event.getTargetId());
        dto.setMessage(event.getMessage());
        dto.setDetail(event.getDetail());
        dto.setOperator(event.getOperator());
        dto.setCreateTime(event.getCreateTime());
        return dto;
    }
}