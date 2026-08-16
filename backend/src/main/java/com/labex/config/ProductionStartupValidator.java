package com.labex.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 生产启动校验：任何缺失或危险的默认配置都在启动前失败，并把所有问题一次性列出，
 * 避免部署后发现第一条错误修复后立刻撞上第二条。local / acceptance 不激活本校验。
 */
@Configuration
@Profile({"prod", "production"})
public class ProductionStartupValidator {

    private static final String LOCAL_JWT_PLACEHOLDER =
            "labex-agent-local-dev-secret-key-change-me-please-64-bytes-minimum";
    private static final String LOCAL_WEBSOCKET_ORIGINS = "http://localhost:3000,http://127.0.0.1:3000";

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Value("${spring.datasource.username:}")
    private String datasourceUsername;

    @Value("${spring.datasource.password:}")
    private String datasourcePassword;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${labex-agent.secret-store.master-key:}")
    private String secretStoreMasterKey;

    @Value("${labex-agent.execution.permission-profile:labex-standard}")
    private String rawPermissionProfile;

    @Value("${labex-agent.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    @Value("${labex-agent.websocket.allowed-origins:}")
    private String websocketAllowedOrigins;

    @Value("${labex-agent.project-base-path:}")
    private String projectBasePath;

    @Value("${rag.upload-path:}")
    private String uploadPath;

    @PostConstruct
    void validateProductionConfiguration() {
        List<String> problems = new ArrayList<>();
        if (datasourceUrl == null || datasourceUrl.isBlank()) {
            problems.add("spring.datasource.url 未配置：设置 LABEX_AGENT_DB_URL 指向生产 MySQL。");
        }
        if (datasourceUsername == null || datasourceUsername.isBlank()) {
            problems.add("spring.datasource.username 未配置：设置 LABEX_AGENT_DB_USERNAME。");
        }
        if (datasourcePassword == null || datasourcePassword.isBlank()) {
            problems.add("spring.datasource.password 为空：生产必须设置 LABEX_AGENT_DB_PASSWORD，不允许空密码。");
        }
        if (jwtSecret == null || jwtSecret.isBlank() || LOCAL_JWT_PLACEHOLDER.equals(jwtSecret.trim())
                || jwtSecret.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 64) {
            problems.add("jwt.secret 缺失、仍是本地占位值或不足 64 字节：用 `openssl rand -base64 64` 生成并设置 LABEX_AGENT_JWT_SECRET。");
        }
        if (secretStoreMasterKey == null || secretStoreMasterKey.isBlank()) {
            problems.add("labex-agent.secret-store.master-key 为空：设置 LABEX_AGENT_SECRET_STORE_MASTER_KEY"
                    + "（Base64 编码的随机 32 字节，如 `openssl rand -base64 32`）。");
        } else {
            try {
                if (Base64.getDecoder().decode(secretStoreMasterKey.trim()).length != 32) {
                    problems.add("labex-agent.secret-store.master-key 不是 Base64 编码的 32 字节密钥。");
                }
            } catch (IllegalArgumentException e) {
                problems.add("labex-agent.secret-store.master-key 不是合法的 Base64 值。");
            }
        }
        String profile = rawPermissionProfile == null ? "" : rawPermissionProfile.trim().toLowerCase(Locale.ROOT);
        if ("full_access".equals(profile)) {
            problems.add("生产环境禁止 permission-profile=full_access：只能使用 labex-standard（或显式 safe）。"
                    + "full_access 仅限显式 unsafe-local 本地诊断环境。");
        }
        List<String> problemsFromOrigins = validateHttpsOrigins(corsAllowedOrigins, "labex-agent.cors.allowed-origins");
        if (problemsFromOrigins != null) {
            problems.addAll(problemsFromOrigins);
        }
        if (websocketAllowedOrigins == null || websocketAllowedOrigins.isBlank()
                || LOCAL_WEBSOCKET_ORIGINS.equals(websocketAllowedOrigins.trim())) {
            problems.add("labex-agent.websocket.allowed-origins 仍是本地默认值：设置 LABEX_AGENT_WEBSOCKET_ALLOWED_ORIGINS"
                    + " 为实际 HTTPS 域名（逗号分隔，不允许 *）。");
        } else {
            List<String> wsProblems = validateHttpsOrigins(websocketAllowedOrigins,
                    "labex-agent.websocket.allowed-origins");
            if (wsProblems != null) {
                problems.addAll(wsProblems);
            }
        }
        if (hasWindowsDrivePrefix(projectBasePath)) {
            problems.add("labex-agent.project-base-path 仍是 Windows 盘符路径（" + projectBasePath
                    + "）：生产 Linux 使用 /srv/labex-agent/workspaces 之类绝对路径。");
        }
        if (hasWindowsDrivePrefix(uploadPath)) {
            problems.add("LABEX_AGENT_UPLOAD_PATH 仍是 Windows 盘符路径（" + uploadPath
                    + "）：生产 Linux 使用 /srv/labex-agent/uploads 之类绝对路径。");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("生产配置校验失败，共 " + problems.size() + " 项：\n - "
                    + String.join("\n - ", problems));
        }
    }

    private List<String> validateHttpsOrigins(String origins, String propertyName) {
        if (origins == null || origins.isBlank()) {
            return List.of(propertyName + " 未配置：设置为实际 HTTPS 域名（逗号分隔，不允许 *）。");
        }
        List<String> problems = new ArrayList<>();
        for (String origin : origins.split(",")) {
            String trimmed = origin.trim();
            if (trimmed.isBlank() || "*".equals(trimmed)) {
                problems.add(propertyName + " 不允许 `*`：必须列出实际 HTTPS 域名。");
                continue;
            }
            if (!trimmed.startsWith("https://") || trimmed.length() <= "https://".length()) {
                problems.add(propertyName + " 包含非 HTTPS 来源（" + trimmed + "）：生产必须只使用 HTTPS 域名。");
            }
        }
        return problems.isEmpty() ? null : problems;
    }

    private boolean hasWindowsDrivePrefix(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String trimmed = path.trim();
        return trimmed.length() >= 2 && Character.isLetter(trimmed.charAt(0)) && trimmed.charAt(1) == ':';
    }
}
