package com.labex.labexagent.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentProjectConfigProposal;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentProjectConfigApplyRecoveryService;
import com.labex.labexagent.projectconfig.AgentProjectConfigChangeEvidenceService;
import com.labex.labexagent.projectconfig.AgentProjectConfigExternalChangeService;
import com.labex.labexagent.projectconfig.AgentProjectConfigFileWriter;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService;
import com.labex.labexagent.projectconfig.AgentProjectConfigRevisionService;
import com.labex.mapper.AgentProjectConfigAuditEventMapper;
import com.labex.mapper.AgentProjectConfigExternalChangeMapper;
import com.labex.mapper.AgentProjectConfigProposalMapper;
import com.labex.mapper.AgentProjectConfigRevisionMapper;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentProjectConfigControllerTest {

    private static final String MANIFEST = "{"
            + "\"schemaVersion\": 1,"
            + "\"defaultAgent\": \"agents/main.json\","
            + "\"agents\": [\"agents/main.json\"],"
            + "\"tools\": [\"tools/build.json\"],"
            + "\"mcpServers\": [\"mcp/typescript.json\"],"
            + "\"skills\": [\"skills/backend.md\"],"
            + "\"environment\": \"environment.json\","
            + "\"models\": [\"model-1\"],"
            + "\"runtimeProfile\": \"strict\","
            + "\"networkPolicy\": {\"allowPublicDownloads\": true, \"allowLocalhost\": true, \"allowLan\": false},"
            + "\"verificationPolicy\": {\"requireTests\": true},"
            + "\"capabilities\": {\"enabledTools\": [\"read_file\"], \"deniedTools\": []}"
            + "}";

    private static final String AGENT_MAIN =
            "{\"id\":\"main\",\"name\":\"Main Agent\",\"modelRef\":\"model-1\",\"instructions\":\"Be helpful\"}";
    private static final String AGENT_MAIN_CANDIDATE =
            "{\"id\":\"main\",\"name\":\"Main Agent\",\"modelRef\":\"model-1\",\"instructions\":\"Be concise\"}";
    private static final String TOOL_BUILD =
            "{\"name\":\"build\",\"script\":\"scripts/build.ps1\",\"description\":\"Build the project\"}";
    private static final String MCP_TYPESCRIPT =
            "{\"id\":\"ts\",\"command\":\"npx\",\"args\":[\"tsc\",\"--noEmit\"],\"credentialAlias\":\"ts-credential\"}";
    private static final String SKILL_BACKEND = "# Backend\r\n\r\nBuild and test instructions.\r\n";
    private static final String ENVIRONMENT =
            "{\"variables\":[\"NODE_ENV\"],\"installPolicy\":\"project-only\"}";

    @TempDir
    Path root;

    private StudentProjectService studentProjectService;
    private AgentProjectConfigProposalMapper proposalMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        createTables(dataSource);
        SqlSessionFactory factory = factory(dataSource);
        SqlSession session = factory.openSession(true);
        AgentProjectConfigRevisionMapper revisionMapper = session.getMapper(AgentProjectConfigRevisionMapper.class);
        AgentProjectConfigExternalChangeMapper externalChangeMapper =
                session.getMapper(AgentProjectConfigExternalChangeMapper.class);
        AgentProjectConfigProposalMapper proposalMapper =
                session.getMapper(AgentProjectConfigProposalMapper.class);
        AgentProjectConfigAuditEventMapper auditMapper =
                session.getMapper(AgentProjectConfigAuditEventMapper.class);
        this.proposalMapper = proposalMapper;
        studentProjectService = mock(StudentProjectService.class);
        AgentProjectConfigExternalChangeService externalChangeService =
                new AgentProjectConfigExternalChangeService(studentProjectService, externalChangeMapper);
        AgentProjectConfigRevisionService revisionService =
                new AgentProjectConfigRevisionService(studentProjectService, revisionMapper, externalChangeService);
        AgentProjectConfigFileWriter fileWriter = new AgentProjectConfigFileWriter();
        AgentProjectConfigChangeEvidenceService evidenceService =
                new AgentProjectConfigChangeEvidenceService(studentProjectService);
        AgentProjectConfigApplyRecoveryService recoveryService =
                new AgentProjectConfigApplyRecoveryService(studentProjectService, proposalMapper, revisionMapper,
                        auditMapper, externalChangeMapper, fileWriter, evidenceService);
        AgentProjectConfigProposalService proposalService =
                new AgentProjectConfigProposalService(studentProjectService, proposalMapper, revisionMapper,
                        fileWriter, recoveryService, externalChangeService);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AgentProjectConfigController(revisionService, proposalService)).build();
    }

    @Test
    void returnsValidatedRedactedConfigForOwnedProjectOnFirstLoad() throws Exception {
        Path project = writeValidTree("first-load");
        registerOwnedProject(7, 12, project);

        mockMvc.perform(get("/student/projects/12/agent/config").with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.validationStatus").value("valid"))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.configDigest").isString())
                .andExpect(jsonPath("$.data.treeDigest").isString())
                .andExpect(jsonPath("$.data.runtimeProfile").value("strict"))
                .andExpect(jsonPath("$.data.trustStatus").value("untrusted"))
                .andExpect(jsonPath("$.data.externalChangePending").value(false))
                .andExpect(jsonPath("$.data.document.manifest.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.document.children['agents/main.json'].id").value("main"))
                .andExpect(jsonPath("$.data.enabledResources.models[0]").value("model-1"))
                .andExpect(jsonPath("$.data.environmentStatus.configured").value(true))
                .andExpect(jsonPath("$.data.environmentStatus.variables[0]").value("NODE_ENV"));
    }

    @Test
    void foreignProjectIsIndistinguishableFromNotFound() throws Exception {
        when(studentProjectService.getOwnedProject(7, 999)).thenReturn(null);

        mockMvc.perform(get("/student/projects/999/agent/config").with(authenticatedAs(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Project not found"));
    }

    @Test
    void foreignProjectProposalListIsSafe404() throws Exception {
        when(studentProjectService.getOwnedProject(7, 999)).thenReturn(null);

        mockMvc.perform(get("/student/projects/999/agent/config/proposals").with(authenticatedAs(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Project not found"));
    }

    @Test
    void staleExpectedRevisionReturnsRealHttp409() throws Exception {
        Path project = writeValidTree("stale");
        registerOwnedProject(7, 12, project);

        mockMvc.perform(get("/student/projects/12/agent/config")
                        .param("expectedRevision", "1")
                        .with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1));

        mockMvc.perform(get("/student/projects/12/agent/config")
                        .param("expectedRevision", "0")
                        .with(authenticatedAs(7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void malformedConfigFailsClosedWithStructuredErrors() throws Exception {
        Path project = writeValidTree("malformed");
        registerOwnedProject(7, 12, project);
        Files.writeString(configDir(project).resolve("agent.json"), "{\"schemaVersion\": 1,");

        mockMvc.perform(get("/student/projects/12/agent/config").with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.validationStatus").value("invalid"))
                .andExpect(jsonPath("$.data.errors[0].reasonCode").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.data.revision").doesNotExist());
    }

    @Test
    void externalEditIsReportedAsPendingBlockingStateWithoutSilentRevisionChange() throws Exception {
        Path project = writeValidTree("external-edit");
        registerOwnedProject(7, 12, project);
        mockMvc.perform(get("/student/projects/12/agent/config").with(authenticatedAs(7)))
                .andExpect(jsonPath("$.data.revision").value(1));

        Files.writeString(configDir(project).resolve("agents/main.json"),
                "{\"id\":\"main\",\"name\":\"Main Agent\",\"modelRef\":\"model-1\",\"instructions\":\"Be concise\"}");

        mockMvc.perform(get("/student/projects/12/agent/config").with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.externalChangePending").value(true))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.externalChange.baseRevision").value(1))
                .andExpect(jsonPath("$.data.externalChange.changedPaths[0]").value("agents/main.json"));
    }

    @Test
    void secretValuesNeverAppearInAnyResponse() throws Exception {
        Path project = writeValidTree("secret");
        registerOwnedProject(7, 12, project);
        Files.writeString(configDir(project).resolve("agent.json"), "{"
                + "\"schemaVersion\": 1,"
                + "\"apiKey\": \"sk-exposed-value-98765\","
                + "\"defaultAgent\": \"agents/main.json\","
                + "\"agents\": [\"agents/main.json\"],"
                + "\"runtimeProfile\": \"strict\""
                + "}");

        String response = mockMvc.perform(get("/student/projects/12/agent/config").with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("sk-exposed-value-98765");
    }

    @Test
    void secretShapedValuesInsideAllowedKeysNeverAppearInTheResponse() throws Exception {
        Path project = writeValidTree("secret-shaped-values");
        registerOwnedProject(7, 12, project);
        Files.writeString(configDir(project).resolve("mcp/typescript.json"),
                "{\"id\":\"ts\",\"command\":\"npx\",\"args\":[\"--api-key\",\"sk-leak-abc\"],"
                        + "\"credentialAlias\":\"ts-credential\"}");
        Files.writeString(configDir(project).resolve("environment.json"),
                "{\"variables\":[\"NODE_ENV\",\"sk-leak-env\"],\"installPolicy\":\"project-only\"}");
        Files.writeString(configDir(project).resolve("agent.json"), MANIFEST.replace(
                "\"capabilities\": {\"enabledTools\": [\"read_file\"], \"deniedTools\": []}",
                "\"capabilities\": {\"enabledTools\": [\"read_file\"], \"deniedTools\": [], "
                        + "\"allowSecretAccess\": false}"));

        String response = mockMvc.perform(get("/student/projects/12/agent/config").with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.children['mcp/typescript.json'].args[1]").value("***"))
                .andExpect(jsonPath("$.data.environmentStatus.variables[1]").value("***"))
                .andExpect(jsonPath("$.data.document.manifest.capabilities.allowSecretAccess").value(false))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("sk-leak-abc", "sk-leak-env");
    }

    @Test
    void createsProposalThroughApiAndListsItWithoutCandidateContent() throws Exception {
        Path project = writeValidTree("api-create");
        registerOwnedProject(7, 12, project);
        loadConfigAs(7, 12);

        String body = proposalBody(1, "api-key-1", AGENT_MAIN_CANDIDATE);
        mockMvc.perform(post("/student/projects/12/agent/config/proposals")
                        .contentType("application/json").content(body).with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.baseRevision").value(1))
                .andExpect(jsonPath("$.data.candidateConfigDigest").isString())
                .andExpect(jsonPath("$.data.proposalId").isNumber());

        String list = mockMvc.perform(get("/student/projects/12/agent/config/proposals")
                        .with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].status").value("pending"))
                .andExpect(jsonPath("$.data[0].changedPathSummary").value("agents/main.json"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(list)
                .doesNotContain("Be concise", "sk-");

        mockMvc.perform(get("/student/projects/12/agent/config/proposals/1")
                        .with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.patchReference").value(org.hamcrest.Matchers.startsWith("tree:")))
                .andExpect(jsonPath("$.data.auditEventIds[0]").isNumber());
    }

    @Test
    void createProposalWithStaleExpectedRevisionReturnsRealHttp409() throws Exception {
        Path project = writeValidTree("api-stale-create");
        registerOwnedProject(7, 12, project);
        loadConfigAs(7, 12);

        mockMvc.perform(post("/student/projects/12/agent/config/proposals")
                        .contentType("application/json")
                        .content(proposalBody(0, "api-stale-key", AGENT_MAIN_CANDIDATE))
                        .with(authenticatedAs(7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void approveDecisionAppliesAndRepeatedDecisionIsIdempotent() throws Exception {
        Path project = writeValidTree("api-approve");
        registerOwnedProject(7, 12, project);
        loadConfigAs(7, 12);
        mockMvc.perform(post("/student/projects/12/agent/config/proposals")
                        .contentType("application/json")
                        .content(proposalBody(1, "api-approve-key", AGENT_MAIN_CANDIDATE))
                        .with(authenticatedAs(7)))
                .andExpect(status().isOk());

        String decision = "{\"decision\":\"approve\",\"expectedRevision\":1,\"decisionIdempotencyKey\":\"api-dec-1\"}";
        mockMvc.perform(post("/student/projects/12/agent/config/proposals/1/decision")
                        .contentType("application/json").content(decision).with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("applied"))
                .andExpect(jsonPath("$.data.appliedRevision").value(2));

        mockMvc.perform(post("/student/projects/12/agent/config/proposals/1/decision")
                        .contentType("application/json").content(decision).with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("applied"))
                .andExpect(jsonPath("$.data.appliedRevision").value(2));

        mockMvc.perform(get("/student/projects/12/agent/config").with(authenticatedAs(7)))
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.externalChangePending").value(false));
        org.assertj.core.api.Assertions.assertThat(
                Files.readString(configDir(project).resolve("agents/main.json"))).isEqualTo(AGENT_MAIN_CANDIDATE);
    }

    @Test
    void rejectDecisionKeepsTreeAndReturnsRejected() throws Exception {
        Path project = writeValidTree("api-reject");
        registerOwnedProject(7, 12, project);
        loadConfigAs(7, 12);
        mockMvc.perform(post("/student/projects/12/agent/config/proposals")
                        .contentType("application/json")
                        .content(proposalBody(1, "api-reject-key", AGENT_MAIN_CANDIDATE))
                        .with(authenticatedAs(7)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/student/projects/12/agent/config/proposals/1/decision")
                        .contentType("application/json")
                        .content("{\"decision\":\"reject\",\"expectedRevision\":1,\"decisionIdempotencyKey\":\"api-rej-1\"}")
                        .with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("rejected"));

        mockMvc.perform(get("/student/projects/12/agent/config").with(authenticatedAs(7)))
                .andExpect(jsonPath("$.data.revision").value(1));
        org.assertj.core.api.Assertions.assertThat(
                Files.readString(configDir(project).resolve("agents/main.json"))).isEqualTo(AGENT_MAIN);
    }

    @Test
    void decisionOnForeignProposalIsSafe404() throws Exception {
        Path project = writeValidTree("api-foreign-decision");
        registerOwnedProject(7, 12, project);
        loadConfigAs(7, 12);
        mockMvc.perform(post("/student/projects/12/agent/config/proposals")
                        .contentType("application/json")
                        .content(proposalBody(1, "api-foreign-key", AGENT_MAIN_CANDIDATE))
                        .with(authenticatedAs(7)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/student/projects/12/agent/config/proposals/999999/decision")
                        .contentType("application/json")
                        .content("{\"decision\":\"approve\",\"decisionIdempotencyKey\":\"api-foreign-dec\"}")
                        .with(authenticatedAs(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Project not found"));

        mockMvc.perform(get("/student/projects/12/agent/config/proposals/999999")
                        .with(authenticatedAs(7)))
                .andExpect(status().isNotFound());
    }

    @Test
    void decisionOnStaleProposalReturnsRealHttp409() throws Exception {
        Path project = writeValidTree("api-stale-decision");
        registerOwnedProject(7, 12, project);
        loadConfigAs(7, 12);
        mockMvc.perform(post("/student/projects/12/agent/config/proposals")
                        .contentType("application/json")
                        .content(proposalBody(1, "api-p1", AGENT_MAIN_CANDIDATE))
                        .with(authenticatedAs(7)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/student/projects/12/agent/config/proposals")
                        .contentType("application/json")
                        .content(proposalBody(1, "api-p2", AGENT_MAIN_CANDIDATE))
                        .with(authenticatedAs(7)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/student/projects/12/agent/config/proposals/2/decision")
                        .contentType("application/json")
                        .content("{\"decision\":\"approve\",\"expectedRevision\":1,\"decisionIdempotencyKey\":\"api-p2-dec\"}")
                        .with(authenticatedAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedRevision").value(2));

        mockMvc.perform(post("/student/projects/12/agent/config/proposals/1/decision")
                        .contentType("application/json")
                        .content("{\"decision\":\"approve\",\"expectedRevision\":2,\"decisionIdempotencyKey\":\"api-p1-dec\"}")
                        .with(authenticatedAs(7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void expiredProposalDecisionReturnsRealHttp410() throws Exception {
        Path project = writeValidTree("api-expired");
        registerOwnedProject(7, 12, project);
        loadConfigAs(7, 12);
        mockMvc.perform(post("/student/projects/12/agent/config/proposals")
                        .contentType("application/json")
                        .content(proposalBody(1, "api-expire-key", AGENT_MAIN_CANDIDATE))
                        .with(authenticatedAs(7)))
                .andExpect(status().isOk());
        proposalMapper.update(null, new UpdateWrapper<AgentProjectConfigProposal>()
                .eq("proposal_id", 1L)
                .set("expires_time", LocalDateTime.now().minusSeconds(5)));

        mockMvc.perform(post("/student/projects/12/agent/config/proposals/1/decision")
                        .contentType("application/json")
                        .content("{\"decision\":\"approve\",\"expectedRevision\":1,\"decisionIdempotencyKey\":\"api-exp-dec\"}")
                        .with(authenticatedAs(7)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(410));
    }

    @Test
    void secretValuesNeverAppearInProposalApiResponses() throws Exception {
        Path project = writeValidTree("api-secret");
        registerOwnedProject(7, 12, project);
        loadConfigAs(7, 12);
        String manifestWithSecret = MANIFEST.replace(
                "\"capabilities\": {\"enabledTools\": [\"read_file\"], \"deniedTools\": []}",
                "\"capabilities\": {\"enabledTools\": [\"read_file\"], \"deniedTools\": []},"
                        + "\"apiKey\": \"sk-exposed-value-98765\"");

        String response = mockMvc.perform(post("/student/projects/12/agent/config/proposals")
                        .contentType("application/json")
                        .content(proposalBody(1, "api-secret-key", AGENT_MAIN_CANDIDATE, manifestWithSecret))
                        .with(authenticatedAs(7)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("sk-exposed-value-98765");
    }

    private String proposalBody(long expectedRevision, String idempotencyKey, String agentContent) {
        return proposalBody(expectedRevision, idempotencyKey, agentContent, MANIFEST);
    }

    private String proposalBody(long expectedRevision, String idempotencyKey, String agentContent,
                                String manifest) {
        Map<String, String> candidate = new LinkedHashMap<>();
        candidate.put("agent.json", manifest);
        candidate.put("agents/main.json", agentContent);
        candidate.put("tools/build.json", TOOL_BUILD);
        candidate.put("mcp/typescript.json", MCP_TYPESCRIPT);
        candidate.put("skills/backend.md", SKILL_BACKEND);
        candidate.put("environment.json", ENVIRONMENT);
        StringBuilder builder = new StringBuilder("{");
        builder.append("\"expectedRevision\":").append(expectedRevision).append(',');
        builder.append("\"reason\":\"switch instructions\",");
        builder.append("\"idempotencyKey\":\"").append(idempotencyKey).append("\",");
        builder.append("\"candidate\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : candidate.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(entry.getKey()).append("\":")
                    .append(new com.google.gson.Gson().toJson(entry.getValue()));
        }
        builder.append("}}");
        return builder.toString();
    }

    private Path configDir(Path project) {
        return project.resolve(".labex-agent").resolve("project");
    }

    private Path writeValidTree(String name) throws Exception {
        Path project = root.resolve(name);
        Path config = configDir(project);
        Files.createDirectories(config.resolve("agents"));
        Files.createDirectories(config.resolve("tools"));
        Files.createDirectories(config.resolve("mcp"));
        Files.createDirectories(config.resolve("skills"));
        Files.writeString(config.resolve("agent.json"), MANIFEST);
        Files.writeString(config.resolve("agents/main.json"), AGENT_MAIN);
        Files.writeString(config.resolve("tools/build.json"), TOOL_BUILD);
        Files.writeString(config.resolve("mcp/typescript.json"), MCP_TYPESCRIPT);
        Files.writeString(config.resolve("skills/backend.md"), SKILL_BACKEND);
        Files.writeString(config.resolve("environment.json"), ENVIRONMENT);
        return project;
    }

    private void registerOwnedProject(int studentId, int projectId, Path workspaceRoot) {
        StudentProject project = new StudentProject();
        project.setProjectId(projectId);
        project.setStudentId(studentId);
        project.setWorkspacePath(workspaceRoot.toString());
        when(studentProjectService.getOwnedProject(studentId, projectId)).thenReturn(project);
    }

    private void loadConfigAs(int studentId, int projectId) throws Exception {
        mockMvc.perform(get("/student/projects/" + projectId + "/agent/config")
                        .with(authenticatedAs(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1));
    }

    private RequestPostProcessor authenticatedAs(int studentId) {
        return request -> {
            request.setUserPrincipal(new UsernamePasswordAuthenticationToken(String.valueOf(studentId), null));
            return request;
        };
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("config-controller", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectConfigRevisionMapper.class);
        configuration.addMapper(AgentProjectConfigExternalChangeMapper.class);
        configuration.addMapper(AgentProjectConfigProposalMapper.class);
        configuration.addMapper(AgentProjectConfigAuditEventMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:config_controller_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createTables(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_project_config_revision (
                        revision_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        revision BIGINT NOT NULL,
                        config_digest VARCHAR(64) NOT NULL,
                        tree_reference VARCHAR(2048) DEFAULT NULL,
                        schema_version VARCHAR(32) NOT NULL DEFAULT '1',
                        normalized_config LONGTEXT NOT NULL,
                        validation_status VARCHAR(32) DEFAULT NULL,
                        source_actor VARCHAR(128) DEFAULT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("ALTER TABLE t_agent_project_config_revision "
                    + "ADD CONSTRAINT uk_agent_project_config_revision UNIQUE (project_id, revision)");
            statement.execute("CREATE INDEX idx_agent_project_config_revision_owner "
                    + "ON t_agent_project_config_revision (student_id, project_id, revision)");
            statement.execute("CREATE INDEX idx_agent_project_config_revision_digest "
                    + "ON t_agent_project_config_revision (project_id, config_digest)");
            statement.execute("""
                    CREATE TABLE t_agent_project_config_external_change (
                        external_change_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        base_revision BIGINT NOT NULL,
                        observed_tree_digest VARCHAR(64) NOT NULL,
                        changed_path_summary TEXT DEFAULT NULL,
                        status VARCHAR(32) NOT NULL DEFAULT 'external_change_pending',
                        proposal_id BIGINT DEFAULT NULL,
                        detected_at DATETIME(3) DEFAULT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("ALTER TABLE t_agent_project_config_external_change "
                    + "ADD CONSTRAINT uk_agent_external_change_pending "
                    + "UNIQUE (project_id, base_revision, observed_tree_digest, status)");
            statement.execute("CREATE INDEX idx_agent_external_change_owner "
                    + "ON t_agent_project_config_external_change (student_id, project_id, status, detected_at)");
            statement.execute("""
                    CREATE TABLE t_agent_project_config_proposal (
                        proposal_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        proposal_key VARCHAR(192) NOT NULL,
                        base_revision BIGINT NOT NULL,
                        candidate_config_digest VARCHAR(64) NOT NULL,
                        patch_reference VARCHAR(2048) DEFAULT NULL,
                        changed_path_summary TEXT DEFAULT NULL,
                        reason VARCHAR(2048) DEFAULT NULL,
                        origin_task_id BIGINT DEFAULT NULL,
                        origin_execution_epoch BIGINT DEFAULT NULL,
                        origin_tool_call_id VARCHAR(128) DEFAULT NULL,
                        source VARCHAR(64) NOT NULL DEFAULT 'agent',
                        creator VARCHAR(128) DEFAULT NULL,
                        status VARCHAR(32) NOT NULL DEFAULT 'pending',
                        expires_time DATETIME NOT NULL,
                        decision_idempotency_key VARCHAR(128) DEFAULT NULL,
                        decision_actor VARCHAR(128) DEFAULT NULL,
                        decision_time DATETIME(3) DEFAULT NULL,
                        applied_revision BIGINT DEFAULT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_agent_project_config_proposal_key (project_id, proposal_key),
                        UNIQUE KEY uk_agent_project_config_proposal_decision (project_id, decision_idempotency_key),
                        INDEX idx_agent_project_config_proposal_owner (student_id, project_id, status, create_time),
                        INDEX idx_agent_project_config_proposal_expiry (status, expires_time),
                        CHECK (expires_time <= TIMESTAMPADD(MINUTE, 1, create_time))
                    )
                    """);
            statement.execute("""
                    CREATE TABLE t_agent_project_config_audit_event (
                        event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        event_type VARCHAR(48) NOT NULL,
                        actor VARCHAR(128) DEFAULT NULL,
                        reason VARCHAR(2048) DEFAULT NULL,
                        previous_status VARCHAR(32) DEFAULT NULL,
                        next_status VARCHAR(32) DEFAULT NULL,
                        before_digest VARCHAR(64) DEFAULT NULL,
                        after_digest VARCHAR(64) DEFAULT NULL,
                        changed_path_summary TEXT DEFAULT NULL,
                        task_id BIGINT DEFAULT NULL,
                        execution_epoch BIGINT DEFAULT NULL,
                        idempotency_key VARCHAR(192) NOT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_agent_project_config_audit_idempotency (project_id, idempotency_key),
                        INDEX idx_agent_project_config_audit_owner (student_id, project_id, create_time),
                        INDEX idx_agent_project_config_audit_proposal (project_id, event_type, create_time)
                    )
                    """);
        }
    }
}
