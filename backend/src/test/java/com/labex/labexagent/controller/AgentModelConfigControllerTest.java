package com.labex.labexagent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.labex.config.AdditiveSchemaMigrator;
import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.secret.SecretStore;
import com.labex.service.AgentModelConfigService;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentModelConfigControllerTest {
    private final AgentModelConfigService configService = mock(AgentModelConfigService.class);
    private final OutboundUrlPolicy outboundUrlPolicy = mock(OutboundUrlPolicy.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgentModelConfigController(
            configService,
            mock(LlmProviderFactory.class),
            outboundUrlPolicy
    )).build();

    @Test
    void createForwardsContextWindowTokensFromJson() throws Exception {
        AgentModelConfig created = new AgentModelConfig();
        created.setContextWindowTokens(16_384);
        when(configService.create(
                eq(42), eq("local"), eq("openai_compatible"), eq("gpt-test"),
                eq("api-key"), eq("https://api.example.test"), eq(2_048),
                eq(16_384), eq(0.2), eq(false)))
                .thenReturn(created);

        mockMvc.perform(post("/student/model-configs")
                        .with(authenticatedAs(42))
                        .contentType("application/json")
                        .content("""
                                {"configName":"local","provider":"openai_compatible","modelName":"gpt-test",
                                "apiKey":"api-key","baseUrl":"https://api.example.test","maxTokens":2048,
                                "contextWindowTokens":16384,"temperature":0.2,"isDefault":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<Integer> contextWindowTokens = ArgumentCaptor.forClass(Integer.class);
        verify(configService).create(
                eq(42), eq("local"), eq("openai_compatible"), eq("gpt-test"),
                eq("api-key"), eq("https://api.example.test"), eq(2_048),
                contextWindowTokens.capture(), eq(0.2), eq(false));
        assertEquals(16_384, contextWindowTokens.getValue());
    }

    @Test
    void createForwardsExplicitCompactionPolicyFromJson() throws Exception {
        AgentModelConfig created = new AgentModelConfig();
        created.setContextWindowTokens(16_384);
        when(configService.createWithCompactionPolicy(
                eq(42), eq("local"), eq("openai_compatible"), eq("gpt-test"),
                eq("api-key"), eq("https://api.example.test"), eq(2_048), eq(16_384),
                eq(0.2), eq(false), eq(false), eq(true), eq(true), eq(3), eq(3_000), eq(1_000)))
                .thenReturn(created);

        mockMvc.perform(post("/student/model-configs")
                        .with(authenticatedAs(42))
                        .contentType("application/json")
                        .content("""
                                {"configName":"local","provider":"openai_compatible","modelName":"gpt-test",
                                "apiKey":"api-key","baseUrl":"https://api.example.test","maxTokens":2048,
                                "contextWindowTokens":16384,"temperature":0.2,"isDefault":false,
                                "compactionAuto":true,"compactionPrune":true,"compactionTailTurns":3,
                                "compactionPreserveRecentTokens":3000,"compactionReservedTokens":1000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(configService).createWithCompactionPolicy(
                eq(42), eq("local"), eq("openai_compatible"), eq("gpt-test"),
                eq("api-key"), eq("https://api.example.test"), eq(2_048), eq(16_384),
                eq(0.2), eq(false), eq(false), eq(true), eq(true), eq(3), eq(3_000), eq(1_000));
    }

    @Test
    void createForwardsDedicatedCompactionModelConfigIdFromJson() throws Exception {
        AgentModelConfig created = new AgentModelConfig();
        created.setContextWindowTokens(16_384);
        when(configService.createWithCompactionPolicy(
                eq(42), eq("local"), eq("openai_compatible"), eq("gpt-test"),
                eq("api-key"), eq("https://api.example.test"), eq(2_048), eq(16_384),
                eq(0.2), eq(false), eq(false), eq(true), eq(false), eq(2), isNull(), isNull(), eq(9)))
                .thenReturn(created);

        mockMvc.perform(post("/student/model-configs")
                        .with(authenticatedAs(42))
                        .contentType("application/json")
                        .content("""
                                {"configName":"local","provider":"openai_compatible","modelName":"gpt-test",
                                "apiKey":"api-key","baseUrl":"https://api.example.test","maxTokens":2048,
                                "contextWindowTokens":16384,"temperature":0.2,"isDefault":false,
                                "compactionAuto":true,"compactionPrune":false,"compactionTailTurns":2,
                                "compactionModelConfigId":9}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(configService).createWithCompactionPolicy(
                eq(42), eq("local"), eq("openai_compatible"), eq("gpt-test"),
                eq("api-key"), eq("https://api.example.test"), eq(2_048), eq(16_384),
                eq(0.2), eq(false), eq(false), eq(true), eq(false), eq(2), isNull(), isNull(), eq(9));
    }

    @Test
    void createRejectsContextWindowTokensThatDoNotExceedMaxTokens() throws Exception {
        mockMvc.perform(post("/student/model-configs")
                        .with(authenticatedAs(42))
                        .contentType("application/json")
                        .content("{\"maxTokens\":8192,\"contextWindowTokens\":8192}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1));

        verifyNoInteractions(configService);
    }

    @Test
    void serviceCreateValidatesContextWindowTokensAgainstDefaultMaxTokens() {
        RecordingAgentModelConfigService service = new RecordingAgentModelConfigService(null);

        assertThrows(IllegalArgumentException.class, () -> service.create(
                42, "local", "openai_compatible", "gpt-test", "", "https://api.example.test",
                null, 8_192, null, false));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                42, "local", "openai_compatible", "gpt-test", "", "https://api.example.test",
                null, 32_768, null, false));

        assertFalse(service.saved);
    }

    @Test
    void serviceCreateRejectsCompactionReserveThatConsumesTheInputCapacity() {
        RecordingAgentModelConfigService service = new RecordingAgentModelConfigService(null);

        assertThrows(IllegalArgumentException.class, () -> service.createWithCompactionPolicy(
                42, "local", "openai_compatible", "gpt-test", "", "https://api.example.test",
                2_048, 16_384, null, false, false, true, false, 2, null, 14_336));

        assertFalse(service.saved);
    }

    @Test
    void serviceUpdateValidatesContextWindowTokensAgainstExistingMaxTokens() {
        AgentModelConfig existing = configWithTokenLimits(16_384, null);
        RecordingAgentModelConfigService service = new RecordingAgentModelConfigService(existing);

        assertThrows(IllegalArgumentException.class, () -> service.update(
                42, 1, null, null, null, null, null, null, 8_192, null, null));

        assertFalse(service.updated);
    }

    @Test
    void serviceUpdateValidatesMaxTokensAgainstExistingContextWindowTokens() {
        AgentModelConfig existing = configWithTokenLimits(2_048, 8_192);
        RecordingAgentModelConfigService service = new RecordingAgentModelConfigService(existing);

        assertThrows(IllegalArgumentException.class, () -> service.update(
                42, 1, null, null, null, null, null, 8_192, null, null, null));

        assertFalse(service.updated);
    }

    @Test
    void serviceOldUpdateOverloadPreservesExistingContextWindowTokens() {
        AgentModelConfig existing = configWithTokenLimits(2_048, 8_192);
        RecordingAgentModelConfigService service = new RecordingAgentModelConfigService(existing);

        AgentModelConfig updated = service.update(
                42, 1, null, null, null, null, null, null, 0.2, null);

        assertSame(existing, updated);
        assertEquals(8_192, updated.getContextWindowTokens());
        assertEquals(0.2, updated.getTemperature());
    }

    @Test
    void migratorIgnoresConfirmedDuplicateColumnRace() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet missing = mock(ResultSet.class);
        ResultSet present = mock(ResultSet.class);
        AtomicInteger contextWindowLookups = new AtomicInteger();

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("labex");
        when(missing.next()).thenReturn(false);
        when(present.next()).thenReturn(true);
        when(metadata.getColumns(any(), isNull(), anyString(), anyString())).thenAnswer(invocation -> {
            String columnName = invocation.getArgument(3, String.class);
            if (!"context_window_tokens".equals(columnName)) {
                return present;
            }
            return contextWindowLookups.getAndIncrement() < 2 ? missing : present;
        });

        String alterSql = "ALTER TABLE t_agent_model_config ADD COLUMN context_window_tokens INT";
        doThrow(new BadSqlGrammarException("ALTER TABLE", alterSql,
                new SQLException("Duplicate column name 'context_window_tokens'", "42S21", 1060)))
                .when(jdbcTemplate).execute(alterSql);

        new AdditiveSchemaMigrator(jdbcTemplate, dataSource).migrate();

        verify(jdbcTemplate).execute(alterSql);
    }

    @Test
    void migratorRethrowsUnrelatedSqlErrorWhenAddingMissingColumn() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet missing = mock(ResultSet.class);
        ResultSet present = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("labex");
        when(missing.next()).thenReturn(false);
        when(present.next()).thenReturn(true);
        when(metadata.getColumns(any(), isNull(), anyString(), anyString())).thenAnswer(invocation ->
                "context_window_tokens".equals(invocation.getArgument(3, String.class)) ? missing : present);

        String alterSql = "ALTER TABLE t_agent_model_config ADD COLUMN context_window_tokens INT";
        BadSqlGrammarException syntaxError = new BadSqlGrammarException("ALTER TABLE", alterSql,
                new SQLException("You have an error in your SQL syntax", "42000", 1064));
        doThrow(syntaxError).when(jdbcTemplate).execute(alterSql);

        BadSqlGrammarException thrown = assertThrows(BadSqlGrammarException.class,
                () -> new AdditiveSchemaMigrator(jdbcTemplate, dataSource).migrate());

        assertSame(syntaxError, thrown);
        verify(jdbcTemplate).execute(alterSql);
    }

    @Test
    void modelListEndpointAcceptsPostRequestsWithoutTreatingPathAsConfigId() throws Exception {
        mockMvc.perform(post("/student/model-configs/model-list")
                        .contentType("application/json")
                        .content("{\"modelsUrl\":\"http://localhost/models\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(false));
    }

    @Test
    void modelListUsesSharedOutboundPolicyBeforeOpeningConnection() throws Exception {
        String modelsUrl = "https://models.example.test/models";
        when(outboundUrlPolicy.validate(modelsUrl)).thenThrow(
                new OutboundUrlPolicy.RejectedOutboundUrlException(
                        OutboundUrlPolicy.RejectionReason.BLOCKED_ADDRESS,
                        "URL resolves to a blocked address"));

        mockMvc.perform(post("/student/model-configs/model-list")
                        .contentType("application/json")
                        .content("{\"modelsUrl\":\"" + modelsUrl + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(false));

        verify(outboundUrlPolicy).validate(modelsUrl);
    }

    private AgentModelConfig configWithTokenLimits(int maxTokens, Integer contextWindowTokens) {
        AgentModelConfig config = new AgentModelConfig();
        config.setMaxTokens(maxTokens);
        config.setContextWindowTokens(contextWindowTokens);
        return config;
    }

    private RequestPostProcessor authenticatedAs(int studentId) {
        return request -> {
            request.setUserPrincipal(new UsernamePasswordAuthenticationToken(String.valueOf(studentId), null));
            return request;
        };
    }

    private static final class RecordingAgentModelConfigService extends AgentModelConfigService {
        private final AgentModelConfig ownedConfig;
        private boolean saved;
        private boolean updated;

        private RecordingAgentModelConfigService(AgentModelConfig ownedConfig) {
            super(mock(SecretStore.class));
            this.ownedConfig = ownedConfig;
        }

        @Override
        public AgentModelConfig getOwned(Integer studentId, Integer configId) {
            return ownedConfig;
        }

        @Override
        public boolean save(AgentModelConfig entity) {
            saved = true;
            return true;
        }

        @Override
        public boolean updateById(AgentModelConfig entity) {
            updated = true;
            return true;
        }
    }
}
