package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentProjectConfigExternalChange;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentProjectConfigExternalChangeService.PendingExternalChange;
import com.labex.labexagent.projectconfig.AgentProjectConfigOwnership.ProjectConfigNotFoundException;
import com.labex.mapper.AgentProjectConfigExternalChangeMapper;
import com.labex.service.StudentProjectService;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

class AgentProjectConfigExternalChangeServiceTest {

    @TempDir
    Path root;

    private StudentProjectService studentProjectService;
    private AgentProjectConfigExternalChangeMapper mapper;
    private AgentProjectConfigExternalChangeService service;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory factory = factory(dataSource);
        SqlSession session = factory.openSession(true);
        mapper = session.getMapper(AgentProjectConfigExternalChangeMapper.class);
        studentProjectService = mock(StudentProjectService.class);
        service = new AgentProjectConfigExternalChangeService(studentProjectService, mapper);
    }

    @Test
    void recordsPendingObservationForOwnedProjectWithOwnerProjectScope() throws Exception {
        registerOwnedProject(7, 12);

        PendingExternalChange change = service.recordPending(7, 12, 1L, "tree-digest-1",
                List.of("agent.json", "agents/main.json"));

        assertThat(change.id()).isNotNull();
        assertThat(change.baseRevision()).isEqualTo(1L);
        assertThat(change.observedTreeDigest()).isEqualTo("tree-digest-1");
        assertThat(change.changedPathSummary()).isEqualTo("agent.json, agents/main.json");
        assertThat(change.detectedAt()).isNotNull();

        AgentProjectConfigExternalChange row = mapper.selectById(change.id());
        assertThat(row.getStudentId()).isEqualTo(7);
        assertThat(row.getProjectId()).isEqualTo(12);
        assertThat(row.getStatus()).isEqualTo("external_change_pending");
        assertThat(row.getProposalId()).isNull();
    }

    @Test
    void identicalObservationIsDeduplicatedIntoOnePendingRecord() throws Exception {
        registerOwnedProject(7, 12);

        PendingExternalChange first = service.recordPending(7, 12, 1L, "tree-digest-1", List.of("agent.json"));
        PendingExternalChange second = service.recordPending(7, 12, 1L, "tree-digest-1", List.of("agent.json"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(mapper.selectCount(new QueryWrapper<AgentProjectConfigExternalChange>()
                .eq("project_id", 12))).isEqualTo(1);
    }

    @Test
    void distinctObservedTreeDigestsProduceDistinctPendingRecords() throws Exception {
        registerOwnedProject(7, 12);

        service.recordPending(7, 12, 1L, "tree-digest-1", List.of("agent.json"));
        service.recordPending(7, 12, 1L, "tree-digest-2", List.of("environment.json"));

        assertThat(mapper.selectCount(new QueryWrapper<AgentProjectConfigExternalChange>()
                .eq("project_id", 12))).isEqualTo(2);
    }

    @Test
    void foreignProjectFailsClosedWithoutAnyRecord() throws Exception {
        when(studentProjectService.getOwnedProject(7, 999)).thenReturn(null);

        ProjectConfigNotFoundException error = catchThrowableOfType(
                () -> service.recordPending(7, 999, 1L, "tree-digest-1", List.of("agent.json")),
                ProjectConfigNotFoundException.class);

        assertThat(error).isNotNull();
        assertThat(mapper.selectCount(new QueryWrapper<AgentProjectConfigExternalChange>()
                .eq("project_id", 999))).isZero();
    }

    @Test
    void findPendingAndHasPendingAreScopedByOwnerAndProject() throws Exception {
        registerOwnedProject(7, 12);
        registerOwnedProject(8, 99);
        registerOwnedProject(7, 77);
        service.recordPending(7, 12, 1L, "tree-12", List.of("agent.json"));
        service.recordPending(8, 99, 1L, "tree-99", List.of("agent.json"));

        Optional<PendingExternalChange> owned = service.findPending(7, 12);
        assertThat(owned).isPresent();
        assertThat(owned.get().observedTreeDigest()).isEqualTo("tree-12");

        assertThat(service.findPending(7, 77)).isEmpty();
        assertThat(service.hasPending(7, 12)).isTrue();
        assertThat(service.hasPending(7, 77)).isFalse();
        assertThat(service.hasPending(8, 99)).isTrue();
    }

    @Test
    void findPendingAndHasPendingRequireOwnershipFirst() throws Exception {
        when(studentProjectService.getOwnedProject(7, 999)).thenReturn(null);

        ProjectConfigNotFoundException findError = catchThrowableOfType(
                () -> service.findPending(7, 999), ProjectConfigNotFoundException.class);
        assertThat(findError).isNotNull();

        ProjectConfigNotFoundException hasError = catchThrowableOfType(
                () -> service.hasPending(7, 999), ProjectConfigNotFoundException.class);
        assertThat(hasError).isNotNull();
    }

    @Test
    void summaryIsARedactedPathListCappedToABoundedSize() throws Exception {
        registerOwnedProject(7, 12);
        List<String> paths = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            paths.add("files/changed-" + index + ".json");
        }

        PendingExternalChange change = service.recordPending(7, 12, 1L, "tree-digest-big", paths);

        assertThat(change.changedPathSummary()).contains("files/changed-0.json");
        assertThat(change.changedPathSummary()).contains("(+5 more)");
        assertThat(change.changedPathSummary()).doesNotContain("secret");
    }

    @Test
    void pendingMarkerNeverMaterializesProposalDecisionFields() throws Exception {
        registerOwnedProject(7, 12);

        PendingExternalChange change = service.recordPending(7, 12, 1L, "tree-digest-1", List.of("agent.json"));

        AgentProjectConfigExternalChange row = mapper.selectById(change.id());
        assertThat(row.getStatus()).isEqualTo("external_change_pending");
        assertThat(row.getProposalId()).isNull();
        assertThat(row.getObservedTreeDigest()).isEqualTo("tree-digest-1");
    }

    private void registerOwnedProject(int studentId, int projectId) {
        StudentProject project = new StudentProject();
        project.setProjectId(projectId);
        project.setStudentId(studentId);
        project.setWorkspacePath(root.toString());
        when(studentProjectService.getOwnedProject(studentId, projectId)).thenReturn(project);
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("external-change-service", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectConfigExternalChangeMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:external_change_service_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
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
