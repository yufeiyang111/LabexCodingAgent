package com.labex.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.secret.SecretStore;
import org.junit.jupiter.api.Test;

class AgentModelConfigRequestOptionsTest {

    @Test
    void persistsValidRequestOptionsJsonObject() {
        AgentModelConfig config = new AgentModelConfig();
        config.setConfigId(9);
        RecordingService service = new RecordingService(config);

        String options = "{\"reasoning\":{\"path\":\"/extra_body/thinking\"}}";
        AgentModelConfig updated = service.updateRequestOptions(42, 9, options);

        assertEquals(options, updated.getRequestOptionsJson());
        assertTrue(service.updated);
    }

    @Test
    void blankInputClearsStoredOptions() {
        AgentModelConfig config = new AgentModelConfig();
        config.setConfigId(9);
        config.setRequestOptionsJson("{\"reasoning\":{\"path\":\"/reasoning_effort\"}}");
        RecordingService service = new RecordingService(config);

        AgentModelConfig updated = service.updateRequestOptions(42, 9, "   ");

        assertNull(updated.getRequestOptionsJson());
        assertTrue(service.updated);
    }

    @Test
    void rejectsMalformedJsonWithoutPersistingAChange() {
        AgentModelConfig config = new AgentModelConfig();
        config.setConfigId(9);
        RecordingService service = new RecordingService(config);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateRequestOptions(42, 9, "{\"reasoning\": "));
        assertFalse(service.updated);
    }

    @Test
    void rejectsNonObjectJsonWithoutPersistingAChange() {
        AgentModelConfig config = new AgentModelConfig();
        config.setConfigId(9);
        RecordingService service = new RecordingService(config);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateRequestOptions(42, 9, "[\"reasoning\"]"));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateRequestOptions(42, 9, "\"reasoning\""));
        assertFalse(service.updated);
    }

    @Test
    void rejectsUnknownConfig() {
        RecordingService service = new RecordingService(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateRequestOptions(42, 9, "{}"));
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
