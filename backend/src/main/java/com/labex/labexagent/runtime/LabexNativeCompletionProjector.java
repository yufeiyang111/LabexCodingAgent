package com.labex.labexagent.runtime;

import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import org.springframework.stereotype.Service;

/**
 * 原生运行时的最终答复投影策略。
 *
 * <p>模型答复可见性、服务器验证结论和任务终态是三个独立事实：原生 profile 不用文本长度、
 * Markdown 结构或“是否调用过工具”来吞掉非空答复；但未满足的服务器验证仍不能被投影为
 * verified completed。</p>
 */
@Service
public final class LabexNativeCompletionProjector {

    public boolean usesLegacyTextFinalGuards(AgentRuntimeProfile runtimeProfile) {
        return runtimeProfile != AgentRuntimeProfile.LABEX_NATIVE;
    }

    public Projection project(AgentRuntimeProfile runtimeProfile,
                              AgentRunFinalizer.CompletionAssessment assessment) {
        if (assessment == null || assessment.allowed()) {
            return new Projection(Disposition.ACCEPTED);
        }
        if (runtimeProfile == AgentRuntimeProfile.LABEX_NATIVE) {
            return new Projection(Disposition.VISIBLE_UNVERIFIED);
        }
        return new Projection(Disposition.BLOCKED);
    }

    public enum Disposition {
        ACCEPTED,
        BLOCKED,
        VISIBLE_UNVERIFIED
    }

    public record Projection(Disposition disposition) {
        public boolean publishFinal() {
            return disposition == Disposition.ACCEPTED || disposition == Disposition.VISIBLE_UNVERIFIED;
        }

        public boolean requiresModelRecovery() {
            return disposition == Disposition.BLOCKED;
        }

        public boolean completesTask() {
            return disposition == Disposition.ACCEPTED;
        }
    }
}
