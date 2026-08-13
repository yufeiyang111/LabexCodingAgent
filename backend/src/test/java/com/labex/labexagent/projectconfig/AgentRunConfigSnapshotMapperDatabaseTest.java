package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentRunConfigSnapshot;
import com.labex.mapper.AgentRunConfigSnapshotMapper;
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

class AgentRunConfigSnapshotMapperDatabaseTest {

    @Test
    void roundTripsAnImmutableTaskEpochSnapshotThroughSnakeCaseColumns() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentRunConfigSnapshotMapper mapper = session.getMapper(AgentRunConfigSnapshotMapper.class);
            mapper.insert(snapshot(71L, 1L, "snap-digest-1"));

            AgentRunConfigSnapshot loaded = mapper.selectById(1L);
            assertThat(loaded.getTaskId()).isEqualTo(71L);
            assertThat(loaded.getExecutionEpoch()).isEqualTo(1L);
            assertThat(loaded.getProjectId()).isEqualTo(12);
            assertThat(loaded.getProjectConfigRevision()).isEqualTo(3L);
            assertThat(loaded.getProjectConfigDigest()).isEqualTo("sha256:head");
            assertThat(loaded.getEffectiveConfigJson()).isEqualTo("{\"model\":\"gpt-4o-mini\"}");
            assertThat(loaded.getEffectiveConfigDigest()).isEqualTo("snap-digest-1");
            assertThat(loaded.getModelFingerprint()).isEqualTo("model-config:42");
            assertThat(loaded.getCapabilityDigest()).isEqualTo("caps:7f8a");
            assertThat(loaded.getResourceDigest()).isEqualTo("resources:9b1c");
            assertThat(loaded.getRuntimeProfile()).isEqualTo("strict");
            assertThat(loaded.getNetworkPolicyJson()).isEqualTo("{\"allowPublic\":true}");
            assertThat(loaded.getVerificationPolicyJson()).isEqualTo("{\"runTests\":true}");
            assertThat(loaded.getEnvironmentOperationRef()).isEqualTo("env-op:5");
            assertThat(loaded.getSecretAliasesJson()).isEqualTo("[\"provider-key-alias\"]");
            assertThat(loaded.getCreateTime()).isNotNull();
        }
    }

    @Test
    void enforcesOneSnapshotPerTaskAndExecutionEpoch() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentRunConfigSnapshotMapper mapper = session.getMapper(AgentRunConfigSnapshotMapper.class);
            mapper.insert(snapshot(71L, 1L, "snap-digest-1"));
            mapper.insert(snapshot(71L, 2L, "snap-digest-2"));
            mapper.insert(snapshot(72L, 1L, "snap-digest-3"));

            Throwable duplicate = catchThrowable(() -> mapper.insert(snapshot(71L, 1L, "snap-digest-1")));
            assertThat(uniqueConstraintViolated(duplicate))
                    .as("duplicate (task_id, execution_epoch) is rejected").isTrue();

            assertThat(mapper.selectCount(new QueryWrapper<AgentRunConfigSnapshot>()
                    .eq("task_id", 71L))).isEqualTo(2);
        }
    }

    @Test
    void selectsSnapshotsByTaskAndProject() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentRunConfigSnapshotMapper mapper = session.getMapper(AgentRunConfigSnapshotMapper.class);
            mapper.insert(snapshot(71L, 1L, "snap-digest-1"));
            mapper.insert(snapshot(71L, 2L, "snap-digest-2"));
            mapper.insert(snapshot(99L, 1L, "snap-digest-other"));

            List<AgentRunConfigSnapshot> taskSnapshots = mapper.selectList(
                    new QueryWrapper<AgentRunConfigSnapshot>()
                            .eq("task_id", 71L)
                            .eq("project_id", 12)
                            .orderByAsc("execution_epoch"));

            assertThat(taskSnapshots).extracting(AgentRunConfigSnapshot::getExecutionEpoch)
                    .containsExactly(1L, 2L);
        }
    }

    private AgentRunConfigSnapshot snapshot(long taskId, long executionEpoch, String digest) {
        AgentRunConfigSnapshot entity = new AgentRunConfigSnapshot();
        entity.setTaskId(taskId);
        entity.setExecutionEpoch(executionEpoch);
        entity.setProjectId(12);
        entity.setProjectConfigRevision(3L);
        entity.setProjectConfigDigest("sha256:head");
        entity.setEffectiveConfigJson("{\"model\":\"gpt-4o-mini\"}");
        entity.setEffectiveConfigDigest(digest);
        entity.setModelFingerprint("model-config:42");
        entity.setCapabilityDigest("caps:7f8a");
        entity.setResourceDigest("resources:9b1c");
        entity.setRuntimeProfile("strict");
        entity.setNetworkPolicyJson("{\"allowPublic\":true}");
        entity.setVerificationPolicyJson("{\"runTests\":true}");
        entity.setEnvironmentOperationRef("env-op:5");
        entity.setSecretAliasesJson("[\"provider-key-alias\"]");
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
                new Environment("run-config-snapshot-mapper", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentRunConfigSnapshotMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:run_config_snapshot_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_run_config_snapshot (
                        snapshot_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        execution_epoch BIGINT NOT NULL,
                        project_id INT NOT NULL,
                        project_config_revision BIGINT DEFAULT NULL,
                        project_config_digest VARCHAR(64) DEFAULT NULL,
                        effective_config_json LONGTEXT NOT NULL,
                        effective_config_digest VARCHAR(64) NOT NULL,
                        model_fingerprint VARCHAR(256) DEFAULT NULL,
                        capability_digest VARCHAR(64) DEFAULT NULL,
                        resource_digest VARCHAR(64) DEFAULT NULL,
                        runtime_profile VARCHAR(64) DEFAULT NULL,
                        network_policy_json LONGTEXT DEFAULT NULL,
                        verification_policy_json LONGTEXT DEFAULT NULL,
                        environment_operation_ref VARCHAR(64) DEFAULT NULL,
                        secret_aliases_json LONGTEXT DEFAULT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("ALTER TABLE t_agent_run_config_snapshot "
                    + "ADD CONSTRAINT uk_agent_run_config_snapshot_epoch UNIQUE (task_id, execution_epoch)");
            statement.execute("CREATE INDEX idx_agent_run_config_snapshot_task_project "
                    + "ON t_agent_run_config_snapshot (task_id, project_id)");
        }
    }
}
