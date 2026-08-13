package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentProjectConfigRevision;
import com.labex.mapper.AgentProjectConfigRevisionMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AgentProjectConfigRevisionMapperDatabaseTest {

    @Test
    void roundTripsAProjectConfigRevisionThroughSnakeCaseColumns() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigRevisionMapper mapper = session.getMapper(AgentProjectConfigRevisionMapper.class);
            mapper.insert(revision(7, 12, 1L, "sha256:abc"));

            AgentProjectConfigRevision loaded = mapper.selectById(revisionId(mapper, 7, 12, 1L));
            assertThat(loaded.getStudentId()).isEqualTo(7);
            assertThat(loaded.getProjectId()).isEqualTo(12);
            assertThat(loaded.getRevision()).isEqualTo(1L);
            assertThat(loaded.getConfigDigest()).isEqualTo("sha256:abc");
            assertThat(loaded.getTreeReference()).isEqualTo("main@9f86d081");
            assertThat(loaded.getSchemaVersion()).isEqualTo("1");
            assertThat(loaded.getNormalizedConfig()).isEqualTo("{\"agent\":\"default\",\"runtime\":\"strict\"}");
            assertThat(loaded.getValidationStatus()).isEqualTo("valid");
            assertThat(loaded.getSourceActor()).isEqualTo("student:7");
            assertThat(loaded.getCreateTime()).isNotNull();
        }
    }

    @Test
    void enforcesOneRevisionPerProject() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigRevisionMapper mapper = session.getMapper(AgentProjectConfigRevisionMapper.class);
            mapper.insert(revision(7, 12, 1L, "sha256:rev-1"));
            mapper.insert(revision(7, 12, 2L, "sha256:rev-2"));
            mapper.insert(revision(7, 99, 1L, "sha256:rev-3"));

            Throwable duplicate = catchThrowable(() -> mapper.insert(revision(7, 12, 1L, "sha256:rev-1")));
            assertThat(uniqueConstraintViolated(duplicate)).as("duplicate (project_id, revision) is rejected").isTrue();

            assertThat(mapper.selectCount(new QueryWrapper<AgentProjectConfigRevision>()
                    .eq("project_id", 12))).isEqualTo(2);
        }
    }

    @Test
    void selectsRevisionsByOwnerAndProjectInMonotonicOrder() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigRevisionMapper mapper = session.getMapper(AgentProjectConfigRevisionMapper.class);
            mapper.insert(revision(7, 12, 1L, "sha256:rev-1"));
            mapper.insert(revision(7, 12, 2L, "sha256:rev-2"));
            mapper.insert(revision(8, 99, 1L, "sha256:other-project"));

            List<AgentProjectConfigRevision> owned = mapper.selectList(new QueryWrapper<AgentProjectConfigRevision>()
                    .eq("student_id", 7)
                    .eq("project_id", 12)
                    .orderByAsc("revision"));

            assertThat(owned).extracting(AgentProjectConfigRevision::getRevision)
                    .containsExactly(1L, 2L);
        }
    }

    private Long revisionId(AgentProjectConfigRevisionMapper mapper, int studentId, int projectId, long revision) {
        return mapper.selectOne(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("student_id", studentId)
                .eq("project_id", projectId)
                .eq("revision", revision)).getRevisionId();
    }

    private AgentProjectConfigRevision revision(int studentId, int projectId, long revision, String digest) {
        AgentProjectConfigRevision entity = new AgentProjectConfigRevision();
        entity.setStudentId(studentId);
        entity.setProjectId(projectId);
        entity.setRevision(revision);
        entity.setConfigDigest(digest);
        entity.setTreeReference("main@9f86d081");
        entity.setSchemaVersion("1");
        entity.setNormalizedConfig("{\"agent\":\"default\",\"runtime\":\"strict\"}");
        entity.setValidationStatus("valid");
        entity.setSourceActor("student:" + studentId);
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
                new Environment("revision-mapper", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectConfigRevisionMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:project_config_revision_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createTable(DataSource dataSource) throws SQLException {
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
        }
    }
}
