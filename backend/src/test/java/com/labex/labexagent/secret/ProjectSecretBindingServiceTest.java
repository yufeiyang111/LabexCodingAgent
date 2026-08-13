package com.labex.labexagent.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.entity.AgentProjectSecretBinding;
import com.labex.entity.StudentProject;
import com.labex.labexagent.controller.AgentProjectConfigController;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalDetail;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalException;
import com.labex.labexagent.projectconfig.AgentProjectConfigRevisionService;
import com.labex.labexagent.secret.ProjectSecretBindingService.SecretInputRequest;
import com.labex.labexagent.secret.ProjectSecretBindingService.SecretInputResult;
import com.labex.labexagent.secret.ScopedSecretLeaseService.ScopedSecretLease;
import com.labex.mapper.AgentProjectSecretBindingMapper;
import com.labex.service.StudentProjectService;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectSecretBindingServiceTest {

    private static final String SENTINEL = "sk-secret-sentinel-7f3a9c";
    private static final String SENTINEL_MCP = "Bearer mcp-secret-sentinel-2b8d";

    private AgentProjectSecretBindingMapper bindingMapper;
    private StudentProjectService studentProjectService;
    private AgentProjectConfigProposalService proposalService;
    private LocalEnvelopeSecretStore secrets;
    private ProjectSecretBindingService bindingService;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory factory = factory(dataSource);
        SqlSession session = factory.openSession(true);
        bindingMapper = session.getMapper(AgentProjectSecretBindingMapper.class);
        studentProjectService = mock(StudentProjectService.class);
        proposalService = mock(AgentProjectConfigProposalService.class);
        secrets = new LocalEnvelopeSecretStore(masterKey(), false);
        bindingService = new ProjectSecretBindingService(studentProjectService, proposalService,
                bindingMapper, secrets);
    }

    @Test
    void encryptsImmediatelyAndReturnsOnlyAliasConfiguredStatus() {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        SecretInputResult result = bindingService.inputSecret(7, 12, 88L,
                new SecretInputRequest("MCP_TS_CREDENTIAL", SENTINEL_MCP, "input-1", 3600));

        assertThat(result.alias()).isEqualTo("p12_MCP_TS_CREDENTIAL");
        assertThat(result.configured()).isTrue();

        AgentProjectSecretBinding stored = byIdempotencyKey(12, "input-1");
        assertThat(stored.getProposalId()).isEqualTo(88L);
        assertThat(stored.getFieldId()).isEqualTo("MCP_TS_CREDENTIAL");
        assertThat(stored.getAlias()).isEqualTo("p12_MCP_TS_CREDENTIAL");
        assertThat(stored.getTtlSeconds()).isEqualTo(3600);
        assertThat(stored.getConfigured()).isEqualTo(1);
        assertThat(stored.getEncryptedValue()).isNotEqualTo(SENTINEL_MCP);
        assertThat(stored.getEncryptedValue()).doesNotContain(SENTINEL_MCP);
        try (SecretStore.SecretLease lease = secrets.open(
                ProjectSecretBindingService.BINDING_SCOPE, stored.getEncryptedValue())) {
            assertThat(lease.value()).isEqualTo(SENTINEL_MCP);
        }
    }

    @Test
    void bindingCreatedByServiceIsOpenableThroughLeaseService() {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        bindingService.inputSecret(7, 12, 88L,
                new SecretInputRequest("DB_PASSWORD", SENTINEL, "input-1", 3600));

        ScopedSecretLeaseService leaseService = new ScopedSecretLeaseService(bindingMapper, secrets);
        try (ScopedSecretLease lease = leaseService.issue(7, 12, 71L, 3L, "worker-run-1",
                Set.of("DB_PASSWORD"), Duration.ofMinutes(1))) {
            assertThat(lease.value("DB_PASSWORD")).isEqualTo(SENTINEL);
            assertThat(lease.bindingIds()).containsKey("DB_PASSWORD");
        }
        assertThat(byIdempotencyKey(12, "input-1").getAlias())
                .isEqualTo(ProjectSecretBindingService.aliasFor(12, "DB_PASSWORD"));
    }

    @Test
    void plaintextIsAbsentFromAllSerializedDomainObjects() throws Exception {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        SecretInputResult result = bindingService.inputSecret(7, 12, 88L,
                new SecretInputRequest("MCP_TS_CREDENTIAL", SENTINEL_MCP, "input-1", 3600));
        AgentProjectSecretBinding stored = byIdempotencyKey(12, "input-1");

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        assertThat(mapper.writeValueAsString(stored)).doesNotContain(SENTINEL_MCP);
        assertThat(mapper.writeValueAsString(result)).doesNotContain(SENTINEL_MCP);
        assertThat(mapper.writeValueAsString(toControllerResponse(result))).doesNotContain(SENTINEL_MCP);
        assertThat(stored.toString()).doesNotContain(SENTINEL_MCP);
        assertThat(result.toString()).doesNotContain(SENTINEL_MCP);
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheFirstDurableBinding() {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        SecretInputResult first = bindingService.inputSecret(7, 12, 88L,
                new SecretInputRequest("FIELD_A", SENTINEL, "input-1", 3600));
        SecretInputResult repeated = bindingService.inputSecret(7, 12, 88L,
                new SecretInputRequest("FIELD_B", "different-" + SENTINEL, "input-1", 60));

        assertThat(repeated.bindingId()).isEqualTo(first.bindingId());
        assertThat(repeated.alias()).isEqualTo(first.alias());
        assertThat(bindingMapper.selectCount(new QueryWrapper<AgentProjectSecretBinding>()
                .eq("project_id", 12))).isEqualTo(1);
        assertThat(byIdempotencyKey(12, "input-1").getEncryptedValue()).doesNotContain(SENTINEL);
    }

    @Test
    void foreignProjectIsIndistinguishableFromNotFound() {
        when(studentProjectService.getOwnedProject(7, 999)).thenReturn(null);

        ProposalException failure = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                bindingService.inputSecret(7, 999, 88L,
                        new SecretInputRequest("FIELD_A", SENTINEL, "input-1", 3600)),
                ProposalException.class);

        assertThat(failure.httpStatus()).isEqualTo(404);
        assertThat(failure.getMessage()).isEqualTo("Project not found");
        assertThat(failure.getMessage()).doesNotContain(SENTINEL);
    }

    @Test
    void foreignProposalIsIndistinguishableFromNotFound() {
        registerOwnedProject(7, 12);
        when(proposalService.getProposal(7, 12, 404L)).thenThrow(new ProposalException(
                404, "NOT_FOUND", "Project not found"));

        ProposalException failure = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                bindingService.inputSecret(7, 12, 404L,
                        new SecretInputRequest("FIELD_A", SENTINEL, "input-1", 3600)),
                ProposalException.class);

        assertThat(failure.httpStatus()).isEqualTo(404);
        assertThat(failure.getMessage()).isEqualTo("Project not found");
        assertThat(failure.getMessage()).doesNotContain(SENTINEL);
    }

    @Test
    void expiredProposalRejectsSecretInputWith410() {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));

        ProposalException failure = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                bindingService.inputSecret(7, 12, 88L,
                        new SecretInputRequest("FIELD_A", SENTINEL, "input-1", 3600)),
                ProposalException.class);

        assertThat(failure.httpStatus()).isEqualTo(410);
        assertThat(failure.reasonCode()).isEqualTo("PROPOSAL_EXPIRED");
        assertThat(failure.getMessage()).doesNotContain(SENTINEL);
        assertThat(bindingMapper.selectCount(new QueryWrapper<AgentProjectSecretBinding>()
                .eq("project_id", 12))).isZero();
    }

    @Test
    void decidedProposalRejectsSecretInputWith409() {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "approved", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        ProposalException failure = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                bindingService.inputSecret(7, 12, 88L,
                        new SecretInputRequest("FIELD_A", SENTINEL, "input-1", 3600)),
                ProposalException.class);

        assertThat(failure.httpStatus()).isEqualTo(409);
        assertThat(failure.reasonCode()).isEqualTo("PROPOSAL_NOT_PENDING");
        assertThat(failure.getMessage()).doesNotContain(SENTINEL);
        assertThat(bindingMapper.selectCount(new QueryWrapper<AgentProjectSecretBinding>()
                .eq("project_id", 12))).isZero();
    }

    @Test
    void rebindingAnAlreadyBoundFieldIsRejectedWith409() {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        bindingService.inputSecret(7, 12, 88L,
                new SecretInputRequest("FIELD_A", SENTINEL, "input-1", 3600));

        ProposalException failure = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                bindingService.inputSecret(7, 12, 88L,
                        new SecretInputRequest("FIELD_A", "rotated-" + SENTINEL, "input-2", 3600)),
                ProposalException.class);

        assertThat(failure.httpStatus()).isEqualTo(409);
        assertThat(failure.reasonCode()).isEqualTo("ALIAS_ALREADY_BOUND");
        assertThat(failure.getMessage()).doesNotContain(SENTINEL);
        assertThat(bindingMapper.selectCount(new QueryWrapper<AgentProjectSecretBinding>()
                .eq("project_id", 12))).isEqualTo(1);
    }

    @Test
    void ttlIsBoundedAndDefaultsToOneHour() {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        SecretInputResult defaulted = bindingService.inputSecret(7, 12, 88L,
                new SecretInputRequest("FIELD_DEFAULT", SENTINEL, "input-default", null));
        assertThat(defaulted.configured()).isTrue();
        assertThat(byIdempotencyKey(12, "input-default").getTtlSeconds()).isEqualTo(3600);

        SecretInputResult minimal = bindingService.inputSecret(7, 12, 88L,
                new SecretInputRequest("FIELD_MIN", SENTINEL, "input-min", 60));
        assertThat(byIdempotencyKey(12, "input-min").getTtlSeconds()).isEqualTo(60);

        SecretInputResult maximal = bindingService.inputSecret(7, 12, 88L,
                new SecretInputRequest("FIELD_MAX", SENTINEL, "input-max", 86400));
        assertThat(byIdempotencyKey(12, "input-max").getTtlSeconds()).isEqualTo(86400);

        for (int invalid : new int[] {0, 59, 86401}) {
            ProposalException failure = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                    bindingService.inputSecret(7, 12, 88L,
                            new SecretInputRequest("FIELD_" + invalid, SENTINEL, "input-" + invalid, invalid)),
                    ProposalException.class);
            assertThat(failure.httpStatus()).isEqualTo(400);
            assertThat(failure.reasonCode()).isEqualTo("INVALID_TTL");
            assertThat(failure.getMessage()).doesNotContain(SENTINEL);
        }
    }

    @Test
    void expiresAtIsComputedFromTheInjectedClock() {
        registerOwnedProject(7, 12);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-12T02:00:00Z"));
        ownedProposal(7, 12, 88L, "pending",
                LocalDateTime.ofInstant(clock.instant().plus(Duration.ofMinutes(1)), ZoneOffset.UTC));
        ProjectSecretBindingService clocked = new ProjectSecretBindingService(
                studentProjectService, proposalService, bindingMapper, secrets, clock);

        clocked.inputSecret(7, 12, 88L,
                new SecretInputRequest("FIELD_A", SENTINEL, "input-1", 120));

        assertThat(byIdempotencyKey(12, "input-1").getExpiresAt())
                .isEqualTo(LocalDateTime.ofInstant(
                        Instant.parse("2026-08-12T02:02:00Z"), ZoneOffset.UTC));
    }

    @Test
    void malformedFieldIdsAndMissingValuesAreRejectedWithoutEchoing() {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        for (String badField : new String[] {"", "A B", "x=$(id)", "a;b", "1FIELD", "a\u0000b",
                "mcp.ts.credential", "FIELD.A", "FIELD-A"}) {
            ProposalException failure = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                    bindingService.inputSecret(7, 12, 88L,
                            new SecretInputRequest(badField, SENTINEL, "input-" + badField.hashCode(), 3600)),
                    ProposalException.class);
            assertThat(failure.httpStatus()).isEqualTo(400);
            assertThat(failure.reasonCode()).isEqualTo("INVALID_FIELD_ID");
            assertThat(failure.getMessage()).doesNotContain(SENTINEL);
        }

        ProposalException missingValue = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                bindingService.inputSecret(7, 12, 88L,
                        new SecretInputRequest("FIELD_A", "   ", "input-blank", 3600)),
                ProposalException.class);
        assertThat(missingValue.httpStatus()).isEqualTo(400);
        assertThat(missingValue.reasonCode()).isEqualTo("INVALID_SECRET_INPUT");
        assertThat(missingValue.getMessage()).doesNotContain(SENTINEL);

        ProposalException missingField = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                bindingService.inputSecret(7, 12, 88L,
                        new SecretInputRequest(null, SENTINEL, "input-null-field", 3600)),
                ProposalException.class);
        assertThat(missingField.httpStatus()).isEqualTo(400);
        assertThat(missingField.reasonCode()).isEqualTo("INVALID_FIELD_ID");
    }

    @Test
    void oversizedFieldIdIsRejectedBeforeBinding() {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        String fieldIdWithin = "A".repeat(124);
        SecretInputResult bound = bindingService.inputSecret(7, 12, 88L,
                new SecretInputRequest(fieldIdWithin, SENTINEL, "input-boundary", 3600));
        assertThat(bound.alias()).isEqualTo(ProjectSecretBindingService.aliasFor(12, fieldIdWithin));
        assertThat(bound.alias()).hasSizeLessThanOrEqualTo(128);

        String fieldIdOver = "A".repeat(125);
        ProposalException failure = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                bindingService.inputSecret(7, 12, 88L,
                        new SecretInputRequest(fieldIdOver, SENTINEL, "input-over", 3600)),
                ProposalException.class);
        assertThat(failure.httpStatus()).isEqualTo(400);
        assertThat(failure.reasonCode()).isEqualTo("INVALID_FIELD_ID");
        assertThat(bindingMapper.selectCount(new QueryWrapper<AgentProjectSecretBinding>()
                .eq("project_id", 12))).isEqualTo(1);
    }

    @Test
    void controllerAcceptsSecretInputAndReturnsOnlyAliasConfiguredStatus() throws Exception {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        MockMvc mockMvc = mockMvc();

        String body = "{\"fieldId\":\"MCP_TS_CREDENTIAL\",\"value\":\"" + SENTINEL_MCP
                + "\",\"idempotencyKey\":\"input-1\",\"ttlSeconds\":3600}";
        mockMvc.perform(post("/student/projects/12/agent/config/proposals/88/secret-input")
                        .content(body).with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.alias").value("p12_MCP_TS_CREDENTIAL"))
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data.value").doesNotExist())
                .andExpect(jsonPath("$.data.secret").doesNotExist())
                .andExpect(jsonPath("$.data.encryptedValue").doesNotExist());
    }

    @Test
    void controllerNeverEchoesTheSecretValueInAnyResponse() throws Exception {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        MockMvc mockMvc = mockMvc();

        String response = mockMvc.perform(post("/student/projects/12/agent/config/proposals/88/secret-input")
                        .content("{\"fieldId\":\"MCP_TS_CREDENTIAL\",\"value\":\"" + SENTINEL_MCP
                                + "\",\"idempotencyKey\":\"input-1\",\"ttlSeconds\":3600}")
                        .with(authenticatedAs(7)))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SENTINEL_MCP);
    }

    @Test
    void controllerForeignProjectReturnsSafe404WithoutEchoingValue() throws Exception {
        when(studentProjectService.getOwnedProject(7, 999)).thenReturn(null);
        MockMvc mockMvc = mockMvc();

        String response = mockMvc.perform(post("/student/projects/999/agent/config/proposals/88/secret-input")
                        .content("{\"fieldId\":\"FIELD_A\",\"value\":\"" + SENTINEL
                                + "\",\"idempotencyKey\":\"input-1\"}")
                        .with(authenticatedAs(7)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SENTINEL);
        assertThat(response).contains("Project not found");
    }

    @Test
    void controllerMalformedRequestFailsClosedWithoutEchoingValue() throws Exception {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        MockMvc mockMvc = mockMvc();

        String response = mockMvc.perform(post("/student/projects/12/agent/config/proposals/88/secret-input")
                        .content("{\"fieldId\":\"FIELD_A\",\"value\":\"" + SENTINEL)
                        .with(authenticatedAs(7)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SENTINEL);
    }

    @Test
    void controllerRejectsExpiredProposalThroughTheRealServicePath() throws Exception {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "pending", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        MockMvc mockMvc = mockMvc();

        String response = mockMvc.perform(post("/student/projects/12/agent/config/proposals/88/secret-input")
                        .content("{\"fieldId\":\"FIELD_A\",\"value\":\"" + SENTINEL
                                + "\",\"idempotencyKey\":\"input-1\"}")
                        .with(authenticatedAs(7)))
                .andExpect(status().isGone())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SENTINEL);
    }

    @Test
    void controllerRejectsDecidedProposalThroughTheRealServicePath() throws Exception {
        registerOwnedProject(7, 12);
        ownedProposal(7, 12, 88L, "rejected", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        MockMvc mockMvc = mockMvc();

        String response = mockMvc.perform(post("/student/projects/12/agent/config/proposals/88/secret-input")
                        .content("{\"fieldId\":\"FIELD_A\",\"value\":\"" + SENTINEL
                                + "\",\"idempotencyKey\":\"input-1\"}")
                        .with(authenticatedAs(7)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SENTINEL);
    }

    private void ownedProposal(int studentId, int projectId, long proposalId, String status,
                               LocalDateTime expiresTime) {
        when(proposalService.getProposal(studentId, projectId, proposalId)).thenReturn(new ProposalDetail(
                proposalId, status, 1L, "sha256:abc", "tree:xyz", "agent.json",
                "bind a credential", "agent", "student:" + studentId, null, null, null,
                expiresTime, LocalDateTime.now(), null, null, List.of()));
    }

    private void registerOwnedProject(int studentId, int projectId) {
        StudentProject project = new StudentProject();
        project.setProjectId(projectId);
        project.setStudentId(studentId);
        project.setWorkspacePath("D:/labex/workspaces/" + studentId + "/" + projectId);
        when(studentProjectService.getOwnedProject(studentId, projectId)).thenReturn(project);
    }

    private MockMvc mockMvc() {
        AgentProjectConfigRevisionService revisionService = mock(AgentProjectConfigRevisionService.class);
        return MockMvcBuilders.standaloneSetup(
                new AgentProjectConfigController(revisionService, proposalService, bindingService)).build();
    }

    private RequestPostProcessor authenticatedAs(int studentId) {
        return request -> {
            request.setUserPrincipal(new UsernamePasswordAuthenticationToken(String.valueOf(studentId), null));
            return request;
        };
    }

    private Map<String, Object> toControllerResponse(SecretInputResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("alias", result.alias());
        response.put("configured", result.configured());
        return response;
    }

    private AgentProjectSecretBinding byIdempotencyKey(int projectId, String key) {
        return bindingMapper.selectOne(new QueryWrapper<AgentProjectSecretBinding>()
                .eq("project_id", projectId)
                .eq("idempotency_key", key));
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("secret-binding-service", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectSecretBindingMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:secret_binding_service_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_project_secret_binding (
                        binding_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        proposal_id BIGINT NOT NULL,
                        field_id VARCHAR(128) NOT NULL,
                        alias VARCHAR(128) NOT NULL,
                        encrypted_value VARCHAR(2048) NOT NULL,
                        key_version VARCHAR(64) NOT NULL,
                        ttl_seconds INT NOT NULL,
                        expires_at DATETIME(3) NOT NULL,
                        idempotency_key VARCHAR(192) NOT NULL,
                        configured TINYINT NOT NULL DEFAULT 1,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_agent_project_secret_binding_key (project_id, idempotency_key),
                        UNIQUE KEY uk_agent_project_secret_binding_alias (project_id, alias),
                        INDEX idx_agent_project_secret_binding_owner (student_id, project_id, field_id),
                        INDEX idx_agent_project_secret_binding_expiry (expires_at)
                    )
                    """);
        }
    }

    private String masterKey() {
        return Base64.getEncoder().encodeToString(
                "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone = ZoneOffset.UTC;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zoneId) {
            return this;
        }
    }
}
