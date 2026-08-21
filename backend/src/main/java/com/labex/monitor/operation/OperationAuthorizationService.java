package com.labex.monitor.operation;

import com.labex.monitor.auth.MonitorRoleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 操作权限校验：状态变更要求 OPS_OPERATOR 及以上；只读角色调用操作接口返回 403。 */
@Service
public class OperationAuthorizationService {

    private final MonitorRoleService roleService;

    public OperationAuthorizationService(MonitorRoleService roleService) {
        this.roleService = roleService;
    }

    public void requireOperator(HttpServletRequest request) {
        Object role = request.getAttribute(MonitorRoleService.REQUEST_ATTRIBUTE_ROLE);
        String actualRole = role == null ? MonitorRoleService.VIEWER : String.valueOf(role);
        if (!roleService.hasRole(actualRole, MonitorRoleService.OPERATOR)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色无权执行此操作，请使用操作者校验码登录");
        }
    }

    public String currentRole(HttpServletRequest request) {
        Object role = request.getAttribute(MonitorRoleService.REQUEST_ATTRIBUTE_ROLE);
        return role == null ? MonitorRoleService.VIEWER : String.valueOf(role);
    }
}