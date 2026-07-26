package com.labex.filter;

import com.labex.security.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT认证过滤器
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);
        log.debug("JWT authentication requestURI={} authorizationPresent={} tokenPresent={}", request.getRequestURI(),
                request.getHeader("Authorization") != null, token != null);
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            Integer userId = jwtUtil.getUserIdFromToken(token);
            String role = jwtUtil.getRoleFromToken(token);

            log.debug("JWT验证通过: username={}, userId={}, role={}", username, userId, role);

            // 根据角色设置权限
            String authority = "ROLE_" + role;
            if ("ADMIN".equals(role)) {
                authority = "ROLE_ADMIN";
            }

            // 创建用户详情
            UserDetails userDetails = User.withUsername(username)
                    .password("")
                    .authorities(authority)
                    .accountExpired(false)
                    .accountLocked(false)
                    .credentialsExpired(false)
                    .disabled(false)
                    .build();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } else {
            log.debug("JWT authentication not established tokenPresent={} validation={}",
                    token != null, token != null ? "invalid" : "not_provided");
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 从请求头或URL参数解析Token
     */
    private String resolveToken(HttpServletRequest request) {
        // 从请求头获取 Authorization 字段
        String authHeader = request.getHeader("Authorization");
        // 检查是否以 Bearer 前缀开头
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // 提取 token（去掉 "Bearer " 前缀）
            return authHeader.substring(7);
        }
        // 支持从URL参数中获取token（用于预览等场景）
        String tokenParam = request.getParameter("token");
        if (tokenParam != null && !tokenParam.isEmpty()) {
            return tokenParam;
        }
        return null;
    }
}
