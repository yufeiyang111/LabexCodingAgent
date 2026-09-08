package com.labex.monitor.controller;

import com.labex.common.Result;
import com.labex.monitor.access.ClientIpResolver;
import com.labex.monitor.auth.MonitorRoleService;
import com.labex.monitor.user.MonitorUserActionService;
import com.labex.monitor.user.MonitorUserQueryService;
import com.labex.monitor.user.dto.UserActionRequest;
import com.labex.monitor.user.dto.UserDetailDto;
import com.labex.monitor.user.dto.UserOverviewDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维控制台：用户分析与行为监控接口（/ops/users/**，受 MonitorAuthInterceptor 保护）。
 */
@RestController
@RequestMapping("/ops/users")
public class MonitorUserController {

    private final MonitorUserQueryService queryService;
    private final MonitorUserActionService actionService;
    private final MonitorRoleService roleService;
    private final ClientIpResolver ipResolver;

    public MonitorUserController(MonitorUserQueryService queryService,
                                MonitorUserActionService actionService,
                                MonitorRoleService roleService,
                                ClientIpResolver ipResolver) {
        this.queryService = queryService;
        this.actionService = actionService;
        this.roleService = roleService;
        this.ipResolver = ipResolver;
    }

    @GetMapping("/overview")
    public Result<UserOverviewDto> overview(@RequestParam(defaultValue = "7d") String range) {
        return Result.success(queryService.getOverview(range));
    }

    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean isOnline,
            @RequestParam(defaultValue = "lastActiveTime") String sortBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(queryService.getUsers(keyword, role, status, isOnline, sortBy, page, pageSize));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<Result<UserDetailDto>> detail(@PathVariable Integer userId) {
        if (userId == null || userId <= 0) {
            return ResponseEntity.badRequest().body(Result.error(-1, "非法 userId"));
        }
        UserDetailDto detail = queryService.getUserDetail(userId);
        if (detail == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "未找到该用户"));
        }
        return ResponseEntity.ok(Result.success(detail));
    }

    @GetMapping("/{userId}/activities")
    public ResponseEntity<Result<Map<String, Object>>> activities(
            @PathVariable Integer userId,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean onlyErrors,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        if (userId == null || userId <= 0) {
            return ResponseEntity.badRequest().body(Result.error(-1, "非法 userId"));
        }
        return ResponseEntity.ok(Result.success(queryService.getUserActivities(userId, category, onlyErrors, page, pageSize)));
    }

    @PostMapping("/{userId}/actions/{action}")
    public ResponseEntity<Result<Map<String, Object>>> executeAction(
            @PathVariable Integer userId,
            @PathVariable String action,
            @RequestBody(required = false) UserActionRequest requestBody,
            HttpServletRequest request) {
        if (userId == null || userId <= 0) {
            return ResponseEntity.badRequest().body(Result.error(-1, "非法 userId"));
        }

        String actualRole = (String) request.getAttribute(MonitorRoleService.REQUEST_ATTRIBUTE_ROLE);
        if (!roleService.hasRole(actualRole, MonitorRoleService.OPERATOR)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Result.error(403, "权限不足：执行用户管控需要 " + MonitorRoleService.OPERATOR + " 或更高角色"));
        }

        String operatorId = request.getHeader("X-Operator-Code");
        if (operatorId == null || operatorId.isBlank()) {
            operatorId = "OPS_OPERATOR";
        }
        String sourceIp = ipResolver.resolve(request);
        String reason = requestBody != null ? requestBody.getReason() : null;

        String upperAction = action == null ? "" : action.toUpperCase().trim();
        switch (upperAction) {
            case "FREEZE" -> actionService.freezeUser(userId, operatorId, actualRole, sourceIp, reason);
            case "UNFREEZE" -> actionService.unfreezeUser(userId, operatorId, actualRole, sourceIp, reason);
            case "TERMINATE_ACTIVE_TASKS" -> {
                int cancelledCount = actionService.terminateActiveTasks(userId, operatorId, actualRole, sourceIp, reason);
                return ResponseEntity.ok(Result.success(Map.of("action", upperAction, "cancelledCount", cancelledCount)));
            }
            default -> {
                return ResponseEntity.badRequest().body(Result.error(-1, "不支持的操作类型: " + action));
            }
        }

        return ResponseEntity.ok(Result.success(Map.of("action", upperAction, "success", true)));
    }
}
