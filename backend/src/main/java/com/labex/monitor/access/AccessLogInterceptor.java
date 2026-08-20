package com.labex.monitor.access;

import com.labex.entity.AccessLog;
import com.labex.monitor.config.MonitorProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 全局访问统计拦截器：只统计 Controller 方法调用，排除 SSE/ws/preview/监控自身等。 */
@Component
public class AccessLogInterceptor implements HandlerInterceptor {

    private static final String ATTR_START_TIME = "labex.monitor.startTime";
    private static final int MAX_UA_LENGTH = 512;

    private final AccessLogService accessLogService;
    private final ClientIpResolver ipResolver;
    private final MonitorProperties properties;

    public AccessLogInterceptor(AccessLogService accessLogService, ClientIpResolver ipResolver,
                                MonitorProperties properties) {
        this.accessLogService = accessLogService;
        this.ipResolver = ipResolver;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (shouldRecord(request, handler)) {
            request.setAttribute(ATTR_START_TIME, System.currentTimeMillis());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!properties.isEnabled()) {
            return;
        }
        Object start = request.getAttribute(ATTR_START_TIME);
        if (!(start instanceof Long startTime)) {
            return;
        }
        try {
            record(request, response, startTime);
        } catch (RuntimeException failure) {
            // 统计失败不影响业务请求；具体异常已由 AccessLogService 记录
        }
    }

    private boolean shouldRecord(HttpServletRequest request, Object handler) {
        if (!properties.isEnabled()) {
            return false;
        }
        if (!(handler instanceof HandlerMethod)) {
            return false;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        return !excluded(request.getServletPath());
    }

    private boolean excluded(String path) {
        return properties.getExcludedPrefixes().stream().anyMatch(path::startsWith);
    }

    private void record(HttpServletRequest request, HttpServletResponse response, long startTime) {
        String contentType = response.getContentType();
        if (contentType != null && contentType.startsWith("text/event-stream")) {
            return; // SSE 长连接不计入统计
        }
        AccessLog entry = new AccessLog();
        entry.setRequestTime(LocalDateTime.now());
        entry.setPath(AccessLogService.normalizePath(request.getServletPath()));
        entry.setMethod(request.getMethod());
        entry.setStatus(response.getStatus());
        entry.setDurationMs((int) Math.max(0, System.currentTimeMillis() - startTime));
        entry.setIp(ipResolver.resolve(request));
        entry.setUserAgent(truncate(request.getHeader("User-Agent"), MAX_UA_LENGTH));
        entry.setUserId(resolveUserId());
        accessLogService.record(entry);
    }

    private Integer resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetails) {
            try {
                return Integer.valueOf(auth.getName());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}