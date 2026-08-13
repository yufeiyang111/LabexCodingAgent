package com.labex.labexagent.run;

/**
 * 运行所需配置永久不可用时的结构化失败。
 *
 * <p>恢复/准备阶段发现任务持久化的模型配置缺失、被禁用或从未持久化时抛出；
 * 调用方必须把任务迁移到终态（FAILED），禁止静默回退到当前默认配置，
 * 也禁止让调度器无限重复投递。reason code 稳定且安全，不回显任何配置细节或秘密。
 */
public final class AgentRunConfigurationException extends RuntimeException {
    private final Reason reason;

    public AgentRunConfigurationException(Reason reason) {
        super("Agent run configuration failure: " + reason.code());
        this.reason = reason;
    }

    public AgentRunConfigurationException(Reason reason, String detail) {
        super("Agent run configuration failure: " + reason.code()
                + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** 安全、稳定的失败原因码；用于事件 payload 与前端投影，不回显配置原文。 */
    public enum Reason {
        MODEL_CONFIG_MISSING("model-config-missing"),
        MODEL_CONFIG_DISABLED("model-config-disabled"),
        MODEL_CONFIG_NOT_PERSISTED("model-config-not-persisted");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
