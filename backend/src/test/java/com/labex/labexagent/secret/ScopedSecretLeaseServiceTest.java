package com.labex.labexagent.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentProjectSecretBinding;
import com.labex.labexagent.secret.ScopedSecretLeaseService.ScopedSecretLease;
import com.labex.mapper.AgentProjectSecretBindingMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScopedSecretLeaseServiceTest {

    private static final String DB_SENTINEL = "db-password-sentinel-4c81";
    private static final String TOKEN_SENTINEL = "api-token-sentinel-9d2e";

    private AgentProjectSecretBindingMapper bindingMapper;
    private RecordingSecretStore secretStore;
    private ScopedSecretLeaseService leaseService;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory factory = factory(dataSource);
        SqlSession session = factory.openSession(true);
        bindingMapper = session.getMapper(AgentProjectSecretBindingMapper.class);
        secretStore = new RecordingSecretStore();
        leaseService = new ScopedSecretLeaseService(bindingMapper, secretStore, Clock.systemUTC());
    }

    @Test
    void opensValuesOnlyForBoundUnexpiredAllowedNames() {
        insertBinding(12, "DB_PASSWORD", cipher(DB_SENTINEL), 1, nowUtcPlus(Duration.ofHours(1)));
        insertBinding(12, "API_TOKEN", cipher(TOKEN_SENTINEL), 1, nowUtcPlus(Duration.ofHours(1)));
        insertBinding(12, "UNCONFIGURED", "enc:dW51c2Vk", 0, nowUtcPlus(Duration.ofHours(1)));
        insertBinding(12, "EXPIRED", "enc:b2xk", 1, nowUtcPlus(Duration.ofMinutes(-1)));
        insertBinding(99, "DB_PASSWORD", "enc:other-project", 1, nowUtcPlus(Duration.ofHours(1)));

        try (ScopedSecretLease lease = leaseService.issue(7, 12, 71L, 3L, "worker-run-1",
                Set.of("DB_PASSWORD", "API_TOKEN", "UNCONFIGURED", "EXPIRED", "NOT_BOUND"),
                Duration.ofMinutes(1))) {
            assertThat(lease.taskId()).isEqualTo(71L);
            assertThat(lease.epoch()).isEqualTo(3L);
            assertThat(lease.workerRunId()).isEqualTo("worker-run-1");
            assertThat(lease.value("DB_PASSWORD")).isEqualTo(DB_SENTINEL);
            assertThat(lease.value("API_TOKEN")).isEqualTo(TOKEN_SENTINEL);
            assertThat(lease.value("UNCONFIGURED")).isNull();
            assertThat(lease.value("EXPIRED")).isNull();
            assertThat(lease.value("NOT_BOUND")).isNull();
            assertThat(lease.allowedNames()).containsExactlyInAnyOrder(
                    "DB_PASSWORD", "API_TOKEN", "UNCONFIGURED", "EXPIRED", "NOT_BOUND");
        }
        assertThat(secretStore.openedLeases()).hasSize(2);
        assertThat(secretStore.openedLeases()).allMatch(RecordingLease::isClosed);
    }

    @Test
    void deniesReservedControlPlaneVariables() {
        for (String reserved : List.of("PATH", "HOME", "USER", "SHELL", "LANG", "WSLENV",
                "LABEX_RUN_ID", "LABEX_WORKER_TOKEN", "PWD", "TERM", "TEMP", "TMP", "TMPDIR",
                "SYSTEMDRIVE", "PROGRAMFILES", "APPDATA", "USERPROFILE", "OS",
                "NUMBER_OF_PROCESSORS", "PSMODULEPATH")) {
            assertThatThrownBy(() -> leaseService.issue(7, 12, 1L, 1L, "run-" + reserved,
                    Set.of(reserved), Duration.ofMinutes(1)))
                    .as("reserved variable %s is denied", reserved)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reserved");
        }
        assertThat(secretStore.openedLeases()).isEmpty();
    }

    @Test
    void deniesCommandLineInjectionAndInvalidShapes() {
        for (String evil : List.of("X=$(id)", "X=$PATH", "A;B", "A|B", "A B", "A&B", "A>B",
                "A<B", "A`B", "A\"B", "A'B", "A\\B", "PATH=evil", "1ABC", "A-B", "A.B", "A\nB",
                "A\tB", "A=B", "")) {
            assertThatThrownBy(() -> leaseService.issue(7, 12, 1L, 1L, "run-" + evil.hashCode(),
                    Set.of(evil), Duration.ofMinutes(1)))
                    .as("injection name <%s> is denied", evil)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> leaseService.issue(7, 12, 1L, 1L, "run-ok",
                Set.of("NODE_ENV", "_INTERNAL", "A_B2"), Duration.ofMinutes(1)))
                .doesNotThrowAnyException();
        assertThat(secretStore.openedLeases()).isEmpty();
    }

    @Test
    void rejectsMissingWorkerRunIdAndNullLeaseInputs() {
        assertThatThrownBy(() -> leaseService.issue(7, 12, 1L, 1L, null,
                Set.of("NODE_ENV"), Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leaseService.issue(7, 12, 1L, 1L, " ",
                Set.of("NODE_ENV"), Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leaseService.issue(null, 12, 1L, 1L, "run-1",
                Set.of("NODE_ENV"), Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leaseService.issue(7, 12, 1L, 1L, "run-1",
                null, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leaseService.issue(7, 12, 1L, 1L, "run-1",
                Set.of("NODE_ENV"), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leaseService.issue(7, 12, 1L, 1L, "run-1",
                Set.of("NODE_ENV"), Duration.ofMinutes(6))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leaseExpiresAndRefusesFurtherAccess() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-12T02:00:00Z"));
        insertBinding(12, "DB_PASSWORD", cipher(DB_SENTINEL), 1,
                LocalDateTime.ofInstant(clock.instant().plus(Duration.ofHours(1)), ZoneOffset.UTC));
        ScopedSecretLeaseService clocked = new ScopedSecretLeaseService(bindingMapper, secretStore, clock);

        try (ScopedSecretLease lease = clocked.issue(7, 12, 71L, 3L, "worker-run-1",
                Set.of("DB_PASSWORD"), Duration.ofMinutes(1))) {
            assertThat(lease.value("DB_PASSWORD")).isEqualTo(DB_SENTINEL);
            assertThat(lease.expiresAt()).isEqualTo(Instant.parse("2026-08-12T02:01:00Z"));

            clock.advance(Duration.ofSeconds(61));
            assertThatThrownBy(() -> lease.value("DB_PASSWORD"))
                    .isInstanceOf(SecretStore.SecretStoreException.class)
                    .hasMessageContaining("expired")
                    .hasMessageNotContaining(DB_SENTINEL);
        }
    }

    @Test
    void closeIsOneTimeCleanupClosingEveryUnderlyingLease() {
        insertBinding(12, "DB_PASSWORD", cipher(DB_SENTINEL), 1, nowUtcPlus(Duration.ofHours(1)));
        insertBinding(12, "API_TOKEN", cipher(TOKEN_SENTINEL), 1, nowUtcPlus(Duration.ofHours(1)));

        ScopedSecretLease lease = leaseService.issue(7, 12, 71L, 3L, "worker-run-1",
                Set.of("DB_PASSWORD", "API_TOKEN"), Duration.ofMinutes(1));
        assertThat(secretStore.openedLeases()).hasSize(2);

        assertThat(lease.value("DB_PASSWORD")).isEqualTo(DB_SENTINEL);
        lease.close();
        assertThat(secretStore.openedLeases()).allMatch(RecordingLease::isClosed);
        assertThatThrownBy(() -> lease.value("DB_PASSWORD"))
                .isInstanceOf(SecretStore.SecretStoreException.class)
                .hasMessageContaining("closed")
                .hasMessageNotContaining(DB_SENTINEL);
        assertThatCode(lease::close).doesNotThrowAnyException();
        assertThat(lease.toString()).doesNotContain(DB_SENTINEL).doesNotContain(TOKEN_SENTINEL);
    }

    @Test
    void leaseRejectsNamesOutsideItsAllowedSet() {
        insertBinding(12, "DB_PASSWORD", cipher(DB_SENTINEL), 1, nowUtcPlus(Duration.ofHours(1)));

        try (ScopedSecretLease lease = leaseService.issue(7, 12, 71L, 3L, "worker-run-1",
                Set.of("OTHER"), Duration.ofMinutes(1))) {
            assertThatThrownBy(() -> lease.value("DB_PASSWORD"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(DB_SENTINEL);
            assertThatThrownBy(() -> lease.value(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void serializedAndErrorFormsNeverExposeStoredValues() {
        insertBinding(12, "DB_PASSWORD", cipher(DB_SENTINEL), 1, nowUtcPlus(Duration.ofHours(1)));

        try (ScopedSecretLease lease = leaseService.issue(7, 12, 71L, 3L, "worker-run-1",
                Set.of("DB_PASSWORD"), Duration.ofMinutes(1))) {
            assertThat(lease.toString()).doesNotContain(DB_SENTINEL);
            assertThat(lease.value("DB_PASSWORD")).isEqualTo(DB_SENTINEL);
        }
        AgentProjectSecretBinding stored = bindingMapper.selectOne(
                new QueryWrapper<AgentProjectSecretBinding>().eq("project_id", 12));
        assertThat(stored.getEncryptedValue()).doesNotContain(DB_SENTINEL);
        assertThat(stored.getEncryptedValue()).isEqualTo(cipher(DB_SENTINEL));
    }

    @Test
    void concurrentValueAndCloseNeverSurfaceHalfErasedState() throws Exception {
        insertBinding(12, "DB_PASSWORD", cipher(DB_SENTINEL), 1, nowUtcPlus(Duration.ofHours(1)));
        insertBinding(12, "API_TOKEN", cipher(TOKEN_SENTINEL), 1, nowUtcPlus(Duration.ofHours(1)));
        ScopedSecretLease lease = leaseService.issue(7, 12, 71L, 3L, "worker-run-1",
                Set.of("DB_PASSWORD", "API_TOKEN"), Duration.ofMinutes(1));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Throwable>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                Throwable failure = null;
                try {
                    start.await();
                    for (int j = 0; j < 500; j++) {
                        lease.value("DB_PASSWORD");
                    }
                } catch (SecretStore.SecretStoreException expectedAfterClose) {
                    // closed/expired after close(): the only expected race outcome
                } catch (Exception unexpected) {
                    failure = unexpected;
                }
                return failure;
            }));
        }
        start.countDown();
        Thread.sleep(5);
        lease.close();
        pool.shutdown();
        for (java.util.concurrent.Future<Throwable> future : futures) {
            assertThat(future.get())
                    .as("concurrent value()/close() must never surface half-erased state").isNull();
        }
        assertThat(secretStore.openedLeases()).allMatch(RecordingLease::isClosed);
    }

    @Test
    void failsClosedWhenAnUnderlyingSecretCannotBeOpened() {
        insertBinding(12, "GOOD", cipher(DB_SENTINEL), 1, nowUtcPlus(Duration.ofHours(1)));
        insertBinding(12, "CORRUPT", "not-an-envelope", 1, nowUtcPlus(Duration.ofHours(1)));

        assertThatThrownBy(() -> leaseService.issue(7, 12, 71L, 3L, "worker-run-1",
                Set.of("GOOD", "CORRUPT"), Duration.ofMinutes(1)))
                .isInstanceOf(SecretStore.SecretStoreException.class)
                .hasMessageNotContaining(DB_SENTINEL);
        assertThat(secretStore.openedLeases()).allMatch(RecordingLease::isClosed);
    }

    private void insertBinding(int projectId, String name, String ciphertext, int configured,
                               LocalDateTime expiresAt) {
        AgentProjectSecretBinding binding = new AgentProjectSecretBinding();
        binding.setStudentId(7);
        binding.setProjectId(projectId);
        binding.setProposalId(88L);
        binding.setFieldId(name);
        binding.setAlias(ProjectSecretBindingService.aliasFor(projectId, name));
        binding.setEncryptedValue(ciphertext);
        binding.setKeyVersion("v1");
        binding.setTtlSeconds(3600);
        binding.setExpiresAt(expiresAt);
        binding.setIdempotencyKey("key-" + name + "-" + UUID.randomUUID());
        binding.setConfigured(configured);
        bindingMapper.insert(binding);
    }

    private LocalDateTime nowUtcPlus(Duration offset) {
        return LocalDateTime.ofInstant(Instant.now().plus(offset), ZoneOffset.UTC);
    }

    private String cipher(String plaintext) {
        return "enc:" + Base64.getEncoder().encodeToString(
                plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("secret-lease", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectSecretBindingMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:secret_lease_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_project_secret_binding (
                        binding_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        proposal_id BIGINT NOT NULL,
                        field_id VARCHAR(128) NOT NULL,
                        alias VARCHAR(128) NOT NULL,
                        encrypted_value VARCHAR(2048) NOT NULL,
                        key_version VARCHAR(64) NOT NULL,
                        ttl_seconds INT NOT NULL,
                        expires_at DATETIME(3) NOT NULL,
                        idempotency_key VARCHAR(192) NOT NULL,
                        configured TINYINT NOT NULL DEFAULT 1,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_agent_project_secret_binding_key (project_id, idempotency_key),
                        UNIQUE KEY uk_agent_project_secret_binding_alias (project_id, alias),
                        INDEX idx_agent_project_secret_binding_owner (student_id, project_id, field_id),
                        INDEX idx_agent_project_secret_binding_expiry (expires_at)
                    )
                    """);
        }
    }

    private static final class RecordingSecretStore implements SecretStore {
        private final List<RecordingLease> openedLeases = new ArrayList<>();

        @Override
        public StoredSecret store(SecretScope scope, String plaintext) {
            return new StoredSecret("enc:" + Base64.getEncoder().encodeToString(
                    plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "v1");
        }

        @Override
        public SecretLease open(SecretScope scope, String ciphertext) {
            if (!ciphertext.startsWith("enc:")) {
                throw new SecretStore.SecretStoreException("fake store cannot open this envelope");
            }
            RecordingLease lease = new RecordingLease(new String(Base64.getDecoder().decode(
                    ciphertext.substring(4)), java.nio.charset.StandardCharsets.UTF_8));
            openedLeases.add(lease);
            return lease;
        }

        List<RecordingLease> openedLeases() {
            return openedLeases;
        }
    }

    private static final class RecordingLease implements SecretStore.SecretLease {
        private String value;
        private boolean closed;

        private RecordingLease(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            if (closed) {
                throw new SecretStore.SecretStoreException("RecordingLease is closed");
            }
            return value;
        }

        @Override
        public void close() {
            closed = true;
            value = null;
        }

        boolean isClosed() {
            return closed;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone = ZoneOffset.UTC;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zoneId) {
            return this;
        }
    }
}
