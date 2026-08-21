package com.labex.monitor.operation;

import com.labex.monitor.auth.MonitorAccessService;
import com.labex.monitor.auth.MonitorRoleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 操作权限校验：会话角色 OPS_OPERATOR 及以上放行；只读角色可携带操作码按请求提权；否则 403。 */
@Service
public class OperationAuthorizationService {

    private final MonitorRoleService roleService;
    private final MonitorAccessService accessService;

    public OperationAuthorizationService(MonitorRoleService roleService, MonitorAccessService accessService) {
        this.roleService = roleService;
        this.accessService = accessService;
    }

    /** 返回操作实际生效角色；会话角色不足时校验请求携带的操作码。 */
    public String requireOperator(HttpServletRequest request, String operatorCode) {
        String role = currentRole(request);
        if (roleService.hasRole(role, MonitorRoleService.OPERATOR)) {
            return role;
        }
        if (operatorCode != null && accessService.verifyOperatorCode(operatorCode)) {
            return MonitorRoleService.OPERATOR;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色无权执行此操作，请使用操作者校验码登录");
    }

    public String currentRole(HttpServletRequest request) {
        Object role = request.getAttribute(MonitorRoleService.REQUEST_ATTRIBUTE_ROLE);
        return role == null ? MonitorRoleService.VIEWER : String.valueOf(role);
    }
}