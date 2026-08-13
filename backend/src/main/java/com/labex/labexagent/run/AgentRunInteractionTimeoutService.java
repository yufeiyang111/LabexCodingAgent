package com.labex.labexagent.run;

import com.labex.entity.AgentRunInteraction;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Expires durable user/permission interactions and terminates only runs that are still waiting for them. */
@Service
public class AgentRunInteractionTimeoutService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunInteractionTimeoutService.class);
    private static final int BATCH_SIZE = 100;

    private final AgentRunInteractionService interactionService;
    private final AgentRunLifecycleService lifecycleService;

    public AgentRunInteractionTimeoutService(AgentRunInteractionService interactionService,
                                             AgentRunLifecycleService lifecycleService) {
        this.interactionService = interactionService;
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${labex-agent.interaction-timeout-poll-interval-ms:30000}")
    public void expireScheduled() {
        expireAvailable();
    }

    public int expireAvailable() {
        return expireAvailable(LocalDateTime.now());
    }

    @Transactional(rollbackFor = Exception.class)
    public int expireAvailable(LocalDateTime now) {
        List<AgentRunInteraction> expired = interactionService.claimExpired(now, BATCH_SIZE);
        for (AgentRunInteraction interaction : expired) {
            if (isGentleExpiry(interaction)) {
                // 网络/权限审批过期走温和恢复：不把任务直接置为失败。
                // claimExpired 已将交互置为 timed_out，AgentRunResumeScheduler 的轮询
                // （selectResolvedAwaitingResume 已含 timed_out）会接管并恢复任务，
                // 模型收到"交互已过期、视为未批准"的提示后重新评估策略。
                continue;
            }
            AgentRunState expected = expectedWaitingState(interaction.getInteractionType());
            Map<String, Object> payload = timeoutPayload(interaction, now);
            boolean transitioned = lifecycleService.transitionIfCurrent(
                    interaction.getTaskId(),
                    expected,
                    AgentRunState.FAILED,
                    "RUN_INTERACTION_TIMED_OUT",
                    payload,
                    "Interaction timed out",
                    summary(interaction),
                    "interaction-timeout-" + interaction.getInteractionId());
            if (!transitioned) {
                log.info("Expired interaction {} did not fail task {} because the run had already left {}",
                        interaction.getInteractionId(), interaction.getTaskId(), expected.persistedStatus());
            }
        }
        return expired.size();
    }

    /**
     * 网络/权限审批与 config_proposal 决策过期不杀任务，由恢复路径温和处理；
     * question 仍按超时失败（用户不回答，任务不应无限挂起）。
     * config_proposal 的 timed_out 与 permission/network 语义一致：任务保持等待态，
     * 由决策路径的 claim 谓词（接受 timed_out）或同幂等键重放恢复。
     */
    private boolean isGentleExpiry(AgentRunInteraction interaction) {
        if (interaction == null || interaction.getInteractionType() == null) {
            return false;
        }
        return "network".equals(interaction.getInteractionType())
                || "permission".equals(interaction.getInteractionType())
                || AgentRunInteraction.TYPE_CONFIG_PROPOSAL.equals(interaction.getInteractionType());
    }

    private AgentRunState expectedWaitingState(String interactionType) {
        return "permission".equalsIgnoreCase(interactionType)
                ? AgentRunState.WAITING_APPROVAL : AgentRunState.WAITING_USER;
    }

    private String summary(AgentRunInteraction interaction) {
        return "permission".equalsIgnoreCase(interaction.getInteractionType())
                ? "Timed out while waiting for approval"
                : "Timed out while waiting for user input";
    }

    private Map<String, Object> timeoutPayload(AgentRunInteraction interaction, LocalDateTime now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interactionId", interaction.getInteractionId());
        payload.put("interactionType", interaction.getInteractionType());
        payload.put("expiresTime", interaction.getExpiresTime() == null ? "" : interaction.getExpiresTime().toString());
        payload.put("timedOutAt", now == null ? LocalDateTime.now().toString() : now.toString());
        return payload;
    }
}
