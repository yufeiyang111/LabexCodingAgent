package com.labex.labexagent.runtime.profile;

import java.util.Arrays;

/**
 * 对话与任务绑定的 Labex 运行时 profile。
 *
 * <p>持久化空值仅用于兼容历史会话，读取时固定解释为 legacy；公共请求中的非空值必须是已知 profile。</p>
 */
public enum AgentRuntimeProfile {
    LABEX_LEGACY("labex-legacy"),
    LABEX_NATIVE("labex-native");

    private final String persistedValue;

    AgentRuntimeProfile(String persistedValue) {
        this.persistedValue = persistedValue;
    }

    public String persistedValue() {
        return this.persistedValue;
    }

    public static AgentRuntimeProfile fromPersisted(String value) {
        if (value == null || value.isBlank()) {
            return LABEX_LEGACY;
        }
        return requireKnown(value);
    }

    public static AgentRuntimeProfile requireKnown(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Runtime profile is required");
        }
        return Arrays.stream(values())
                .filter(profile -> profile.persistedValue.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported Labex runtime profile: " + value));
    }
}