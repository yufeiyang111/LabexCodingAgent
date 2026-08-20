package com.labex.labexagent.run;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class AgentRunStateMachine {
    private static final Map<AgentRunState, Set<AgentRunState>> TRANSITIONS = transitions();

    private AgentRunStateMachine() {
    }

    public static boolean canTransition(AgentRunState from, AgentRunState to) {
        return from != null && to != null && TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(AgentRunState from, AgentRunState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Illegal agent run transition: " + from + " -> " + to);
        }
    }

    private static Map<AgentRunState, Set<AgentRunState>> transitions() {
        Map<AgentRunState, Set<AgentRunState>> transitions = new EnumMap<>(AgentRunState.class);
        transitions.put(AgentRunState.QUEUED, EnumSet.of(
                AgentRunState.PREPARING,
                AgentRunState.RECOVERING,
                AgentRunState.CANCELLING,
                AgentRunState.FAILED,
                AgentRunState.WAITING_WORKSPACE));
        transitions.put(AgentRunState.PREPARING, EnumSet.of(
                AgentRunState.RUNNING,
                AgentRunState.RECOVERING,
                AgentRunState.WAITING_ENVIRONMENT,
                AgentRunState.CANCELLING,
                AgentRunState.FAILED,
                AgentRunState.WAITING_WORKSPACE));
        transitions.put(AgentRunState.RUNNING, EnumSet.of(
                AgentRunState.WAITING_APPROVAL,
                AgentRunState.WAITING_USER,
                AgentRunState.WAITING_ENVIRONMENT,
                AgentRunState.WAITING_RECOVERY,
                AgentRunState.RETRYING,
                AgentRunState.RECOVERING,
                AgentRunState.CANCELLING,
                AgentRunState.COMPLETED,
                AgentRunState.FAILED));
        transitions.put(AgentRunState.WAITING_APPROVAL, EnumSet.of(
                AgentRunState.RUNNING,
                AgentRunState.RECOVERING,
                AgentRunState.CANCELLING,
                AgentRunState.FAILED));
        transitions.put(AgentRunState.WAITING_USER, EnumSet.of(
                AgentRunState.RUNNING,
                AgentRunState.RECOVERING,
                AgentRunState.CANCELLING,
                AgentRunState.FAILED));
        transitions.put(AgentRunState.WAITING_WORKSPACE, EnumSet.of(
                AgentRunState.QUEUED, AgentRunState.CANCELLING, AgentRunState.FAILED));
        transitions.put(AgentRunState.WAITING_ENVIRONMENT, EnumSet.of(
                AgentRunState.QUEUED, AgentRunState.CANCELLING, AgentRunState.FAILED));
        transitions.put(AgentRunState.WAITING_RECOVERY, EnumSet.of(
                AgentRunState.QUEUED, AgentRunState.CANCELLING, AgentRunState.FAILED));
        transitions.put(AgentRunState.RECOVERING, EnumSet.of(
                AgentRunState.PREPARING,
                AgentRunState.RUNNING,
                AgentRunState.WAITING_WORKSPACE,
                AgentRunState.CANCELLING,
                AgentRunState.FAILED));
        transitions.put(AgentRunState.RETRYING, EnumSet.of(
                AgentRunState.RUNNING,
                AgentRunState.RECOVERING,
                AgentRunState.CANCELLING,
                AgentRunState.CANCELLED,
                AgentRunState.FAILED));
        transitions.put(AgentRunState.CANCELLING, EnumSet.of(AgentRunState.CANCELLED));
        return Map.copyOf(transitions);
    }
}
