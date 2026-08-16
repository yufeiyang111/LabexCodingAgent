package com.labex.labexagent.runtime;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Agent 执行配置：Labex 标准 profile 是默认值，safe 保留 direct-command 兼容，
 * full_access 只允许显式 unsafe-local 使用。
 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.execution")
public class AgentExecutionProperties implements EnvironmentAware {
    public static final String STANDARD_PROFILE = "labex-standard";
    public static final String SAFE_PROFILE = "safe";
    public static final String FULL_ACCESS_PROFILE = "full_access";

    private String permissionProfile = STANDARD_PROFILE;
    private boolean networkDefaultEnabled = true;
    private boolean allowFullAccess;
    private Environment environment;

    /**
     * production/prod 环境拒绝 full_access；Spring Environment 注入后，只有显式
     * unsafe-local profile 才允许开启 full_access。
     */
    public String getPermissionProfile() {
        if (!FULL_ACCESS_PROFILE.equals(permissionProfile)) {
            return permissionProfile;
        }
        if (!allowFullAccess || isProductionEnvironment() || !isUnsafeLocalEnvironment()) {
            return STANDARD_PROFILE;
        }
        return FULL_ACCESS_PROFILE;
    }

    public void setPermissionProfile(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        permissionProfile = switch (normalized) {
            case SAFE_PROFILE, STANDARD_PROFILE, FULL_ACCESS_PROFILE -> normalized;
            default -> STANDARD_PROFILE;
        };
    }

    public boolean isNetworkDefaultEnabled() {
        return networkDefaultEnabled && !isSafeProfile();
    }

    public void setNetworkDefaultEnabled(boolean value) {
        networkDefaultEnabled = value;
    }

    /** full_access 是否被显式允许；production/prod 仍会降级为 Labex 标准 profile。 */
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

    public boolean isStandardProfile() {
        return STANDARD_PROFILE.equals(getPermissionProfile());
    }

    public boolean isFullAccessProfile() {
        return FULL_ACCESS_PROFILE.equals(getPermissionProfile());
    }

    public boolean isSafeProfile() {
        return SAFE_PROFILE.equals(getPermissionProfile());
    }

    private boolean isProductionEnvironment() {
        return environment != null
                && (environment.matchesProfiles("prod") || environment.matchesProfiles("production"));
    }

    private boolean isUnsafeLocalEnvironment() {
        return environment == null || environment.matchesProfiles("unsafe-local");
    }
}
