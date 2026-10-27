package com.labex.labexagent.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.labex.entity.AgentChangeSet;
import com.labex.entity.AgentFileChange;
import com.labex.mapper.AgentChangeSetMapper;
import com.labex.mapper.AgentFileChangeMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 真实 MySQL 上的同步供给契约验证（**隔离库**，不触碰真实业务数据）。
 *
 * <p>为什么必须有它：单元测试用 mock mapper，永远看不到"秒级 DATETIME + MySQL 四舍五入 +
 * JDBC 绑定 + 事务可见性"这四件事叠在一起的效果——而 P0 缺陷（尾巴分页丢尾部、
 * 水位线精度吃掉同秒行）恰恰只在这里暴露。
 *
 * <p>环境要求：`LABEX_AGENT_DB_PASSWORD`（以及可选的 `LABEX_AGENT_DB_USERNAME` / `LABEX_AGENT_DB_URL`）。
 * 缺省时整类跳过（CI 无数据库也能绿）。本类自建 schema `labex_sync_iso_*` 并在结束时删除。
 */
class AgentSyncFeedServiceMySqlIsolationTest {

    private static final int MAX_PAGE = 1;
    private static final AdjustableClock CLOCK = new AdjustableClock(Instant.parse("2026-10-06T06:00:00Z"));
    private static final List<String> CREATED_SCHEMAS = new ArrayList<>();

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String schemaName;
    private static String isoUrl;
    private static SqlSessionFactory factory;

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        String password = System.getenv("LABEX_AGENT_DB_PASSWORD");
        assumeTrue(password != null && !password.isBlank(), "缺少 LABEX_AGENT_DB_PASSWORD，跳过真实 MySQL 验证");
        adminPassword = password;
        adminUser = orDefault(System.getenv("LABEX_AGENT_DB_USERNAME"), "root");
        String rawUrl = orDefault(System.getenv("LABEX_AGENT_DB_URL"),
                "jdbc:mysql://localhost:3306/labex_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String hostAndDb = rawUrl.substring("jdbc:mysql://".length());
        String hostPort = hostAndDb.substring(0, hostAndDb.indexOf('/'));
        String query = hostAndDb.contains("?") ? hostAndDb.substring(hostAndDb.indexOf('?') + 1) : "";
        adminUrl = "jdbc:mysql://" + hostPort + "/?" + query;
        schemaName = "labex_sync_iso_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        isoUrl = "jdbc:mysql://" + hostPort + "/" + schemaName + (query.isEmpty() ? "" : "?" + query);

        try (Connection connection = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schemaName + " DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci");
            CREATED_SCHEMAS.add(schemaName);
            statement.execute("USE " + schemaName);
            for (String ddl : schemaDdl()) {
                statement.execute(ddl);
            }
        }
        factory = buildFactory();
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (CREATED_SCHEMAS.isEmpty()) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
             Statement statement = connection.createStatement()) {
            for (String schema : CREATED_SCHEMAS) {
                statement.execute("DROP DATABASE IF EXISTS " + schema);
            }
        }
    }

    /** 与 sql/schema.sql 对齐的关键列 + 支撑索引。 */
    private static List<String> schemaDdl() {
        return List.of(
                "CREATE TABLE t_agent_change_set ("
                        + " change_set_id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " task_id BIGINT NOT NULL, conversation_id VARCHAR(64) NOT NULL,"
                        + " student_id INT NOT NULL, project_id INT NOT NULL,"
                        + " status VARCHAR(32) DEFAULT 'pending', change_count INT DEFAULT 0, summary TEXT,"
                        + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                        + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + " INDEX idx_cs_project_set (student_id, project_id, change_set_id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
                "CREATE TABLE t_agent_file_change ("
                        + " change_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                        + " change_set_id BIGINT NOT NULL, task_id BIGINT NOT NULL, conversation_id VARCHAR(64) NOT NULL,"
                        + " student_id INT NOT NULL, project_id INT NOT NULL, relative_path VARCHAR(1000) NOT NULL,"
                        + " change_type VARCHAR(32) DEFAULT 'modify', before_hash VARCHAR(128),"
                        + " before_content LONGTEXT, after_content LONGTEXT, diff_text LONGTEXT,"
                        + " snapshot_before_ref VARCHAR(64), snapshot_after_ref VARCHAR(64),"
                        + " snapshot_paths MEDIUMTEXT, snapshot_status VARCHAR(32),"
                        + " status VARCHAR(32) DEFAULT 'pending',"
                        + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                        + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + " applied_time DATETIME, rejected_time DATETIME, undone_time DATETIME,"
                        + " INDEX idx_fc_project_update (student_id, project_id, update_time)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }

    private static SqlSessionFactory buildFactory() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(isoUrl);
        dataSource.setUser(adminUser);
        dataSource.setPassword(adminPassword);
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("sync-feed-iso", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        // 裸 MybatisConfiguration 没有 Spring Boot 注入的 GlobalConfig，
        // 缺它时带 @TableId 且未指定 IdType 的实体会在 insert 时 NPE（identifierGenerator is null）。
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        configuration.addMapper(AgentChangeSetMapper.class);
        configuration.addMapper(AgentFileChangeMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private AgentSyncFeedService service(AgentSyncFeedProperties properties) {
        SqlSession session = factory.openSession(true);
        return new AgentSyncFeedService(
                session.getMapper(AgentChangeSetMapper.class),
                session.getMapper(AgentFileChangeMapper.class),
                properties, CLOCK);
    }

    /**
     * 建一页用的服务：mapper 取自一个**保持打开**的 session（autoCommit=true），
     * 避免"方法返回后 session 已关闭"导致的连接失效。
     */
    private SessionFixture fixture(AgentSyncFeedProperties properties) {
        SqlSession session = factory.openSession(true);
        return new SessionFixture(session, new AgentSyncFeedService(
                session.getMapper(AgentChangeSetMapper.class),
                session.getMapper(AgentFileChangeMapper.class),
                properties, CLOCK));
    }

    private record SessionFixture(SqlSession session, AgentSyncFeedService service) implements AutoCloseable {
        @Override
        public void close() {
            session.close();
        }
    }

    private void insertChangeSet(long id, int studentId, int projectId) {
        try (Connection connection = jdbc();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO t_agent_change_set (change_set_id, task_id, conversation_id, student_id, project_id, status, change_count, create_time, update_time)"
                             + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            statement.setLong(1, id);
            statement.setLong(2, 1L);
            statement.setString(3, "conv");
            statement.setInt(4, studentId);
            statement.setInt(5, projectId);
            statement.setString(6, "pending");
            statement.setInt(7, 0);
            statement.setObject(8, LocalDateTime.parse("2026-07-01T00:00:00"));
            statement.setObject(9, LocalDateTime.parse("2026-07-01T00:00:00"));
            statement.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("insert change set failed", error);
        }
    }

    /**
     * 走**原生 JDBC**写入（不经 ORM）：这样写入路径、参数绑定、精度与事务可见性都是真实的，
     * 而读取路径仍然走真实 mapper + 服务。
     */
    private String insertFileRaw(String changeId, long changeSetId, int studentId, int projectId,
                                 String status, LocalDateTime updateTime) {
        try (Connection connection = jdbc()) {
            insertFileOn(connection, changeId, changeSetId, studentId, projectId, status, updateTime);
        } catch (Exception error) {
            throw new IllegalStateException("insert file change failed", error);
        }
        return changeId;
    }

    /** 在给定连接上插入（调用方控制事务边界，用于"延迟提交"场景）。 */
    private void insertFileOn(Connection connection, String changeId, long changeSetId, int studentId, int projectId,
                              String status, LocalDateTime updateTime) throws Exception {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO t_agent_file_change (change_id, change_set_id, task_id, conversation_id, student_id, project_id,"
                        + " relative_path, change_type, status, before_content, after_content, create_time, update_time)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, changeId);
            statement.setLong(2, changeSetId);
            statement.setLong(3, 1L);
            statement.setString(4, "conv");
            statement.setInt(5, studentId);
            statement.setInt(6, projectId);
            statement.setString(7, "src/" + changeId + ".java");
            statement.setString(8, "modify");
            statement.setString(9, status);
            statement.setString(10, "before-" + changeId);
            statement.setString(11, "after-" + changeId);
            statement.setObject(12, LocalDateTime.parse("2026-07-01T00:00:00"));
            statement.setObject(13, updateTime);
            statement.executeUpdate();
        }
    }

    private Connection jdbc() throws Exception {
        return DriverManager.getConnection(isoUrl, adminUser, adminPassword);
    }

    private LocalDateTime readStoredUpdateTime(String changeId) {
        try (Connection connection = jdbc();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT update_time FROM t_agent_file_change WHERE change_id = ?")) {
            statement.setString(1, changeId);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "刚写入的行必须能查到：" + changeId);
                return resultSet.getObject(1, LocalDateTime.class);
            }
        } catch (Exception error) {
            throw new IllegalStateException("read update_time failed", error);
        }
    }

    private AgentSyncFeedProperties props(int tailLimit, int overlapSeconds) {
        AgentSyncFeedProperties properties = new AgentSyncFeedProperties();
        properties.setMaxChangeSetsPerPage(50);
        properties.setMaxChangedFilesPerPage(tailLimit);
        properties.setTailOverlapSeconds(overlapSeconds);
        return properties;
    }

    // ------------------------------------------------------------------
    // P0-2：真实 JDBC 写入精度
    // ------------------------------------------------------------------

    @Test
    void recordsTheRealPrecisionBehaviourOfTheUpdateTimeColumn() {
        // 这条断言证明"游标带纳秒"为什么危险：DATETIME（秒级）存不下纳秒。
        LocalDateTime withNanos = LocalDateTime.parse("2026-07-05T10:00:00.987654321");
        String changeId = insertFileRaw("precision-" + UUID.randomUUID(), 1L, 1, 2, "applied", withNanos);
        LocalDateTime stored = readStoredUpdateTime(changeId);

        assertEquals(stored, stored.withNano(0), "列精度只有秒：读回的 updateTime 不得带小数");
        long deltaSeconds = Math.abs(java.time.Duration.between(withNanos, stored).getSeconds());
        assertTrue(deltaSeconds <= 1, "写入与存储最多相差 1 秒（MySQL 取整行为），实际差 " + deltaSeconds + " 秒");
        assertTrue(stored.equals(LocalDateTime.parse("2026-07-05T10:00:00"))
                        || stored.equals(LocalDateTime.parse("2026-07-05T10:00:01")),
                "只可能是截断（10:00:00）或四舍五入（10:00:01），实际 " + stored);
    }

    // ------------------------------------------------------------------
    // P0-1：尾巴分页必须交付全部行
    // ------------------------------------------------------------------

    @Test
    void paginatesTheWholeTailWithoutLosingRowsEvenWhenManyShareTheSameSecond() {
        CLOCK.set(Instant.parse("2026-10-06T06:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        insertChangeSet(100L, 9001, 9001);
        // 5 条同秒 + 2 条跨秒：同秒多文件必须靠 (update_time, change_id) 复合键推进。
        LocalDateTime sameSecond = LocalDateTime.parse("2026-07-05T10:00:00");
        for (int i = 0; i < 5; i++) {
            insertFileRaw(tag + "-same-" + i, 100L, 9001, 9001, "applied", sameSecond);
        }
        insertFileRaw(tag + "-next-0", 100L, 9001, 9001, "undone", sameSecond.plusSeconds(1));
        insertFileRaw(tag + "-next-1", 100L, 9001, 9001, "applied", sameSecond.plusSeconds(2));

        Set<String> delivered = new LinkedHashSet<>();
        String tailTime = "2026-07-01T00:00:00";
        String tailChangeId = null;
        int pages = 0;
        boolean hasMore = true;
        try (SessionFixture fixture = fixture(props(MAX_PAGE, 2))) {
            while (hasMore && pages < 20) {
                AgentSyncFeed feed = fixture.service().fetch(9001, 9001, 100L, tailTime, tailChangeId, 10);
                feed.changedFiles().forEach(entry -> delivered.add(entry.changeId()));
                tailTime = feed.nextCursor().tailUpdateTime();
                tailChangeId = feed.nextCursor().tailChangeId();
                hasMore = feed.hasMore();
                pages++;
            }
        }
        assertEquals(7, delivered.size(), "尾巴分页必须交付全部 7 条（原实现只交付第 1 条）: " + delivered);
        assertTrue(delivered.stream().allMatch(id -> id.startsWith(tag)), "不得混入其它项目的数据: " + delivered);
    }

    // ------------------------------------------------------------------
    // P0-2：边界与重叠窗口
    // ------------------------------------------------------------------

    @Test
    void doesNotLoseARowWhoseUpdateTimeMatchesTheAdvancedCursorSecond() {
        // 时钟定在 06:00:00Z，重叠窗口 2 秒 ⇒ 本次扫描上界 05:59:58。
        CLOCK.set(Instant.parse("2026-10-06T06:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        insertChangeSet(200L, 9002, 9002);
        LocalDateTime boundarySecond = LocalDateTime.parse("2026-10-06T05:59:58");
        insertFileRaw(tag + "-first", 200L, 9002, 9002, "applied", boundarySecond);

        try (SessionFixture fixture = fixture(props(10, 2))) {
            AgentSyncFeed first = fixture.service().fetch(9002, 9002, 200L, "2026-07-01T00:00:00", null, 10);
            assertEquals(1, first.changedFiles().size());
            String tailTime = first.nextCursor().tailUpdateTime();
            String cursorChangeId = first.nextCursor().tailChangeId();
            assertFalse(first.hasMoreChangedFiles());
            // 扫完一页后游标 = 本次扫描上界（= 请求时刻 − 重叠窗口），且 tailChangeId 为空串。
            // 空串 + SQL 里的 `change_id > ''` 让"该秒新出现的行"仍会被交付（宁可重复，不可丢失）。
            assertEquals("2026-10-06T05:59:58", tailTime);
            assertEquals("", cursorChangeId);

            // 同一秒内又发生了一次写入（延迟提交 / 同秒并发）。若续拉点等于扫描上界，
            // 这一行将永远不可见；重叠窗口使它成为"重复交付"而不是"丢失"。
            insertFileRaw(tag + "-late", 200L, 9002, 9002, "undone", boundarySecond);

            AgentSyncFeed second = fixture.service().fetch(9002, 9002, 200L, tailTime, cursorChangeId, 10);
            List<String> ids = second.changedFiles().stream().map(AgentSyncFeed.FileEntry::changeId).toList();
            assertTrue(ids.contains(tag + "-late"),
                    "落在重叠窗口内的同秒行必须被补发（收到：" + ids + "，游标=" + tailTime + "）");
        }
    }

    @Test
    void resumesExactlyFromTheLastDeliveredRowWhenTheTailPageIsTruncated() {
        CLOCK.set(Instant.parse("2026-10-06T06:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        insertChangeSet(300L, 9003, 9003);
        LocalDateTime sameSecond = LocalDateTime.parse("2026-07-05T12:00:00");
        insertFileRaw(tag + "-a", 300L, 9003, 9003, "applied", sameSecond);
        insertFileRaw(tag + "-b", 300L, 9003, 9003, "applied", sameSecond);
        insertFileRaw(tag + "-c", 300L, 9003, 9003, "applied", sameSecond);

        try (SessionFixture fixture = fixture(props(1, 2))) {
            AgentSyncFeed page1 = fixture.service().fetch(9003, 9003, 300L, "2026-07-01T00:00:00", null, 10);
            assertEquals(1, page1.changedFiles().size());
            assertTrue(page1.hasMoreChangedFiles(), "被截断必须显式报告");
            // 续拉点 = 最后交付行的键（时间 + change_id），不是水位线。
            assertEquals(sameSecond, LocalDateTime.parse(page1.nextCursor().tailUpdateTime()));
            assertEquals(page1.changedFiles().get(0).changeId(), page1.nextCursor().tailChangeId());

            AgentSyncFeed page2 = fixture.service().fetch(9003, 9003, 300L,
                    page1.nextCursor().tailUpdateTime(), page1.nextCursor().tailChangeId(), 10);
            assertEquals(1, page2.changedFiles().size());
            assertEquals(tag + "-b", page2.changedFiles().get(0).changeId(), "第二页必须正好接着第一页");
        }
    }

    // ------------------------------------------------------------------
    // 两条进度线互不吞没
    // ------------------------------------------------------------------

    @Test
    void keepsNewChangeSetProgressSeparateFromTheTailScanProgress() {
        CLOCK.set(Instant.parse("2026-10-06T06:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        insertChangeSet(400L, 9004, 9004);
        insertFileRaw(tag + "-tail", 400L, 9004, 9004, "undone", LocalDateTime.parse("2026-07-05T13:00:00"));
        insertChangeSet(401L, 9004, 9004);
        insertFileRaw(tag + "-new", 401L, 9004, 9004, "applied", LocalDateTime.parse("2026-07-05T13:01:00"));

        try (SessionFixture fixture = fixture(props(10, 2))) {
            AgentSyncFeed feed = fixture.service().fetch(9004, 9004, 400L, "2026-07-01T00:00:00", null, 10);
            assertEquals(List.of(401L), feed.changeSets().stream().map(AgentSyncFeed.ChangeSet::changeSetId).toList());
            assertEquals(401L, feed.nextCursor().afterChangeSetId(), "新集合进度推进到 401");
            assertEquals(1, feed.changedFiles().size(), "尾巴只扫 change_set_id <= 400 的行");
            assertEquals(tag + "-tail", feed.changedFiles().get(0).changeId());
            assertFalse(feed.hasMoreChangedFiles());
        }
    }

    @Test
    void scopesTheFeedToTheOwningStudentAndProject() {
        CLOCK.set(Instant.parse("2026-10-06T06:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        insertChangeSet(500L, 9005, 9005);
        insertFileRaw(tag + "-mine", 500L, 9005, 9005, "applied", LocalDateTime.parse("2026-07-05T14:00:00"));
        insertChangeSet(600L, 9999, 9999);
        insertFileRaw(tag + "-other", 600L, 9999, 9999, "applied", LocalDateTime.parse("2026-07-05T14:00:00"));

        try (SessionFixture fixture = fixture(props(50, 2))) {
            AgentSyncFeed mine = fixture.service().fetch(9005, 9005, 0L, "2026-07-01T00:00:00", null, 10);
            assertEquals(List.of(500L), mine.changeSets().stream().map(AgentSyncFeed.ChangeSet::changeSetId).toList());
            assertTrue(mine.changedFiles().stream().noneMatch(entry -> entry.changeId().equals(tag + "-other")),
                    "不得返回其它项目/用户的文件");

            AgentSyncFeed stolen = fixture.service().fetch(9999, 9999, 0L, "2026-07-01T00:00:00", null, 10);
            assertTrue(stolen.changeSets().stream().noneMatch(cs -> cs.changeSetId() == 500L));
        }
    }

    /**
     * 延迟提交：写入事务在扫描之后才提交时，这一行不能丢。
     * 这是"水位线推进"最容易造成永久丢失的场景，也是重叠窗口存在的真正理由。
     */
    @Test
    void doesNotLoseARowThatCommitsAfterTheScanAdvancedTheCursor() throws Exception {
        CLOCK.set(Instant.parse("2026-10-06T06:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        insertChangeSet(700L, 9007, 9007);
        LocalDateTime boundarySecond = LocalDateTime.parse("2026-10-06T05:59:58");
        insertFileRaw(tag + "-visible", 700L, 9007, 9007, "applied", boundarySecond);

        try (Connection pending = jdbc(); SessionFixture fixture = fixture(props(10, 2))) {
            pending.setAutoCommit(false);
            insertFileOn(pending, tag + "-pending", 700L, 9007, 9007, "undone", boundarySecond);

            AgentSyncFeed first = fixture.service().fetch(9007, 9007, 700L, "2026-07-01T00:00:00", null, 10);
            List<String> visibleIds = first.changedFiles().stream().map(AgentSyncFeed.FileEntry::changeId).toList();
            assertTrue(visibleIds.contains(tag + "-visible"));
            assertFalse(visibleIds.contains(tag + "-pending"), "未提交的行不该被读到（隔离级别：REPEATABLE READ）");

            pending.commit(); // 晚提交

            AgentSyncFeed second = fixture.service().fetch(9007, 9007, 700L,
                    first.nextCursor().tailUpdateTime(), first.nextCursor().tailChangeId(), 10);
            List<String> afterCommit = second.changedFiles().stream().map(AgentSyncFeed.FileEntry::changeId).toList();
            assertTrue(afterCommit.contains(tag + "-pending"),
                    "延迟提交的同秒行必须由重叠窗口兜住（收到：" + afterCommit
                            + "，游标=" + first.nextCursor().tailUpdateTime() + "）");
        }
    }

    /** 可推进的 Clock：让"扫描 → 水位线推进 → 新数据"能在测试里确定性复现。 */
    private static final class AdjustableClock extends Clock {
        private Instant current;

        private AdjustableClock(Instant start) {
            this.current = start;
        }

        void set(Instant instant) {
            this.current = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        @Override
        public long millis() {
            return current.toEpochMilli();
        }
    }
}
