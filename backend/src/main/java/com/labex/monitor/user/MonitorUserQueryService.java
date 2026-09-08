package com.labex.monitor.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.AccessLog;
import com.labex.entity.AgentTask;
import com.labex.entity.AppUser;
import com.labex.entity.CommandAuditEvent;
import com.labex.entity.StudentProject;
import com.labex.mapper.AccessLogMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.mapper.AppUserMapper;
import com.labex.mapper.CommandAuditEventMapper;
import com.labex.mapper.OpsUserMapper;
import com.labex.mapper.StudentProjectMapper;
import com.labex.monitor.geo.IpLocation;
import com.labex.monitor.geo.IpLocationService;
import com.labex.monitor.user.dto.TokenBreakdownDto;
import com.labex.monitor.user.dto.UserActivityEventDto;
import com.labex.monitor.user.dto.UserDetailDto;
import com.labex.monitor.user.dto.UserOverviewDto;
import com.labex.monitor.user.dto.UserProfileDto;
import com.labex.monitor.user.dto.UserProjectDto;
import com.labex.monitor.user.dto.UserSummaryDto;
import com.labex.monitor.user.dto.UserTaskMetricsDto;
import com.labex.monitor.user.dto.UserTrendPointDto;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class MonitorUserQueryService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OpsUserMapper opsUserMapper;
    private final AppUserMapper appUserMapper;
    private final AccessLogMapper accessLogMapper;
    private final AgentTaskMapper agentTaskMapper;
    private final CommandAuditEventMapper commandAuditEventMapper;
    private final StudentProjectMapper studentProjectMapper;
    private final IpLocationService ipLocationService;

    public MonitorUserQueryService(OpsUserMapper opsUserMapper,
                                  AppUserMapper appUserMapper,
                                  AccessLogMapper accessLogMapper,
                                  AgentTaskMapper agentTaskMapper,
                                  CommandAuditEventMapper commandAuditEventMapper,
                                  StudentProjectMapper studentProjectMapper,
                                  IpLocationService ipLocationService) {
        this.opsUserMapper = opsUserMapper;
        this.appUserMapper = appUserMapper;
        this.accessLogMapper = accessLogMapper;
        this.agentTaskMapper = agentTaskMapper;
        this.commandAuditEventMapper = commandAuditEventMapper;
        this.studentProjectMapper = studentProjectMapper;
        this.ipLocationService = ipLocationService;
    }

    public UserOverviewDto getOverview(String range) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime fiveMinutesAgo = now.minusMinutes(5);
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime weekAgo = now.minusDays(7);

        int onlineCount = opsUserMapper.countActiveUsersSince(fiveMinutesAgo);
        int todayDau = opsUserMapper.countActiveUsersSince(todayStart);
        int yesterdayDau = opsUserMapper.countActiveUsersBetween(yesterdayStart, todayStart);
        int weeklyWau = opsUserMapper.countActiveUsersSince(weekAgo);
        long totalRegistered = appUserMapper.selectCount(null);

        double dauChangeRatio = yesterdayDau == 0 ? 0.0 : ((double) (todayDau - yesterdayDau) / yesterdayDau);

        long todayTokens = opsUserMapper.sumTokensSince(todayStart);
        int todayTasks = opsUserMapper.countTasksSince(todayStart);

        long avgTokensPerActiveUser = todayDau == 0 ? 0 : todayTokens / todayDau;
        double avgTasksPerActiveUser = todayDau == 0 ? 0.0 : Math.round(((double) todayTasks / todayDau) * 10.0) / 10.0;

        List<UserTrendPointDto> trendSeries = buildTrendSeries(range);

        return UserOverviewDto.builder()
                .onlineCount(onlineCount)
                .todayDau(todayDau)
                .dauChangeRatio(Math.round(dauChangeRatio * 1000.0) / 1000.0)
                .weeklyWau(weeklyWau)
                .totalRegistered(totalRegistered)
                .avgTokensPerActiveUser(avgTokensPerActiveUser)
                .avgTasksPerActiveUser(avgTasksPerActiveUser)
                .trendSeries(trendSeries)
                .build();
    }

    private List<UserTrendPointDto> buildTrendSeries(String range) {
        String normalizedRange = range == null ? "7d" : range.trim().toLowerCase();
        List<Map<String, Object>> accessPoints;
        List<Map<String, Object>> taskPoints;

        if ("24h".equals(normalizedRange)) {
            LocalDateTime since = LocalDateTime.now().minusHours(24);
            accessPoints = opsUserMapper.accessTrendByHour(since);
            taskPoints = opsUserMapper.taskTrendByHour(since);
        } else {
            int days = "30d".equals(normalizedRange) ? 30 : 7;
            accessPoints = opsUserMapper.accessTrendByDay(days);
            taskPoints = opsUserMapper.taskTrendByDay(days);
        }

        Map<String, UserTrendPointDto> mergedMap = new LinkedHashMap<>();
        for (Map<String, Object> point : accessPoints) {
            String bucket = String.valueOf(point.get("time_bucket"));
            int activeUsers = ((Number) point.getOrDefault("active_users", 0)).intValue();
            int requestCount = ((Number) point.getOrDefault("request_count", 0)).intValue();
            mergedMap.put(bucket, new UserTrendPointDto(bucket, activeUsers, requestCount, 0));
        }

        for (Map<String, Object> point : taskPoints) {
            String bucket = String.valueOf(point.get("time_bucket"));
            int taskCount = ((Number) point.getOrDefault("task_count", 0)).intValue();
            UserTrendPointDto existing = mergedMap.get(bucket);
            if (existing != null) {
                existing.setTaskCount(taskCount);
            } else {
                mergedMap.put(bucket, new UserTrendPointDto(bucket, 0, 0, taskCount));
            }
        }

        List<UserTrendPointDto> result = new ArrayList<>(mergedMap.values());
        result.sort(Comparator.comparing(UserTrendPointDto::getTimestamp));
        return result;
    }

    public Map<String, Object> getUsers(String keyword, String role, Integer status, Boolean isOnline,
                                       String sortBy, int page, int pageSize) {
        LambdaQueryWrapper<AppUser> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            boolean isNumber = kw.matches("\\d+");
            wrapper.and(w -> {
                if (isNumber) {
                    w.eq(AppUser::getUserId, Integer.parseInt(kw)).or();
                }
                w.like(AppUser::getUsername, kw)
                        .or().like(AppUser::getDisplayName, kw)
                        .or().like(AppUser::getEmail, kw);
            });
        }
        if (role != null && !role.isBlank()) {
            wrapper.eq(AppUser::getRole, role.trim());
        }
        if (status != null) {
            wrapper.eq(AppUser::getStatus, status);
        }

        wrapper.orderByDesc(AppUser::getUserId);

        Page<AppUser> pageReq = new Page<>(Math.max(1, page), Math.min(100, Math.max(1, pageSize)));
        Page<AppUser> pageResult = appUserMapper.selectPage(pageReq, wrapper);

        List<AppUser> userList = pageResult.getRecords();
        if (userList.isEmpty()) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("list", Collections.emptyList());
            res.put("total", pageResult.getTotal());
            res.put("page", pageResult.getCurrent());
            res.put("pageSize", pageResult.getSize());
            return res;
        }

        List<Integer> userIds = userList.stream().map(AppUser::getUserId).toList();
        String userIdsCsv = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        Map<Integer, Integer> projectCountMap = new HashMap<>();
        opsUserMapper.selectProjectCountsByUserIds(userIdsCsv).forEach(row -> {
            projectCountMap.put(((Number) row.get("user_id")).intValue(), ((Number) row.get("project_count")).intValue());
        });

        Map<Integer, TaskStatHolder> taskStatMap = new HashMap<>();
        opsUserMapper.selectTaskStatsByUserIds(userIdsCsv).forEach(row -> {
            int uid = ((Number) row.get("user_id")).intValue();
            int total = ((Number) row.getOrDefault("task_total", 0)).intValue();
            int completed = ((Number) row.getOrDefault("task_completed", 0)).intValue();
            taskStatMap.put(uid, new TaskStatHolder(total, completed));
        });

        Map<Integer, Long> tokenTotalMap = new HashMap<>();
        opsUserMapper.selectTokenTotalsByUserIds(userIdsCsv).forEach(row -> {
            tokenTotalMap.put(((Number) row.get("user_id")).intValue(), ((Number) row.getOrDefault("total_tokens", 0)).longValue());
        });

        Map<Integer, String> lastActiveMap = new HashMap<>();
        opsUserMapper.selectLastActiveByUserIds(userIdsCsv).forEach(row -> {
            int uid = ((Number) row.get("user_id")).intValue();
            Object activeTime = row.get("last_active_time");
            if (activeTime != null) {
                lastActiveMap.put(uid, String.valueOf(activeTime).replace("T", " "));
            }
        });

        LocalDateTime fiveMinsAgo = LocalDateTime.now().minusMinutes(5);
        List<UserSummaryDto> summaryList = new ArrayList<>();
        for (AppUser u : userList) {
            int uid = u.getUserId();
            int pCount = projectCountMap.getOrDefault(uid, 0);
            TaskStatHolder tStat = taskStatMap.getOrDefault(uid, new TaskStatHolder(0, 0));
            long tokens = tokenTotalMap.getOrDefault(uid, 0L);
            String lastActive = lastActiveMap.get(uid);

            boolean online = false;
            if (lastActive != null) {
                try {
                    LocalDateTime lat = LocalDateTime.parse(lastActive.substring(0, 19).replace(" ", "T"));
                    online = lat.isAfter(fiveMinsAgo);
                } catch (Exception ignored) {
                }
            }

            double successRate = tStat.total == 0 ? 1.0 : Math.round(((double) tStat.completed / tStat.total) * 1000.0) / 1000.0;

            if (Boolean.TRUE.equals(isOnline) && !online) {
                continue;
            }

            summaryList.add(UserSummaryDto.builder()
                    .userId(uid)
                    .username(u.getUsername())
                    .displayName(u.getDisplayName())
                    .email(u.getEmail())
                    .role(u.getRole())
                    .status(u.getStatus())
                    .online(online)
                    .lastActiveTime(lastActive)
                    .createTime(u.getCreateTime() == null ? null : u.getCreateTime().toString().replace("T", " "))
                    .projectCount(pCount)
                    .taskCount(tStat.total)
                    .taskSuccessRate(successRate)
                    .totalTokens(tokens)
                    .build());
        }

        if (sortBy != null && !sortBy.isBlank()) {
            switch (sortBy) {
                case "totalTokens" -> summaryList.sort(Comparator.comparingLong(UserSummaryDto::getTotalTokens).reversed());
                case "taskCount" -> summaryList.sort(Comparator.comparingInt(UserSummaryDto::getTaskCount).reversed());
                case "lastActiveTime" -> summaryList.sort(Comparator.comparing(UserSummaryDto::getLastActiveTime,
                        Comparator.nullsLast(Comparator.reverseOrder())));
                default -> {}
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("list", summaryList);
        res.put("total", pageResult.getTotal());
        res.put("page", pageResult.getCurrent());
        res.put("pageSize", pageResult.getSize());
        return res;
    }

    public UserDetailDto getUserDetail(Integer userId) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            return null;
        }

        Map<String, Object> lastAccess = opsUserMapper.selectLastAccessByUserId(userId);
        String lastIp = lastAccess == null ? null : (String) lastAccess.get("ip");
        String location = null;
        if (lastIp != null) {
            IpLocation loc = ipLocationService.lookup(lastIp);
            location = loc == null ? null : loc.display();
        }

        UserProfileDto profile = UserProfileDto.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .role(user.getRole())
                .status(user.getStatus())
                .createTime(user.getCreateTime() == null ? null : user.getCreateTime().toString().replace("T", " "))
                .lastLoginIp(lastIp)
                .lastLoginLocation(location)
                .build();

        List<Map<String, Object>> tokenRows = opsUserMapper.selectUserTokenBreakdown(userId);
        long sumUserTokens = 0;
        for (Map<String, Object> row : tokenRows) {
            sumUserTokens += ((Number) row.getOrDefault("total_tokens", 0)).longValue();
        }
        List<TokenBreakdownDto> tokenBreakdown = new ArrayList<>();
        for (Map<String, Object> row : tokenRows) {
            long tokens = ((Number) row.getOrDefault("total_tokens", 0)).longValue();
            double pct = sumUserTokens == 0 ? 0.0 : Math.round(((double) tokens / sumUserTokens) * 1000.0) / 1000.0;
            tokenBreakdown.add(TokenBreakdownDto.builder()
                    .provider(String.valueOf(row.get("provider")))
                    .model(String.valueOf(row.get("model")))
                    .totalTokens(tokens)
                    .percentage(pct)
                    .build());
        }

        List<Map<String, Object>> taskStatusRows = opsUserMapper.selectUserTaskStatusCounts(userId);
        int totalTasks = 0;
        int completedTasks = 0;
        int failedTasks = 0;
        int runningTasks = 0;
        for (Map<String, Object> row : taskStatusRows) {
            String st = String.valueOf(row.get("status")).toLowerCase();
            int cnt = ((Number) row.getOrDefault("count_val", 0)).intValue();
            totalTasks += cnt;
            if ("completed".equals(st)) {
                completedTasks += cnt;
            } else if ("failed".equals(st) || "cancelled".equals(st)) {
                failedTasks += cnt;
            } else if ("running".equals(st) || "queued".equals(st) || "preparing".equals(st)) {
                runningTasks += cnt;
            }
        }
        UserTaskMetricsDto taskMetrics = UserTaskMetricsDto.builder()
                .totalTasks(totalTasks)
                .completedTasks(completedTasks)
                .failedTasks(failedTasks)
                .runningTasks(runningTasks)
                .build();

        LambdaQueryWrapper<StudentProject> projectWrapper = new LambdaQueryWrapper<>();
        projectWrapper.eq(StudentProject::getStudentId, userId)
                .orderByDesc(StudentProject::getUpdateTime)
                .last("LIMIT 20");
        List<StudentProject> projectList = studentProjectMapper.selectList(projectWrapper);
        List<UserProjectDto> projects = projectList.stream()
                .map(p -> UserProjectDto.builder()
                        .projectId(p.getProjectId())
                        .projectName(p.getProjectName())
                        .updateTime(p.getUpdateTime() == null ? null : p.getUpdateTime().toString().replace("T", " "))
                        .build())
                .toList();

        return UserDetailDto.builder()
                .profile(profile)
                .tokenBreakdown(tokenBreakdown)
                .taskMetrics(taskMetrics)
                .projects(projects)
                .build();
    }

    public Map<String, Object> getUserActivities(Integer userId, String category, boolean onlyErrors,
                                                int page, int pageSize) {
        List<UserActivityEventDto> allEvents = new ArrayList<>();

        boolean fetchHttp = category == null || "ALL".equalsIgnoreCase(category) || "HTTP".equalsIgnoreCase(category);
        boolean fetchTask = category == null || "ALL".equalsIgnoreCase(category) || "TASK".equalsIgnoreCase(category);
        boolean fetchSecurity = category == null || "ALL".equalsIgnoreCase(category) || "SECURITY".equalsIgnoreCase(category);

        if (fetchHttp) {
            LambdaQueryWrapper<AccessLog> accessWrapper = new LambdaQueryWrapper<>();
            accessWrapper.eq(AccessLog::getUserId, userId);
            if (onlyErrors) {
                accessWrapper.ge(AccessLog::getStatus, 400);
            }
            accessWrapper.orderByDesc(AccessLog::getRequestTime).last("LIMIT 50");
            List<AccessLog> logs = accessLogMapper.selectList(accessWrapper);
            for (AccessLog log : logs) {
                String statusType = log.getStatus() >= 500 ? "DANGER" : (log.getStatus() >= 400 ? "WARNING" : "SUCCESS");
                allEvents.add(UserActivityEventDto.builder()
                        .eventId("http_" + log.getId())
                        .timestamp(log.getRequestTime() == null ? null : log.getRequestTime().toString().replace("T", " "))
                        .category("HTTP")
                        .action(log.getMethod() + " " + log.getPath())
                        .title("接口请求: " + log.getPath())
                        .details("方法: " + log.getMethod() + ", 耗时: " + log.getDurationMs() + "ms, IP: " + log.getIp())
                        .status(statusType)
                        .metadata(Map.of("status", log.getStatus(), "durationMs", log.getDurationMs(), "ip", log.getIp() == null ? "" : log.getIp()))
                        .build());
            }
        }

        if (fetchTask) {
            LambdaQueryWrapper<AgentTask> taskWrapper = new LambdaQueryWrapper<>();
            taskWrapper.eq(AgentTask::getStudentId, userId);
            if (onlyErrors) {
                taskWrapper.eq(AgentTask::getStatus, "failed");
            }
            taskWrapper.orderByDesc(AgentTask::getTaskId).last("LIMIT 50");
            List<AgentTask> tasks = agentTaskMapper.selectList(taskWrapper);
            for (AgentTask task : tasks) {
                String st = task.getStatus();
                String statusType = "completed".equalsIgnoreCase(st) ? "SUCCESS"
                        : ("failed".equalsIgnoreCase(st) ? "DANGER" : "INFO");
                allEvents.add(UserActivityEventDto.builder()
                        .eventId("task_" + task.getTaskId())
                        .timestamp(task.getSubmittedAt() == null ? (task.getStartedAt() == null ? null : task.getStartedAt().toString().replace("T", " "))
                                : task.getSubmittedAt().toString().replace("T", " "))
                        .category("TASK")
                        .action("AGENT_TASK_" + (st == null ? "UNKNOWN" : st.toUpperCase()))
                        .title("Agent 任务: " + (task.getTitle() == null ? ("#" + task.getTaskId()) : task.getTitle()))
                        .details("状态: " + st + (task.getSummary() != null ? (", 总结: " + task.getSummary()) : ""))
                        .status(statusType)
                        .metadata(Map.of("taskId", task.getTaskId(), "status", st == null ? "" : st))
                        .build());
            }
        }

        if (fetchSecurity) {
            LambdaQueryWrapper<CommandAuditEvent> cmdWrapper = new LambdaQueryWrapper<>();
            cmdWrapper.eq(CommandAuditEvent::getStudentId, userId);
            if (onlyErrors) {
                cmdWrapper.and(w -> w.eq(CommandAuditEvent::getDecision, "REJECTED")
                        .or().ne(CommandAuditEvent::getExitCode, 0));
            }
            cmdWrapper.orderByDesc(CommandAuditEvent::getEventId).last("LIMIT 50");
            List<CommandAuditEvent> cmds = commandAuditEventMapper.selectList(cmdWrapper);
            for (CommandAuditEvent cmd : cmds) {
                String decision = cmd.getDecision();
                String statusType = "REJECTED".equalsIgnoreCase(decision) ? "DANGER"
                        : ("APPROVED".equalsIgnoreCase(decision) ? "SUCCESS" : "WARNING");
                allEvents.add(UserActivityEventDto.builder()
                        .eventId("sec_" + cmd.getEventId())
                        .timestamp(cmd.getCreateTime() == null ? null : cmd.getCreateTime().toString().replace("T", " "))
                        .category("SECURITY")
                        .action("COMMAND_" + (cmd.getEventType() == null ? "APPROVAL" : cmd.getEventType()))
                        .title("命令审批: " + (cmd.getClassification() == null ? "安全审查" : cmd.getClassification()))
                        .details("决策: " + decision + ", 状态: " + cmd.getExecutionStatus())
                        .status(statusType)
                        .metadata(Map.of("approvalId", cmd.getApprovalId() == null ? "" : cmd.getApprovalId(),
                                "decision", decision == null ? "" : decision))
                        .build());
            }
        }

        allEvents.sort(Comparator.comparing(UserActivityEventDto::getTimestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        int total = allEvents.size();
        int fromIndex = Math.min((safePage - 1) * safePageSize, total);
        int toIndex = Math.min(fromIndex + safePageSize, total);

        List<UserActivityEventDto> paged = allEvents.subList(fromIndex, toIndex);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("list", paged);
        res.put("total", total);
        res.put("page", safePage);
        res.put("pageSize", safePageSize);
        return res;
    }

    private static class TaskStatHolder {
        int total;
        int completed;

        TaskStatHolder(int total, int completed) {
            this.total = total;
            this.completed = completed;
        }
    }
}
