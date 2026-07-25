package com.labex.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.secret.SecretStore;
import org.junit.jupiter.api.Test;

class AgentModelConfigCapabilitiesTest {

    @Test
    void persistsOpenCodeStyleReasoningEffortAndMultimodalCapability() {
        AgentModelConfig config = new AgentModelConfig();
        config.setConfigId(9);
        RecordingService service = new RecordingService(config);

        AgentModelConfig updated = service.updateCapabilities(42, 9, "xhigh", true);

        assertEquals("xhigh", updated.getReasoningEffort());
        assertEquals(1, updated.getImageInputEnabled());
        assertTrue(service.updated);
    }

    @Test
    void rejectsUnsupportedReasoningEffortWithoutPersistingAChange() {
        AgentModelConfig config = new AgentModelConfig();
        config.setConfigId(9);
        RecordingService service = new RecordingService(config);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateCapabilities(42, 9, "maximum", false));

        assertFalse(service.updated);
    }

    private static final class RecordingService extends AgentModelConfigService {
        private final AgentModelConfig config;
        private boolean updated;

        private RecordingService(AgentModelConfig config) {
            super(mock(SecretStore.class));
            this.config = config;
        }

        @Override
        public AgentModelConfig getOwned(Integer studentId, Integer configId) {
            return config;
        }

        @Override
        public boolean updateById(AgentModelConfig entity) {
            updated = true;
            return true;
        }
    }
}
