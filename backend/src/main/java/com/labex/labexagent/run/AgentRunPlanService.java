package com.labex.labexagent.run;

import com.google.gson.Gson;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunPlanItem;
import com.labex.entity.AgentTask;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.mapper.AgentRunPlanItemMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 计划的数据库唯一事实源。
 *
 * <p>所有写入都先锁定 AgentTask 并校验 execution epoch，再整体替换有序快照；
 * PLAN_UPDATE 事件加入同一事务，当前 SSE 连接只负责投影该已提交事件。</p>
 */
@Service
public class AgentRunPlanService {
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";
    private static final int MAX_ITEMS = 30;
    private static final int MAX_TITLE_CHARS = 512;
    private static final int MAX_DESCRIPTION_CHARS = 20_000;
    private static final Gson GSON = new Gson();

    private final AgentRunPlanItemMapper planMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentRunLifecycleService lifecycleService;

    public AgentRunPlanService(AgentRunPlanItemMapper planMapper,
                               AgentTaskMapper taskMapper,
                               AgentRunLifecycleService lifecycleService) {
        this.planMapper = planMapper;
        this.taskMapper = taskMapper;
        this.lifecycleService = lifecycleService;
    }

    public Projection load(Long taskId) {
        requireTaskId(taskId);
        return project(taskId, orderedRows(taskId), 0L, "load");
    }

    /** 数据库优先；仅当数据库为空且存在 v1 seed 时执行一次性迁移。 */
    @Transactional(rollbackFor = Exception.class)
    public Projection restoreOrMigrate(Long taskId, long expectedExecutionEpoch, List<PlanDraft> legacyDrafts) {
        requireTaskId(taskId);
        List<AgentRunPlanItem> existing = orderedRows(taskId);
        if (!existing.isEmpty()) {
            return project(taskId, existing, 0L, "load");
        }
        if (legacyDrafts == null || legacyDrafts.isEmpty()) {
            return project(taskId, List.of(), 0L, "load");
        }
        AgentTask task = lockWritableTask(taskId, expectedExecutionEpoch);
        existing = orderedRows(taskId);
        if (!existing.isEmpty()) {
            return project(taskId, existing, 0L, "load");
        }
        return replaceLocked(task, expectedExecutionEpoch, 1L,
                normalizeDrafts(legacyDrafts), "checkpoint_v1_migration");
    }

    @Transactional(rollbackFor = Exception.class)
    public Projection replace(Long taskId, long expectedExecutionEpoch,
                              List<PlanDraft> drafts, String source) {
        AgentTask task = lockWritableTask(taskId, expectedExecutionEpoch);
        List<AgentRunPlanItem> existing = orderedRows(taskId);
        long nextRevision = nextRevision(existing);
        List<PlanDraft> normalized = normalizeDrafts(drafts);
        return replaceLocked(task, expectedExecutionEpoch, nextRevision, normalized, source);
    }

    @Transactional(rollbackFor = Exception.class)
    public Projection complete(Long taskId, long expectedExecutionEpoch, int index, String source) {
        AgentTask task = lockWritableTask(taskId, expectedExecutionEpoch);
        List<AgentRunPlanItem> existing = requireExistingPlan(taskId);
        if (index < 0 || index >= existing.size()) {
            throw new IllegalArgumentException("Plan item index is out of range: " + (index + 1));
        }
        List<PlanDraft> drafts = existing.stream()
                .map(item -> new PlanDraft(item.getTitle(), item.getDescription(),
                        STATUS_COMPLETED.equals(normalizeStatus(item.getStatus()))))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        PlanDraft current = drafts.get(index);
        drafts.set(index, new PlanDraft(current.title(), current.description(), true));
        return replaceLocked(task, expectedExecutionEpoch, nextRevision(existing), drafts, source);
    }

    @Transactional(rollbackFor = Exception.class)
    public Projection update(Long taskId, long expectedExecutionEpoch, int index,
                             String title, String description, String source) {
        AgentTask task = lockWritableTask(taskId, expectedExecutionEpoch);
        List<AgentRunPlanItem> existing = requireExistingPlan(taskId);
        if (index < 0 || index >= existing.size()) {
            throw new IllegalArgumentException("Plan item index is out of range: " + (index + 1));
        }
        List<PlanDraft> drafts = existing.stream()
                .map(item -> new PlanDraft(item.getTitle(), item.getDescription(),
                        STATUS_COMPLETED.equals(normalizeStatus(item.getStatus()))))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        PlanDraft current = drafts.get(index);
        drafts.set(index, new PlanDraft(title, description == null ? current.description() : description,
                current.completed()));
        return replaceLocked(task, expectedExecutionEpoch, nextRevision(existing), normalizeDrafts(drafts), source);
    }

