package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.AgentRunInteraction;
import com.labex.mapper.AgentRunInteractionMapper;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AgentRunInteractionMapperDatabaseTest {

    @Test
    void selectsOnlyTheLatestResolvedInteractionForTasksThatStillWaitForThatInteractionType() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        Configuration configuration = new Configuration(
                new Environment("interaction-reconcile", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentRunInteractionMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessionFactory.openSession()) {
            List<AgentRunInteraction> candidates = session.getMapper(AgentRunInteractionMapper.class)
                    .selectResolvedAwaitingResume(100);

            assertThat(candidates)
                    .extracting(AgentRunInteraction::getInteractionId)
                    .containsExactly("question-current", "permission-current");
        }
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:resolved_interaction_reconcile;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createSchemaAndFixtures(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_task (
                        task_id BIGINT PRIMARY KEY,
                        status VARCHAR(32) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE t_agent_run_interaction (
                        interaction_id VARCHAR(64) PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        conversation_id VARCHAR(64),
                        session_id VARCHAR(128),
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        interaction_type VARCHAR(32) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        request_payload LONGTEXT,
                        response_payload LONGTEXT,
                        idempotency_key VARCHAR(128) NOT NULL,
                        expires_time DATETIME,
                        create_time DATETIME,
                        update_time DATETIME NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO t_agent_task VALUES (71, 'waiting_user'), (72, 'waiting_user'), "
                    + "(73, 'waiting_approval'), (74, 'completed'), (75, 'waiting_user')");
            insert(statement, "question-old", 71, "question", "answered", "2026-08-02 10:00:00");
            insert(statement, "question-current", 71, "question", "answered", "2026-08-02 10:01:00");
            insert(statement, "answered-before-new-wait", 72, "question", "answered", "2026-08-02 10:00:00");
            insert(statement, "question-still-waiting", 72, "question", "waiting", "2026-08-02 10:01:00");
            insert(statement, "permission-current", 73, "permission", "approved", "2026-08-02 10:02:00");
            insert(statement, "completed-question", 74, "question", "answered", "2026-08-02 10:03:00");
            statement.execute("INSERT INTO t_agent_run_interaction "
                    + "(interaction_id, task_id, student_id, project_id, interaction_type, status, idempotency_key, "
                    + "create_time, update_time) VALUES ('legacy-null-time', 75, 7, 12, 'question', "
                    + "'answered', 'key-legacy-null-time', NULL, TIMESTAMP '2026-08-02 10:00:00')");
            insert(statement, "question-after-null-time", 75, "question", "waiting", "2026-08-02 10:04:00");
        }
    }

    private void insert(Statement statement, String id, long taskId, String type, String status, String time)
            throws Exception {
        statement.execute("INSERT INTO t_agent_run_interaction "
                + "(interaction_id, task_id, student_id, project_id, interaction_type, status, idempotency_key, "
                + "create_time, update_time) VALUES ('" + id + "', " + taskId + ", 7, 12, '" + type + "', '"
                + status + "', 'key-" + id + "', TIMESTAMP '" + time + "', TIMESTAMP '" + time + "')");
    }
}