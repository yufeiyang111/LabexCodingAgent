package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentProjectConfigExternalChange;
import com.labex.entity.AgentProjectConfigRevision;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentProjectConfigOwnership.ProjectConfigNotFoundException;
import com.labex.labexagent.projectconfig.AgentProjectConfigRevisionService.ExternalChangeInfo;
import com.labex.labexagent.projectconfig.AgentProjectConfigRevisionService.ProjectConfigView;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.ValidationError;
import com.labex.mapper.AgentProjectConfigExternalChangeMapper;
import com.labex.mapper.AgentProjectConfigRevisionMapper;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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

class AgentProjectConfigRevisionServiceTest {

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
    private AgentProjectConfigRevisionMapper revisionMapper;
    private AgentProjectConfigExternalChangeMapper externalChangeMapper;
    private AgentProjectConfigRevisionService service;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        createTables(dataSource);
        SqlSessionFactory factory = factory(dataSource);
        SqlSession session = factory.openSession(true);
        revisionMapper = session.getMapper(AgentProjectConfigRevisionMapper.class);
        externalChangeMapper = session.getMapper(AgentProjectConfigExternalChangeMapper.class);
        studentProjectService = mock(StudentProjectService.class);
        AgentProjectConfigExternalChangeService externalChangeService =
                new AgentProjectConfigExternalChangeService(studentProjectService, externalChangeMapper);
        service = new AgentProjectConfigRevisionService(studentProjectService, revisionMapper, externalChangeService);
    }

    @Test
    void firstLoadValidatesTreeAndCreatesRevisionOneWithDigestAndAuditMetadata() throws Exception {
        Path project = writeValidTree("first-load");
        registerOwnedProject(7, 12, project);

        ProjectConfigView view = service.load(7, 12);

        assertThat(view.valid()).isTrue();
        assertThat(view.validationStatus()).isEqualTo("valid");
        assertThat(view.revision()).isEqualTo(1L);
        assertThat(view.configDigest()).matches("[0-9a-f]{64}");
        assertThat(view.treeDigest()).matches("[0-9a-f]{64}");
        assertThat(view.externalChangePending()).isFalse();
        assertThat(view.runtimeProfile()).isEqualTo("strict");
        assertThat(view.trustStatus()).isEqualTo("untrusted");
        assertThat(view.environmentStatus().configured()).isTrue();
        assertThat(view.environmentStatus().variables()).containsExactly("NODE_ENV");
        assertThat(view.environmentStatus().installPolicy()).isEqualTo("project-only");
        assertThat(view.enabledResources().get("models")).containsExactly("model-1");
        assertThat(view.enabledResources().get("skills")).containsExactly("skills/backend.md");

        AgentProjectConfigRevision row = revisionMapper.selectOne(
                new QueryWrapper<AgentProjectConfigRevision>()
                        .eq("student_id", 7)
                        .eq("project_id", 12));
        assertThat(row).isNotNull();
        assertThat(row.getRevision()).isEqualTo(1L);
        assertThat(row.getConfigDigest()).isEqualTo(view.configDigest());
        assertThat(row.getTreeReference()).isEqualTo("tree:" + view.treeDigest());
        assertThat(row.getSchemaVersion()).isEqualTo("1");
        assertThat(row.getValidationStatus()).isEqualTo("valid");
        assertThat(row.getSourceActor()).isEqualTo("student:7");
        assertThat(row.getNormalizedConfig()).contains("\"document\"");
        assertThat(row.getNormalizedConfig()).contains("\"treeDigest\"");
        assertThat(row.getNormalizedConfig()).contains("\"files\"");
    }

    @Test
    void equivalentFormattingDoesNotCreateRevisionOrExternalChange() throws Exception {
        Path project = writeValidTree("formatting");
        registerOwnedProject(7, 12, project);
        ProjectConfigView first = service.load(7, 12);

        Path config = configDir(project);
        Files.writeString(config.resolve("agent.json"), "{"
                + "\r\n  \"runtimeProfile\": \"strict\","
                + "\r\n  \"schemaVersion\": 1,"
                + "\r\n  \"capabilities\": { \"deniedTools\": [], \"enabledTools\": [\"read_file\"] },"
                + "\r\n  \"networkPolicy\": { \"allowLan\": false, \"allowLocalhost\": true, \"allowPublicDownloads\": true },"
                + "\r\n  \"verificationPolicy\": { \"requireTests\": true },"
                + "\r\n  \"models\": [\"model-1\"],"
                + "\r\n  \"environment\": \"environment.json\","
                + "\r\n  \"skills\": [\"skills/backend.md\"],"
                + "\r\n  \"mcpServers\": [\"mcp/typescript.json\"],"
                + "\r\n  \"tools\": [\"tools/build.json\"],"
                + "\r\n  \"agents\": [\"agents/main.json\"],"
                + "\r\n  \"defaultAgent\": \"agents/main.json\""
                + "\r\n}");
        Files.writeString(config.resolve("agents/main.json"),
                "{\n  \"modelRef\": \"model-1\",\n  \"name\": \"Main Agent\",\n  \"instructions\": \"Be helpful\",\n  \"id\": \"main\"\n}");

        ProjectConfigView second = service.load(7, 12);

        assertThat(second.revision()).isEqualTo(1L);
        assertThat(second.externalChangePending()).isFalse();
        assertThat(second.treeDigest()).isEqualTo(first.treeDigest());
        assertThat(second.configDigest()).isEqualTo(first.configDigest());
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1);
        assertThat(externalChangeMapper.selectCount(new QueryWrapper<AgentProjectConfigExternalChange>()
                .eq("project_id", 12))).isZero();
    }

    @Test
    void externalSemanticEditPersistsPendingMarkerWithoutSilentNewRevision() throws Exception {
        Path project = writeValidTree("external-edit");
        registerOwnedProject(7, 12, project);
        ProjectConfigView first = service.load(7, 12);
        assertThat(first.revision()).isEqualTo(1L);

        Files.writeString(configDir(project).resolve("agents/main.json"),
                "{\"id\":\"main\",\"name\":\"Main Agent\",\"modelRef\":\"model-1\",\"instructions\":\"Be concise\"}");

        ProjectConfigView second = service.load(7, 12);

        assertThat(second.externalChangePending()).isTrue();
        assertThat(second.revision()).isEqualTo(1L);
        assertThat(second.configDigest()).isEqualTo(first.configDigest());
        ExternalChangeInfo change = second.externalChange();
        assertThat(change).isNotNull();
        assertThat(change.baseRevision()).isEqualTo(1L);
        assertThat(change.observedTreeDigest()).matches("[0-9a-f]{64}");
        assertThat(change.changedPaths()).containsExactly("agents/main.json");
        assertThat(change.detectedAt()).isNotNull();

        AgentProjectConfigExternalChange row = externalChangeMapper.selectOne(
                new QueryWrapper<AgentProjectConfigExternalChange>()
                        .eq("student_id", 7)
                        .eq("project_id", 12));
        assertThat(row.getStatus()).isEqualTo("external_change_pending");
        assertThat(row.getBaseRevision()).isEqualTo(1L);
        assertThat(row.getObservedTreeDigest()).isEqualTo(change.observedTreeDigest());
        assertThat(row.getChangedPathSummary()).isEqualTo("agents/main.json");
        assertThat(row.getProposalId()).isNull();
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1);
    }

    @Test
    void repeatedLoadsStayIdempotentForTheSameObservedTreeDigest() throws Exception {
        Path project = writeValidTree("idempotent");
        registerOwnedProject(7, 12, project);
        service.load(7, 12);
        Files.writeString(configDir(project).resolve("agents/main.json"),
                "{\"id\":\"main\",\"name\":\"Main Agent\",\"modelRef\":\"model-1\",\"instructions\":\"Be concise\"}");
        service.load(7, 12);

        ProjectConfigView third = service.load(7, 12);
        ExternalChangeInfo change = third.externalChange();

        assertThat(third.externalChangePending()).isTrue();
        assertThat(externalChangeMapper.selectCount(new QueryWrapper<AgentProjectConfigExternalChange>()
                .eq("project_id", 12))).isEqualTo(1);
        assertThat(change.id()).isEqualTo(
                externalChangeMapper.selectOne(new QueryWrapper<AgentProjectConfigExternalChange>()
                        .eq("project_id", 12)).getExternalChangeId());
    }

    @Test
    void undeclaredFileAdditionIsStillAnExternalTreeChange() throws Exception {
        Path project = writeValidTree("undeclared-add");
        registerOwnedProject(7, 12, project);
        service.load(7, 12);

        Files.writeString(configDir(project).resolve("agents/undeclared.json"),
                "{\"id\":\"ghost\",\"name\":\"Ghost Agent\"}");

        ProjectConfigView view = service.load(7, 12);

        assertThat(view.externalChangePending()).isTrue();
        assertThat(view.externalChange().changedPaths()).containsExactly("agents/undeclared.json");
        assertThat(view.revision()).isEqualTo(1L);
    }

    @Test
    void malformedConfigFailsClosedWithoutAnyRevision() throws Exception {
        Path project = writeValidTree("malformed");
        registerOwnedProject(7, 12, project);
        Files.writeString(configDir(project).resolve("agent.json"), "{\"schemaVersion\": 1,");

        ProjectConfigView view = service.load(7, 12);

        assertThat(view.valid()).isFalse();
        assertThat(view.validationStatus()).isEqualTo("invalid");
        assertThat(view.revision()).isNull();
        assertThat(view.redactedManifest()).isNull();
        assertThat(view.errors()).anySatisfy(error ->
                assertThat(error.reasonCode()).isEqualTo(AgentProjectConfigValidator.REASON_MALFORMED_JSON));
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isZero();
        assertThat(externalChangeMapper.selectCount(new QueryWrapper<AgentProjectConfigExternalChange>()
                .eq("project_id", 12))).isZero();
    }

    @Test
    void malformedAfterAcceptedRevisionFailsClosedAndKeepsTheAcceptedHead() throws Exception {
        Path project = writeValidTree("malformed-later");
        registerOwnedProject(7, 12, project);
        ProjectConfigView first = service.load(7, 12);
        assertThat(first.revision()).isEqualTo(1L);

        Files.writeString(configDir(project).resolve("agent.json"), "not json at all");

        ProjectConfigView view = service.load(7, 12);

        assertThat(view.valid()).isFalse();
        assertThat(view.validationStatus()).isEqualTo("invalid");
        assertThat(view.revision()).isEqualTo(1L);
        assertThat(view.errors()).anySatisfy(error ->
                assertThat(error.reasonCode()).isEqualTo(AgentProjectConfigValidator.REASON_MALFORMED_JSON));
        assertThat(revisionMapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", 12))).isEqualTo(1);
    }

    @Test
    void foreignProjectIsIndistinguishableFromNotFound() throws Exception {
        when(studentProjectService.getOwnedProject(7, 999)).thenReturn(null);

        ProjectConfigNotFoundException error = catchThrowableOfType(
                () -> service.load(7, 999), ProjectConfigNotFoundException.class);

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).isEqualTo("Project not found");
    }

    @Test
    void secretValuesNeverAppearInErrorsOrView() throws Exception {
        Path project = writeValidTree("secret");
        registerOwnedProject(7, 12, project);
        Files.writeString(configDir(project).resolve("agent.json"), "{"
                + "\"schemaVersion\": 1,"
                + "\"apiKey\": \"sk-exposed-value-98765\","
                + "\"defaultAgent\": \"agents/main.json\","
                + "\"agents\": [\"agents/main.json\"],"
                + "\"runtimeProfile\": \"strict\""
                + "}");

        ProjectConfigView view = service.load(7, 12);

        assertThat(view.valid()).isFalse();
        String rendered = view.errors().stream().map(ValidationError::message).reduce("", String::concat);
        assertThat(rendered).doesNotContain("sk-exposed-value-98765");
    }

    @Test
    void environmentStatusReportsAbsentWhenNoTemplateIsDeclared() throws Exception {
        Path project = writeValidTree("no-env");
        registerOwnedProject(7, 12, project);
        Files.deleteIfExists(configDir(project).resolve("environment.json"));
        String manifestWithoutEnv = MANIFEST.replace(",\"environment\": \"environment.json\"", "");
        Files.writeString(configDir(project).resolve("agent.json"), manifestWithoutEnv);

        ProjectConfigView view = service.load(7, 12);

        assertThat(view.valid()).isTrue();
        assertThat(view.environmentStatus().configured()).isFalse();
        assertThat(view.environmentStatus().variables()).isEmpty();
    }

    @Test
    void secretShapedValuesInsideAllowedKeysAreMaskedWhileBooleansKeepTheirType() throws Exception {
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

        ProjectConfigView view = service.load(7, 12);

        assertThat(view.valid()).isTrue();
        List<String> allStrings = new ArrayList<>();
        collectStrings(view.redactedManifest(), allStrings);
        collectStrings(view.redactedChildren(), allStrings);
        allStrings.addAll(view.environmentStatus().variables());
        assertThat(allStrings).doesNotContain("sk-leak-abc", "sk-leak-env");

        @SuppressWarnings("unchecked")
        List<Object> args = (List<Object>) view.redactedChildren().get("mcp/typescript.json").get("args");
        assertThat(args).containsExactly("--api-key", "***");
        assertThat(view.environmentStatus().variables()).containsExactly("NODE_ENV", "***");

        Object capabilities = view.redactedManifest().get("capabilities");
        assertThat(((java.util.Map<?, ?>) capabilities).get("allowSecretAccess")).isEqualTo(false);
    }

    private void collectStrings(Object value, List<String> out) {
        if (value instanceof java.util.Map<?, ?> map) {
            for (Object item : map.values()) {
                collectStrings(item, out);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectStrings(item, out);
            }
        } else if (value instanceof String string) {
            out.add(string);
        }
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

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("revision-service", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectConfigRevisionMapper.class);
        configuration.addMapper(AgentProjectConfigExternalChangeMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:revision_service_" + UUID.randomUUID().toString().replace("-", "")
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
        }
    }
}