    private Projection replaceLocked(AgentTask task, long executionEpoch, long revision,
                                     List<PlanDraft> drafts, String source) {
        List<PlanDraft> normalized = normalizeDrafts(drafts);
        LocalDateTime now = LocalDateTime.now();
        int currentIndex = firstIncomplete(normalized);
        planMapper.deleteByTaskId(task.getTaskId());
        List<AgentRunPlanItem> stored = new ArrayList<>(normalized.size());
        for (int position = 0; position < normalized.size(); position++) {
            PlanDraft draft = normalized.get(position);
            AgentRunPlanItem row = new AgentRunPlanItem();
            row.setTaskId(task.getTaskId());
            row.setExecutionEpoch(executionEpoch);
            row.setPlanRevision(revision);
            row.setPosition(position);
            row.setTitle(draft.title());
            row.setDescription(draft.description());
            row.setStatus(draft.completed() ? STATUS_COMPLETED
                    : (position == currentIndex ? STATUS_IN_PROGRESS : STATUS_PENDING));
            row.setCreateTime(now);
            row.setUpdateTime(now);
            if (planMapper.insertPlanItem(row) != 1) {
                throw new IllegalStateException("Unable to persist Agent plan item at position " + position);
            }
            stored.add(row);
        }
        Projection pendingEvent = project(task.getTaskId(), stored, 0L, safeSource(source));
        AgentRunEvent event = lifecycleService.appendEvent(task.getTaskId(), "PLAN_UPDATE",
                pendingEvent.eventPayload(), eventIdempotencyKey(task.getTaskId(), revision));
        if (event == null || event.getSequenceNumber() == null) {
            throw new IllegalStateException("Unable to persist PLAN_UPDATE for plan revision " + revision);
        }
        return new Projection(pendingEvent.taskId(), executionEpoch, revision, pendingEvent.items(),
                pendingEvent.currentIndex(), event.getSequenceNumber(), pendingEvent.source());
    }

