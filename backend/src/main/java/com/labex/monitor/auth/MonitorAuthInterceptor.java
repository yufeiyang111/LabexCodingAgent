package com.labex.monitor.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.common.Result;
import com.labex.monitor.config.MonitorProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 校验 /ops/** 请求的会话 token；/ops/auth 与 /ops/logout 放行。 */
@Component
public class MonitorAuthInterceptor implements HandlerInterceptor {

    private static final String MONITOR_TOKEN_HEADER = "X-Monitor-Token";

    private final MonitorAccessService accessService;
    private final MonitorProperties properties;
    private final ObjectMapper objectMapper;

    public MonitorAuthInterceptor(MonitorAccessService accessService, MonitorProperties properties,
                                  ObjectMapper objectMapper) {
        this.accessService = accessService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getServletPath();
        if ("/ops/auth".equals(path) || "/ops/logout".equals(path)) {
            return true;
        }
        if (!accessService.isEnabled()) {
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE.value(), "监控未启用，请配置校验码后访问");
            return false;
        }
        String token = request.getHeader(MONITOR_TOKEN_HEADER);
        if (!accessService.isValidToken(token)) {
            writeError(response, HttpStatus.UNAUTHORIZED.value(), "访问凭证缺失或已失效，请重新输入校验码");
            return false;
        }
        request.setAttribute(MonitorRoleService.REQUEST_ATTRIBUTE_ROLE, accessService.sessionRole(token));
        return true;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.error(-1, message));
    }
}