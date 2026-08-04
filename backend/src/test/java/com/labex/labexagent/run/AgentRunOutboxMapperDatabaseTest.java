package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.mapper.AgentRunOutboxMapper;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AgentRunOutboxMapperDatabaseTest {

    @Test
    void countsOnlyEarlierUnpublishedSequencesFromTheSameTask() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        Configuration configuration = new Configuration(
                new Environment("outbox-sequence-fence", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(AgentRunOutboxMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentRunOutboxMapper mapper = session.getMapper(AgentRunOutboxMapper.class);

            assertThat(mapper.countUnpublishedBeforeSequence(71L, 10L)).isEqualTo(1L);
            assertThat(mapper.countUnpublishedBeforeSequence(72L, 2L)).isEqualTo(1L);

            session.getConnection().createStatement().execute(
                    "UPDATE t_agent_run_outbox SET status = 'publishing' WHERE event_id = 902");
            session.clearCache();
            assertThat(mapper.countUnpublishedBeforeSequence(71L, 10L)).isEqualTo(1L);

            session.getConnection().createStatement().execute(
                    "UPDATE t_agent_run_outbox SET status = 'published' WHERE event_id = 902");
            session.clearCache();
            assertThat(mapper.countUnpublishedBeforeSequence(71L, 10L)).isZero();
            assertThat(mapper.countUnpublishedBeforeSequence(71L, 9L)).isZero();
        }
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:outbox_sequence_fence;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createSchemaAndFixtures(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_run_event (
                        event_id BIGINT PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        sequence_number BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE t_agent_run_outbox (
                        outbox_id BIGINT PRIMARY KEY,
                        event_id BIGINT NOT NULL,
                        task_id BIGINT NOT NULL,
                        status VARCHAR(32) NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO t_agent_run_event VALUES "
                    + "(901, 71, 8), (902, 71, 9), (903, 71, 10), (904, 72, 1)");
            statement.execute("INSERT INTO t_agent_run_outbox VALUES "
                    + "(81, 901, 71, 'published'), "
                    + "(82, 902, 71, 'pending'), "
                    + "(83, 903, 71, 'pending'), "
                    + "(84, 904, 72, 'pending')");
        }
    }
}