    private AgentTask lockWritableTask(Long taskId, long expectedExecutionEpoch) {
        requireTaskId(taskId);
        AgentTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Agent task not found: " + taskId);
        }
        long currentEpoch = task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch();
        if (currentEpoch != expectedExecutionEpoch) {
            throw new StalePlanEpochException("Plan write expected epoch " + expectedExecutionEpoch
                    + " but current epoch " + currentEpoch + " owns task " + taskId);
        }
        String status = task.getStatus() == null ? "" : task.getStatus().toLowerCase(Locale.ROOT);
        if (List.of("completed", "failed", "cancelled").contains(status)) {
            throw new IllegalStateException("Terminal Agent task cannot change its plan: " + status);
        }
        return task;
    }

    private List<AgentRunPlanItem> requireExistingPlan(Long taskId) {
        List<AgentRunPlanItem> rows = orderedRows(taskId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Current Agent task has no persisted plan");
        }
        return rows;
    }

    private List<AgentRunPlanItem> orderedRows(Long taskId) {
        List<AgentRunPlanItem> rows = planMapper.selectByTaskIdOrderByPosition(taskId);
        return rows == null ? List.of() : List.copyOf(rows);
    }

    private List<PlanDraft> normalizeDrafts(List<PlanDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("Plan must contain at least one item");
        }
        if (drafts.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Plan may contain at most " + MAX_ITEMS + " items");
        }
        List<PlanDraft> normalized = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            PlanDraft draft = drafts.get(index);
            if (draft == null) {
                throw new IllegalArgumentException("Plan item " + (index + 1) + " is required");
            }
            String title = safe(draft.title());
            String description = safe(draft.description());
            if (title.isBlank()) {
                throw new IllegalArgumentException("Plan item " + (index + 1) + " title is required");
            }
            if (title.length() > MAX_TITLE_CHARS) {
                throw new IllegalArgumentException("Plan item title exceeds " + MAX_TITLE_CHARS + " characters");
            }
            if (description.length() > MAX_DESCRIPTION_CHARS) {
                throw new IllegalArgumentException("Plan item description exceeds " + MAX_DESCRIPTION_CHARS + " characters");
            }
            normalized.add(new PlanDraft(title, description, draft.completed()));
        }
        return List.copyOf(normalized);
    }

    private Projection project(Long taskId, List<AgentRunPlanItem> rows, long eventSequence, String source) {
        if (rows == null || rows.isEmpty()) {
            return new Projection(taskId, 0L, 0L, List.of(), -1, eventSequence, safeSource(source));
        }
        long executionEpoch = rows.stream().map(AgentRunPlanItem::getExecutionEpoch)
                .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        long revision = rows.stream().map(AgentRunPlanItem::getPlanRevision)
                .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        List<PlanItem> items = rows.stream()
                .map(row -> new PlanItem(safe(row.getTitle()), safe(row.getDescription()), normalizeStatus(row.getStatus())))
                .toList();
        int currentIndex = -1;
        for (int index = 0; index < items.size(); index++) {
            if (STATUS_IN_PROGRESS.equals(items.get(index).status())) {
                currentIndex = index;
                break;
            }
        }
        if (currentIndex < 0) {
            for (int index = 0; index < items.size(); index++) {
                if (!STATUS_COMPLETED.equals(items.get(index).status())) {
                    currentIndex = index;
                    break;
                }
            }
        }
        return new Projection(taskId, executionEpoch, revision, items, currentIndex, eventSequence, safeSource(source));
    }

    private long nextRevision(List<AgentRunPlanItem> existing) {
        return existing.stream().map(AgentRunPlanItem::getPlanRevision)
                .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L) + 1L;
    }

    private int firstIncomplete(List<PlanDraft> drafts) {
        for (int index = 0; index < drafts.size(); index++) {
            if (!drafts.get(index).completed()) return index;
        }
        return -1;
    }

    private static String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case STATUS_COMPLETED -> STATUS_COMPLETED;
            case STATUS_IN_PROGRESS -> STATUS_IN_PROGRESS;
            default -> STATUS_PENDING;
        };
    }

    private static String eventIdempotencyKey(Long taskId, long revision) {
        return "plan-update:" + taskId + ":" + revision;
    }

    private static void requireTaskId(Long taskId) {
        if (taskId == null || taskId <= 0L) {
            throw new IllegalArgumentException("A positive Agent task ID is required");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\u0000", "").strip();
    }

    private static String safeSource(String source) {
        String normalized = safe(source).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return normalized.isBlank() ? "agent_plan" : normalized;
    }

    public record PlanDraft(String title, String description, boolean completed) {
    }

    public record PlanItem(String title, String description, String status) {
        public boolean completed() {
            return STATUS_COMPLETED.equals(status);
        }
    }

    public record Projection(Long taskId, long executionEpoch, long revision, List<PlanItem> items,
                             int currentIndex, long eventSequence, String source) {
        public Projection(Long taskId, long executionEpoch, long revision, List<PlanItem> items,
                          int currentIndex, long eventSequence) {
            this(taskId, executionEpoch, revision, items, currentIndex, eventSequence, "agent_plan");
        }

        public Projection {
            items = items == null ? List.of() : List.copyOf(items);
            source = safeSource(source);
        }

        public Map<String, Object> eventPayload() {
            List<Map<String, Object>> plan = new ArrayList<>(items.size());
            for (int index = 0; index < items.size(); index++) {
                PlanItem item = items.get(index);
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                row.put("index", index + 1);
                row.put("title", item.title());
                row.put("description", item.description());
                row.put("status", item.status());
                row.put("completed", item.completed());
                row.put("current", index == currentIndex);
                plan.add(Map.copyOf(row));
            }
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("executionEpoch", executionEpoch);
            payload.put("planRevision", revision);
            payload.put("source", source);
            payload.put("plan", List.copyOf(plan));
            payload.put("summary", summary());
            payload.put("planJson", GSON.toJson(plan));
            return Map.copyOf(payload);
        }

        public String summary() {
            if (items.isEmpty()) return "";
            StringBuilder summary = new StringBuilder("当前执行计划:\n");
            for (int index = 0; index < items.size(); index++) {
                PlanItem item = items.get(index);
                String marker = item.completed() ? "[DONE]" : (index == currentIndex ? "[NEXT]" : "[TODO]");
                summary.append(marker).append(' ').append(index + 1).append(". ").append(item.title());
                if (item.completed()) summary.append(" [已完成]");
                summary.append('\n');
            }
            return summary.toString();
        }

        /** 将数据库事实投影到本轮内存缓存，不改变当前 worker 的 execution epoch。 */
        public void applyTo(AgentContext context) {
            if (context == null) return;
            List<AgentContext.PlanItem> projected = items.stream()
                    .map(item -> new AgentContext.PlanItem(item.title(), item.description(), item.completed()))
                    .toList();
            context.applyPlanProjection(projected, currentIndex, revision,
                    eventSequence > 0L ? eventSequence : null, source);
        }
    }

    public static final class StalePlanEpochException extends IllegalStateException {
        public StalePlanEpochException(String message) {
            super(message);
        }
    }
}
