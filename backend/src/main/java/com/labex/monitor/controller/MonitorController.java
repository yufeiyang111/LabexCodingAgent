package com.labex.monitor.controller;

import com.labex.common.Result;
import com.labex.monitor.access.ClientIpResolver;
import com.labex.monitor.auth.MonitorAccessService;
import com.labex.monitor.config.MonitorProperties;
import com.labex.monitor.dto.PathStat;
import com.labex.monitor.dto.StatusStat;
import com.labex.monitor.dto.TrafficPoint;
import com.labex.monitor.dto.VisitorAgg;
import com.labex.monitor.dto.VisitorHit;
import com.labex.monitor.service.MonitorQueryService;
import com.labex.monitor.system.SystemMetricsService;
import com.labex.monitor.system.SystemSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 站点运维监控接口（路径保持隐蔽，前端通过校验码进入）。 */
@RestController
@RequestMapping("/ops")
public class MonitorController {

    private static final List<String> TRAFFIC_RANGES = List.of("24h", "7d", "30d");

    private final MonitorAccessService accessService;
    private final MonitorQueryService queryService;
    private final SystemMetricsService systemMetricsService;
    private final ClientIpResolver ipResolver;
    private final MonitorProperties properties;

    public MonitorController(MonitorAccessService accessService, MonitorQueryService queryService,
                             SystemMetricsService systemMetricsService, ClientIpResolver ipResolver,
                             MonitorProperties properties) {
        this.accessService = accessService;
        this.queryService = queryService;
        this.systemMetricsService = systemMetricsService;
        this.ipResolver = ipResolver;
        this.properties = properties;
    }

    @PostMapping("/auth")
    public ResponseEntity<Result<Map<String, Object>>> auth(@RequestBody(required = false) Map<String, String> body,
                                                            HttpServletRequest request) {
        if (!accessService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE.value())
                    .body(Result.error(-1, "监控未启用，请配置 LABEX_AGENT_MONITOR_ACCESS_CODE 后访问"));
        }
        String input = body == null ? null : body.get("accessCode");
        if (input == null || input.isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(-1, "请输入校验码"));
        }
        if (!accessService.verifyAccessCode(input, ipResolver.resolve(request))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED.value()).body(Result.error(-1, "校验码错误"));
        }
        String role = accessService.roleForCode(input);
        String token = accessService.issueSession(role);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("role", role);
        data.put("expiresAt", Instant.now().plus(Duration.ofHours(properties.getSessionTtlHours())).toString());
        return ResponseEntity.ok(Result.success(data));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        accessService.revokeSession(request.getHeader("X-Monitor-Token"));
        return Result.success();
    }

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.success(queryService.summary());
    }

    @GetMapping("/traffic")
    public Result<List<TrafficPoint>> traffic(@RequestParam(defaultValue = "24h") String range) {
        if (!TRAFFIC_RANGES.contains(range)) {
            return Result.error(-1, "不支持的统计区间: " + range);
        }
        return Result.success(queryService.traffic(range));
    }

    @GetMapping("/top-paths")
    public Result<List<PathStat>> topPaths(@RequestParam(defaultValue = "7") int days,
                                           @RequestParam(defaultValue = "10") int limit) {
        return Result.success(queryService.topPaths(days, limit));
    }

    @GetMapping("/status-distribution")
    public Result<List<StatusStat>> statusDistribution(@RequestParam(defaultValue = "7") int days) {
        return Result.success(queryService.statusDistribution(days));
    }

    @GetMapping("/system")
    public Result<SystemSnapshot> system() {
        return Result.success(systemMetricsService.snapshot());
    }

    @GetMapping("/recent-visitors")
    public Result<List<VisitorHit>> recentVisitors(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(queryService.recentVisitors(limit));
    }

    @GetMapping("/visitors")
    public Result<List<VisitorAgg>> visitors(@RequestParam(defaultValue = "7") int days,
                                             @RequestParam(defaultValue = "50") int limit) {
        return Result.success(queryService.visitors(days, limit));
    }
}