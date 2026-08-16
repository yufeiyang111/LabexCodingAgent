package com.labex.labexagent.runtime.profile;

import com.labex.entity.AgentTask;

/**
 * 解析一次执行实际绑定的运行时 profile。
 *
 * <p>任务创建后，task snapshot 是运行时唯一权威来源。恢复时不得相信旧 request payload、
 * 浏览器重新提交的 profile 或 conversation 的后续字段变化；历史空值稳定兼容为 legacy。</p>
 */
public final class AgentRuntimeProfileResolver {

    private AgentRuntimeProfileResolver() {
    }

    public static AgentRuntimeProfile resolveExecutionProfile(AgentTask task) {
        if (task == null) {
            throw new IllegalArgumentException("Agent task is required to resolve the runtime profile");
        }
        return AgentRuntimeProfile.fromPersisted(task.getRuntimeProfile());
    }
}
