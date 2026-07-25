package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class CommandApprovalDatabaseConcurrencyTest {

    @Test
    void realH2ConditionalConsumeAllowsExactlyOneConcurrentWinner() throws Exception {
        String url = "jdbc:h2:mem:command_approval_concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("src/test/resources/sql/command-approval-h2.sql"));
            insertApproved(connection);
        }

        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return consume(url);
                }));
            }
            ready.await();
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status, consumed_time FROM t_command_approval WHERE approval_id = ?")) {
            statement.setString(1, "approval-71");
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("consumed");
                assertThat(result.getTimestamp("consumed_time")).isNotNull();
            }
        }
    }

    private boolean consume(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE t_command_approval SET status = 'consumed', consumed_time = ?, update_time = ?
                     WHERE approval_id = ? AND student_id = ? AND project_id = ? AND task_id = ?
                       AND conversation_id = ? AND session_id = ? AND source = ? AND invocation_id = ?
                       AND tool_call_id = ? AND command_digest = ? AND canonical_command = ?
                       AND working_directory = ? AND shell = ? AND command_options = ?
                       AND classification = ? AND policy_version = ? AND status = 'approved' AND expires_time > ?
                     """)) {
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            statement.setTimestamp(1, now);
            statement.setTimestamp(2, now);
            statement.setString(3, "approval-71");
            statement.setInt(4, 7);
            statement.setInt(5, 12);
            statement.setLong(6, 71L);
            statement.setString(7, "conversation-71");
            statement.setString(8, "session-71");
            statement.setString(9, "agent_shell");
            statement.setString(10, "invoke-71");
            statement.setString(11, "tool-71");
            statement.setString(12, "digest-71");
            statement.setString(13, "npm test");
            statement.setString(14, ".");
            statement.setString(15, "direct");
            statement.setString(16, "timeout=60;longRunning=false");
            statement.setString(17, "REQUIRE_APPROVAL");
            statement.setString(18, "policy-v1");
            statement.setTimestamp(19, now);
            return statement.executeUpdate() == 1;
        }
    }

    private void insertApproved(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO t_command_approval (approval_id, idempotency_key, student_id, project_id, task_id,
                conversation_id, session_id, source, invocation_id, tool_call_id, command_digest, canonical_command,
                display_command, working_directory, shell, command_options, classification, policy_version, status,
                expires_time, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'approved', ?, ?, ?)
                """)) {
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            statement.setString(1, "approval-71");
            statement.setString(2, "request-71");
            statement.setInt(3, 7);
            statement.setInt(4, 12);
            statement.setLong(5, 71L);
            statement.setString(6, "conversation-71");
            statement.setString(7, "session-71");
            statement.setString(8, "agent_shell");
            statement.setString(9, "invoke-71");
            statement.setString(10, "tool-71");
            statement.setString(11, "digest-71");
            statement.setString(12, "npm test");
            statement.setString(13, "npm test");
            statement.setString(14, ".");
            statement.setString(15, "direct");
            statement.setString(16, "timeout=60;longRunning=false");
            statement.setString(17, "REQUIRE_APPROVAL");
            statement.setString(18, "policy-v1");
            statement.setTimestamp(19, Timestamp.valueOf(LocalDateTime.now().plusMinutes(5)));
            statement.setTimestamp(20, now);
            statement.setTimestamp(21, now);
            statement.executeUpdate();
        }
    }
}
