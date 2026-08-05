package com.labex.labexagent.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.AgentLegacyMigrationGate;
import com.labex.mapper.AgentLegacyMigrationGateMapper;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AgentLegacyMigrationGateMapperDatabaseTest {

    @Test
    void explicitGateUpdateCanResetZeroInventoryTimestampToSqlNull() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixture(dataSource);
        Configuration configuration = new Configuration(
                new Environment("legacy-gate", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentLegacyMigrationGateMapper.class);
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessions.openSession(false)) {
            AgentLegacyMigrationGateMapper mapper = session.getMapper(AgentLegacyMigrationGateMapper.class);
            AgentLegacyMigrationGate gate = mapper.selectForUpdate("legacy_checkpoint");
            gate.setReadHitCount(4L);
            gate.setSourceItemHitCount(9L);
            gate.setLastReadHitAt(LocalDateTime.of(2026, 8, 5, 9, 30));
            gate.setZeroInventorySince(null);
            gate.setUpdateTime(LocalDateTime.of(2026, 8, 5, 9, 30));
            assertThat(mapper.updateGate(gate)).isEqualTo(1);
            session.commit();
        }

        try (SqlSession session = sessions.openSession()) {
            AgentLegacyMigrationGate stored = session.getMapper(AgentLegacyMigrationGateMapper.class)
                    .selectForUpdate("legacy_checkpoint");
            assertThat(stored.getReadHitCount()).isEqualTo(4L);
            assertThat(stored.getSourceItemHitCount()).isEqualTo(9L);
            assertThat(stored.getZeroInventorySince()).isNull();
        }
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:legacy_gate;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createSchemaAndFixture(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_legacy_migration_gate (
                        reader_key VARCHAR(64) NOT NULL PRIMARY KEY,
                        target_removal_version VARCHAR(32) NOT NULL,
                        observation_window_days INT NOT NULL,
                        read_hit_count BIGINT NOT NULL,
                        source_item_hit_count BIGINT NOT NULL,
                        last_read_hit_at TIMESTAMP NULL,
                        pending_source_count BIGINT NOT NULL,
                        last_inventory_at TIMESTAMP NULL,
                        zero_inventory_since TIMESTAMP NULL,
                        create_time TIMESTAMP NULL,
                        update_time TIMESTAMP NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO t_agent_legacy_migration_gate
                    (reader_key, target_removal_version, observation_window_days,
                     read_hit_count, source_item_hit_count, pending_source_count,
                     zero_inventory_since, create_time, update_time)
                    VALUES ('legacy_checkpoint', '1.1.0', 14, 3, 8, 0,
                            TIMESTAMP '2026-08-01 09:00:00',
                            TIMESTAMP '2026-08-01 09:00:00',
                            TIMESTAMP '2026-08-01 09:00:00')
                    """);
        }
    }
}
