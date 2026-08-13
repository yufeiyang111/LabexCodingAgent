package com.labex.labexagent.runtime;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Agent 执行配置：OpenCode-first 是默认 profile，safe 保留 direct-command 兼容，
 * full_access 只允许显式 unsafe-local 使用。
 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.execution")
public class AgentExecutionProperties implements EnvironmentAware {
    private String permissionProfile = "opencode";
    private boolean networkDefaultEnabled = true;
    private boolean allowFullAccess;
    private Environment environment;

    /**
     * production/prod 环境拒绝 full_access；Spring Environment 注入后，只有显式
     * unsafe-local profile 才允许开启 full_access。
     */
    public String getPermissionProfile() {
        if (!"full_access".equals(permissionProfile)) {
            return permissionProfile;
        }
        if (!allowFullAccess || isProductionEnvironment() || !isUnsafeLocalEnvironment()) {
            return "opencode";
        }
        return "full_access";
    }

    public void setPermissionProfile(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        permissionProfile = switch (normalized) {
            case "safe", "opencode", "full_access" -> normalized;
            default -> "opencode";
        };
    }

    public boolean isNetworkDefaultEnabled() {
        return networkDefaultEnabled && !isSafeProfile();
    }

    public void setNetworkDefaultEnabled(boolean value) {
        networkDefaultEnabled = value;
    }

    /** full_access 是否被显式允许；production/prod 仍会降级为 opencode。 */
    public boolean isAllowFullAccess() {
        return allowFullAccess;
    }

    public void setAllowFullAccess(boolean value) {
        allowFullAccess = value;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public boolean isOpenCodeProfile() {
        return "opencode".equals(getPermissionProfile());
    }

    public boolean isFullAccessProfile() {
        return "full_access".equals(getPermissionProfile());
    }

    public boolean isSafeProfile() {
        return "safe".equals(getPermissionProfile());
    }

    private boolean isProductionEnvironment() {
        return environment != null
                && (environment.matchesProfiles("prod") || environment.matchesProfiles("production"));
    }

    private boolean isUnsafeLocalEnvironment() {
        return environment == null || environment.matchesProfiles("unsafe-local");
    }
}
