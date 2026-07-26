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
