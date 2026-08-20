package com.labex.monitor.access;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** 解析客户端真实 IP：优先取反向代理链路（内网穿透场景），最后回退 remoteAddr。 */
@Component
public class ClientIpResolver {

    public String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isBlank()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}