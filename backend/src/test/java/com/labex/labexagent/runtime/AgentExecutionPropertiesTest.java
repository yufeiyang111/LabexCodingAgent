package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentExecutionPropertiesTest {

    @Test
    void defaultsToLabexStandardWithWorkerNetworkEnabled() {
        AgentExecutionProperties properties = new AgentExecutionProperties();

        assertThat(properties.getPermissionProfile()).isEqualTo("labex-standard");
        assertThat(properties.isNetworkDefaultEnabled()).isTrue();
    }

    @Test
    void safeDisablesDefaultNetworkAndKeepsCompatibilityMode() {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setPermissionProfile("safe");

        assertThat(properties.isSafeProfile()).isTrue();
        assertThat(properties.isNetworkDefaultEnabled()).isFalse();
    }

    @Test
    void fullAccessRequiresExplicitLocalOptIn() {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setPermissionProfile("full_access");

        assertThat(properties.getPermissionProfile()).isEqualTo("labex-standard");
        properties.setAllowFullAccess(true);
        assertThat(properties.getPermissionProfile()).isEqualTo("full_access");
    }

    @Test
    void productionAlwaysDowngradesFullAccessToLabexStandard() {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setPermissionProfile("full_access");
        properties.setAllowFullAccess(true);
        org.springframework.core.env.Environment environment = org.mockito.Mockito.mock(
                org.springframework.core.env.Environment.class);
        org.mockito.Mockito.when(environment.matchesProfiles("prod")).thenReturn(false);
        org.mockito.Mockito.when(environment.matchesProfiles("production")).thenReturn(true);

        properties.setEnvironment(environment);

        assertThat(properties.getPermissionProfile()).isEqualTo("labex-standard");
        assertThat(properties.isFullAccessProfile()).isFalse();
    }
}
