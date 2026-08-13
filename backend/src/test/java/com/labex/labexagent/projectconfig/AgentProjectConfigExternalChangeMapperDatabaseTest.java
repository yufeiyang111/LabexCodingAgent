package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.labex.entity.AgentProjectConfigExternalChange;
import com.labex.mapper.AgentProjectConfigExternalChangeMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AgentProjectConfigExternalChangeMapperDatabaseTest {

    @Test
    void roundTripsAPendingExternalChangeObservationWithItsDefaultStatus() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigExternalChangeMapper mapper =
                    session.getMapper(AgentProjectConfigExternalChangeMapper.class);
            AgentProjectConfigExternalChange pending = externalChange(7, 12, 1L, "tree-digest-1",
                    "agent.json", null);
            mapper.insert(pending);

            AgentProjectConfigExternalChange loaded = mapper.selectById(pending.getExternalChangeId());
            assertThat(loaded.getStudentId()).isEqualTo(7);
            assertThat(loaded.getProjectId()).isEqualTo(12);
            assertThat(loaded.getBaseRevision()).isEqualTo(1L);
            assertThat(loaded.getObservedTreeDigest()).isEqualTo("tree-digest-1");
            assertThat(loaded.getChangedPathSummary()).isEqualTo("agent.json");
            assertThat(loaded.getStatus()).isEqualTo("external_change_pending");
            assertThat(loaded.getProposalId()).isNull();
            assertThat(loaded.getDetectedAt()).isNull();
            assertThat(loaded.getCreateTime()).isNotNull();
        }
    }

    @Test
    void materializesTheProposalReferenceOntoTheSameObservationRow() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigExternalChangeMapper mapper =
                    session.getMapper(AgentProjectConfigExternalChangeMapper.class);
            AgentProjectConfigExternalChange pending = externalChange(7, 12, 1L, "tree-digest-1",
                    "agent.json", "external_change_pending");
            mapper.insert(pending);

            pending.setStatus("proposal_materialized");
            pending.setProposalId(501L);
            mapper.updateById(pending);

            AgentProjectConfigExternalChange loaded = mapper.selectById(pending.getExternalChangeId());
            assertThat(loaded.getStatus()).isEqualTo("proposal_materialized");
            assertThat(loaded.getProposalId()).isEqualTo(501L);
            assertThat(loaded.getObservedTreeDigest()).isEqualTo("tree-digest-1");
        }
    }

    @Test
    void enforcesOnePendingRecordPerProjectBaseRevisionAndTreeDigest() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigExternalChangeMapper mapper =
                    session.getMapper(AgentProjectConfigExternalChangeMapper.class);
            mapper.insert(externalChange(7, 12, 1L, "tree-digest-1", "agent.json", "external_change_pending"));
            mapper.insert(externalChange(7, 12, 1L, "tree-digest-2", "environment.json", "external_change_pending"));
            mapper.insert(externalChange(8, 99, 1L, "tree-digest-1", "agent.json", "external_change_pending"));

            Throwable duplicate = catchThrowable(() -> mapper.insert(
                    externalChange(7, 12, 1L, "tree-digest-1", "agent.json", "external_change_pending")));
            assertThat(uniqueConstraintViolated(duplicate))
                    .as("duplicate pending observation for the same project/base/tree is rejected").isTrue();
        }
    }

    private AgentProjectConfigExternalChange externalChange(int studentId, int projectId, long baseRevision,
                                                            String treeDigest, String summary, String status) {
        AgentProjectConfigExternalChange entity = new AgentProjectConfigExternalChange();
        entity.setStudentId(studentId);
        entity.setProjectId(projectId);
        entity.setBaseRevision(baseRevision);
        entity.setObservedTreeDigest(treeDigest);
        entity.setChangedPathSummary(summary);
        entity.setStatus(status);
        return entity;
    }

    private boolean uniqueConstraintViolated(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("external-change-mapper", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectConfigExternalChangeMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:external_change_" + UUID.randomUUID().toString().replace("-", "")
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
