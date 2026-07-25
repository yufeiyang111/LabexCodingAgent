package com.labex.labexagent.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Forces the state enum and transition table to link during startup.
 * This turns stale or mixed class artifacts into a startup failure instead of a later agent-thread failure.
 */
@Component
public class AgentRunStateStartupVerifier implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentRunStateStartupVerifier.class);

    @Override
    public void run(ApplicationArguments args) {
        AgentRunState[] states = AgentRunState.values();
        for (AgentRunState state : states) {
            AgentRunStateMachine.canTransition(state, state);
        }
        log.info("Verified AgentRunState/AgentRunStateMachine linkage for {} states", states.length);
    }
}
