package com.labex.monitor.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.AgentModelConfigService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Agent 运行态只读查询用例。所有数据来自权威持久化状态（AgentTask / AgentRunEvent /
 * AgentRunInteraction），只读、不迁移、不建立第二套状态源。列表采用 MyBatis-Plus 分页，
 * 事件时间线采用 sequence 游标分页。
 */
@Service
public class AgentRuntimeQueryService {

    private final AgentTaskMapper taskMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentModelConfigService modelConfigService;
    private final AgentRunInteractionService interactionService;
    private final AgentRuntimeProjectionService projection;
    private final MonitorRuntimeProperties properties;

    public AgentRuntimeQueryService(AgentTaskMapper taskMapper,
                                    AgentRunEventMapper eventMapper,
                                    AgentModelConfigService modelConfigService,
                                    AgentRunInteractionService interactionService,
                                    AgentRuntimeProjectionService projection,
                                    MonitorRuntimeProperties properties) {
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
        this.modelConfigService = modelConfigService;
        this.interactionService = interactionService;
        this.projection = projection;
        this.properties = properties;
    }

    public AgentRuntimePage<AgentRuntimeTaskSummary> listTasks(AgentRuntimeQueryFilter filter) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<AgentTask> wrapper = new LambdaQueryWrapper<>();
        if (filter.status() != null && !filter.status().isBlank()) {
            wrapper.eq(AgentTask::getStatus, filter.status().trim().toLowerCase());
        }
        if (filter.studentId() != null) {
            wrapper.eq(AgentTask::getStudentId, filter.studentId());
        }
        if (filter.provider() != null && !filter.provider().isBlank()) {
            List<Integer> configIds = modelConfigService.listConfigIdsByProvider(filter.provider().trim());
            wrapper.in(AgentTask::getModelConfigId, configIds);
        }
        if (filter.overdueOnly()) {
            wrapper.and(w -> w.isNotNull(AgentTask::getExecutionLeaseExpiresAt)
                    .lt(AgentTask::getExecutionLeaseExpiresAt, now)
                    .notIn(AgentTask::getStatus, List.of("completed", "failed", "cancelled")));
        }
        wrapper.orderByDesc(AgentTask::getUpdateTime);

        Page<AgentTask> page = new Page<>(filter.page(), filter.pageSize());
        taskMapper.selectPage(page, wrapper);

        List<AgentTask> tasks = page.getRecords();
        Map<Integer, AgentModelConfig> configMap = resolveConfigs(tasks);
        List<AgentRuntimeTaskSummary> items = tasks.stream()
                .map(task -> projection.toSummary(
                        task,
                        providerOf(task, configMap),
                        modelNameOf(task, configMap),
                        null,
                        projection.isOverdue(task, now)))
                .collect(Collectors.toList());

        return new AgentRuntimePage<>(items, page.getCurrent(), page.getSize(), page.getTotal(),
                page.getCurrent() * page.getSize() < page.getTotal());
    }

    public AgentRuntimeTaskDetail taskDetail(Long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }
        AgentModelConfig config = task.getModelConfigId() == null ? null
                : modelConfigService.getById(task.getModelConfigId());
        AgentRunEvent latest = eventMapper.selectLatestByTaskId(taskId);
        AgentRunInteraction waiting = interactionService.findWaitingForTask(taskId);
        return projection.toDetail(task,
                config == null ? null : config.getProvider(),
                config == null ? null : config.getModelName(),
                latest, waiting,
                properties.getEventPayloadMaxChars(),
                properties.getSummaryMaxChars());
    }

    public AgentRuntimePage<AgentRuntimeEventItem> taskTimeline(Long taskId, Long afterSequence, int requestedLimit) {
        int limit = clampLimit(requestedLimit);
        LambdaQueryWrapper<AgentRunEvent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentRunEvent::getTaskId, taskId);
        if (afterSequence != null && afterSequence > 0) {
            wrapper.gt(AgentRunEvent::getSequenceNumber, afterSequence);
        }
        wrapper.orderByAsc(AgentRunEvent::getSequenceNumber)
                .last("LIMIT " + limit);

        List<AgentRunEvent> events = eventMapper.selectList(wrapper);
        List<AgentRuntimeEventItem> items = events.stream()
                .map(event -> projection.toEventItem(event, properties.getEventPayloadMaxChars()))
                .collect(Collectors.toList());
        long total = eventMapper.selectCount(new LambdaQueryWrapper<AgentRunEvent>()
                .eq(AgentRunEvent::getTaskId, taskId));
        boolean hasMore = items.size() >= limit;
        return new AgentRuntimePage<>(items, 0L, limit, total, hasMore);
    }

    private Map<Integer, AgentModelConfig> resolveConfigs(List<AgentTask> tasks) {
        Set<Integer> configIds = tasks.stream()
                .map(AgentTask::getModelConfigId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (configIds.isEmpty()) {
            return Map.of();
        }
        return modelConfigService.listByIds(configIds).stream()
                .collect(Collectors.toMap(AgentModelConfig::getConfigId, Function.identity(), (a, b) -> a));
    }

    private String providerOf(AgentTask task, Map<Integer, AgentModelConfig> configMap) {
        AgentModelConfig config = lookupConfig(task, configMap);
        return config == null ? null : config.getProvider();
    }

    private String modelNameOf(AgentTask task, Map<Integer, AgentModelConfig> configMap) {
        AgentModelConfig config = lookupConfig(task, configMap);
        return config == null ? null : config.getModelName();
    }

    private AgentModelConfig lookupConfig(AgentTask task, Map<Integer, AgentModelConfig> configMap) {
        if (task.getModelConfigId() == null) {
            return null;
        }
        return configMap.get(task.getModelConfigId());
    }

    private int clampLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return properties.getEventTimelineLimit();
        }
        return Math.min(requestedLimit, properties.getEventTimelineMaxLimit());
    }
}
