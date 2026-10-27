package com.labex.labexagent.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.entity.AgentChangeSet;
import com.labex.entity.AgentFileChange;
import com.labex.labexagent.diff.GitSnapshotService;
import com.labex.mapper.AgentChangeSetMapper;
import com.labex.mapper.AgentFileChangeMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * B1 同步供给投影的行为契约。
 *
 * 覆盖"组合逻辑"：游标语义、分页、集合内文件完整性、存在性推导、截断标记，
 * 以及两条**互相独立**的进度线（change-set 进度 / 尾巴扫描进度）。
 * **不覆盖**：真实 MySQL 上的 SQL 执行、精度与并发（见 AgentSyncFeedServiceMySqlPrecisionTest /
 * AgentSyncFeedServiceMySqlIsolationTest）。
 */
class AgentSyncFeedServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime TAIL_TIME = LocalDateTime.parse("2026-09-28T09:00:00");

    @BeforeAll
    static void initializeEntityMetadata() {
        if (TableInfoHelper.getTableInfo(AgentFileChange.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentFileChange.class);
        }
        if (TableInfoHelper.getTableInfo(AgentChangeSet.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentChangeSet.class);
        }
    }

    private AgentSyncFeedService serviceWith(AgentChangeSetMapper changeSetMapper, AgentFileChangeMapper fileChangeMapper) {
        return new AgentSyncFeedService(changeSetMapper, fileChangeMapper, new AgentSyncFeedProperties(), FIXED_CLOCK);
    }

    private AgentChangeSet changeSet(long id) {
        AgentChangeSet changeSet = new AgentChangeSet();
        changeSet.setChangeSetId(id);
        changeSet.setTaskId(100L + id);
        changeSet.setStatus("pending");
        changeSet.setUpdateTime(LocalDateTime.parse("2026-09-28T09:30:00"));
        return changeSet;
    }

    private AgentFileChange file(String changeId, long changeSetId, String type, String status,
                                 String before, String after) {
        AgentFileChange file = new AgentFileChange();
        file.setChangeId(changeId);
        file.setChangeSetId(changeSetId);
        file.setRelativePath("src/" + changeId + ".java");
        file.setChangeType(type);
        file.setStatus(status);
        file.setBeforeContent(before);
        file.setAfterContent(after);
        file.setCreateTime(TAIL_TIME);
        file.setUpdateTime(TAIL_TIME.plusSeconds(1));
        return file;
    }

    private AgentSyncFeed.Cursor cursor(long afterChangeSetId, LocalDateTime tailTime, String tailChangeId) {
        return new AgentSyncFeed.Cursor(afterChangeSetId, tailTime == null ? null : tailTime.toString(), tailChangeId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsChangeSetsStrictlyAboveTheCursorInAscendingOrderWithTheirFullFileLists() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        when(changeSetMapper.selectList(any())).thenReturn(List.of(changeSet(11L), changeSet(12L)));
        when(fileChangeMapper.selectList(any())).thenReturn(List.of(
                file("b1", 11L, "modify", "pending", "old", "new"),
                file("a2", 12L, "create", "pending", "", "hello")));

        AgentSyncFeed feed = serviceWith(changeSetMapper, fileChangeMapper)
                .fetch(7, 12, 10L, null, null, null);

        assertEquals(List.of(11L, 12L), feed.changeSets().stream().map(AgentSyncFeed.ChangeSet::changeSetId).toList());
        assertEquals(1, feed.changeSets().get(0).files().size());
        assertEquals(1, feed.changeSets().get(1).files().size());
        assertEquals(12L, feed.nextCursor().afterChangeSetId());
        assertFalse(feed.hasMore());
        assertFalse(feed.hasMoreChangeSets());

        ArgumentCaptor<LambdaQueryWrapper<AgentChangeSet>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(changeSetMapper).selectList(captor.capture());
        AbstractWrapper<AgentChangeSet, ?, ?> query = captor.getValue();
        query.getSqlSegment();
        assertTrue(query.getParamNameValuePairs().containsValue(10L));
        assertTrue(query.getParamNameValuePairs().containsValue(7));
        assertTrue(query.getParamNameValuePairs().containsValue(12));
    }

    @Test
    void marksHasMoreWhenThePageIsTruncatedInsteadOfSilentlyDroppingTail() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        AgentSyncFeedProperties properties = new AgentSyncFeedProperties();
        properties.setMaxChangeSetsPerPage(2);
        when(changeSetMapper.selectList(any())).thenReturn(List.of(changeSet(1L), changeSet(2L), changeSet(3L)));
        when(fileChangeMapper.selectList(any())).thenReturn(List.of());

        AgentSyncFeed feed = new AgentSyncFeedService(changeSetMapper, fileChangeMapper, properties, FIXED_CLOCK)
                .fetch(7, 12, 0L, null, null, 2);

        assertEquals(2, feed.changeSets().size());
        assertEquals(2L, feed.nextCursor().afterChangeSetId());
        assertTrue(feed.hasMore());
        assertTrue(feed.hasMoreChangeSets());
        assertFalse(feed.hasMoreChangedFiles());
    }

    @Test
    void derivesExistenceFromChangeTypeSoCallersStopGuessingFromEmptyContent() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        when(changeSetMapper.selectList(any())).thenReturn(List.of(changeSet(1L)));
        when(fileChangeMapper.selectList(any())).thenReturn(List.of(
                file("created", 1L, "create", "pending", "", "hello"),
                file("modified", 1L, "modify", "pending", "old", "new"),
                file("deleted", 1L, "delete", "pending", "gone", "")));

        AgentSyncFeed feed = serviceWith(changeSetMapper, fileChangeMapper).fetch(7, 12, 0L, null, null, null);

        var entries = feed.changeSets().get(0).files();
        assertEquals(3, entries.size());
        var created = entries.stream().filter(e -> "create".equals(e.changeType())).findFirst().orElseThrow();
        assertFalse(created.beforeExisted());
        assertTrue(created.afterExisted());
        var deleted = entries.stream().filter(e -> "delete".equals(e.changeType())).findFirst().orElseThrow();
        assertTrue(deleted.beforeExisted());
        assertFalse(deleted.afterExisted());
        var modified = entries.stream().filter(e -> "modify".equals(e.changeType())).findFirst().orElseThrow();
        assertTrue(modified.beforeExisted());
        assertTrue(modified.afterExisted());
    }

    @Test
    void flagsTruncatedContentBecauseItCannotBeUsedAsARollbackBaseline() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        String truncated = "x".repeat(50) + GitSnapshotService.TRUNCATION_MARKER;
        when(changeSetMapper.selectList(any())).thenReturn(List.of(changeSet(1L)));
        when(fileChangeMapper.selectList(any()))
                .thenReturn(List.of(file("big", 1L, "modify", "pending", truncated, "small")));

        AgentSyncFeed feed = serviceWith(changeSetMapper, fileChangeMapper).fetch(7, 12, 0L, null, null, null);

        var entry = feed.changeSets().get(0).files().get(0);
        assertTrue(entry.beforeTruncated());
        assertFalse(entry.afterTruncated());
    }

    // ------------------------------------------------------------------
    // 尾巴扫描：独立进度线
    // ------------------------------------------------------------------

    @Test
    void surfacesFilesThatChangedInsideTheCursorBecauseAppendsAndUndoNeverCreateANewChangeSet() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        when(changeSetMapper.selectList(any())).thenReturn(List.of());
        AgentFileChange undone = file("undone", 5L, "modify", "undone", "before", "after");
        when(fileChangeMapper.selectList(any())).thenReturn(List.of(undone));

        AgentSyncFeed feed = serviceWith(changeSetMapper, fileChangeMapper)
                .fetch(7, 12, 10L, TAIL_TIME.toString(), null, null);

        assertEquals(1, feed.changedFiles().size());
        assertEquals("undone", feed.changedFiles().get(0).status());
        assertEquals(5L, feed.changedFiles().get(0).changeSetId());
        assertEquals(10L, feed.nextCursor().afterChangeSetId());
        // 扫描完成（未截断）时，续拉点回退"重叠秒"，避免同秒边界行被永久跳过。
        assertEquals("2026-09-28T09:59:58", feed.nextCursor().tailUpdateTime());
        assertEquals("", feed.nextCursor().tailChangeId());
    }

    /** P0 回归：尾巴页被截断时，续拉点必须是**最后一条的键**，而不是扫描水位线。 */
    @Test
    @SuppressWarnings("unchecked")
    void resumesTheChangedFileScanFromTheLastReturnedRowWhenThePageIsTruncated() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        AgentSyncFeedProperties properties = new AgentSyncFeedProperties();
        properties.setMaxChangedFilesPerPage(1);
        when(changeSetMapper.selectList(any())).thenReturn(List.of());
        AgentFileChange first = file("first", 5L, "modify", "applied", "a", "b");
        first.setUpdateTime(LocalDateTime.parse("2026-09-28T09:30:00"));
        AgentFileChange second = file("second", 5L, "modify", "undone", "a", "b");
        second.setUpdateTime(LocalDateTime.parse("2026-09-28T09:30:00")); // 同一秒，多文件
        when(fileChangeMapper.selectList(any())).thenReturn(List.of(first, second));

        AgentSyncFeed feed = new AgentSyncFeedService(changeSetMapper, fileChangeMapper, properties, FIXED_CLOCK)
                .fetch(7, 12, 10L, TAIL_TIME.toString(), null, null);

        assertEquals(1, feed.changedFiles().size(), "limit=1 时只返回 1 条");
        assertTrue(feed.hasMoreChangedFiles(), "还有未交付的尾巴文件必须显式报告");
        assertEquals("first", feed.changedFiles().get(0).changeId());
        // 关键：续拉点是最后返回那条的 (update_time, change_id)，不是 10:00:00 水位线。
        assertEquals("2026-09-28T09:30:00", feed.nextCursor().tailUpdateTime());
        assertEquals("first", feed.nextCursor().tailChangeId());

        ArgumentCaptor<LambdaQueryWrapper<AgentFileChange>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fileChangeMapper).selectList(captor.capture());
        AbstractWrapper<AgentFileChange, ?, ?> query = captor.getValue();
        query.getSqlSegment();
        // 同秒多文件必须用 (update_time, change_id) 复合键比较，否则同秒的其余文件永远取不到。
        assertTrue(query.getTargetSql().contains("change_id >"),
                "尾巴扫描必须是复合键推进；实际 sql=" + query.getTargetSql());
        assertTrue(query.getParamNameValuePairs().containsValue(TAIL_TIME), "必须带尾部游标时间参数");
        // 扫描上界单独绑定，使"请求期间写入的行"留到下一轮而不是靠时钟完全同步。
        assertTrue(query.getParamNameValuePairs().containsValue(LocalDateTime.parse("2026-09-28T09:59:58")),
                "必须绑定扫描上界");
    }

    @Test
    void skipsTheChangedFileSweepOnFirstSyncButStillStoresABaseline() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        when(changeSetMapper.selectList(any())).thenReturn(List.of());

        AgentSyncFeed feed = serviceWith(changeSetMapper, fileChangeMapper).fetch(7, 12, 0L, null, null, null);

        assertTrue(feed.changedFiles().isEmpty());
        assertFalse(feed.hasMore());
        verify(fileChangeMapper, never()).selectList(any());
        // 首次同步也要给出基线，否则客户端第二次请求会重放全部历史。
        assertEquals("2026-09-28T09:59:58", feed.nextCursor().tailUpdateTime());
        assertEquals("", feed.nextCursor().tailChangeId());
    }

    @Test
    void floorsAhigherPrecisionTailCursorToSecondsSoItCannotDriftAheadOfTheColumn() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        when(changeSetMapper.selectList(any())).thenReturn(List.of());
        when(fileChangeMapper.selectList(any())).thenReturn(List.of());

        // 精度必须与 DATETIME（秒）一致：纳秒游标会比库里任何行都"新"，直接吃掉未交付的行。
        serviceWith(changeSetMapper, fileChangeMapper)
                .fetch(7, 12, 10L, "2026-09-28T09:30:00.987654321", null, null);

        ArgumentCaptor<LambdaQueryWrapper<AgentFileChange>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fileChangeMapper).selectList(captor.capture());
        captor.getValue().getSqlSegment();
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(LocalDateTime.parse("2026-09-28T09:30:00")),
                "纳秒必须被截断到秒后再进 SQL");
        assertFalse(captor.getValue().getParamNameValuePairs().containsValue(LocalDateTime.parse("2026-09-28T09:30:00.987654321")));
    }

    @Test
    void keepsChangeSetProgressAndTailProgressIndependent() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        AgentSyncFeedProperties properties = new AgentSyncFeedProperties();
        properties.setMaxChangeSetsPerPage(1);
        properties.setMaxChangedFilesPerPage(1);
        when(changeSetMapper.selectList(any())).thenReturn(List.of(changeSet(11L), changeSet(12L)));
        AgentFileChange tail1 = file("tail1", 3L, "modify", "undone", "a", "b");
        tail1.setUpdateTime(LocalDateTime.parse("2026-09-28T09:30:00"));
        AgentFileChange tail2 = file("tail2", 3L, "modify", "applied", "a", "b");
        tail2.setUpdateTime(LocalDateTime.parse("2026-09-28T09:30:01"));
        // 服务请求 limit+1 条：返回 2 条即代表"还有下一行"，第一条必须被交付、续拉点落在它身上。
        when(fileChangeMapper.selectList(any())).thenReturn(List.of(tail1, tail2));

        AgentSyncFeed feed = new AgentSyncFeedService(changeSetMapper, fileChangeMapper, properties, FIXED_CLOCK)
                .fetch(7, 12, 10L, TAIL_TIME.toString(), null, null);

        assertEquals(1, feed.changeSets().size());
        assertEquals(11L, feed.nextCursor().afterChangeSetId());
        assertEquals("2026-09-28T09:30:00", feed.nextCursor().tailUpdateTime());
        assertEquals("tail1", feed.nextCursor().tailChangeId());
        assertTrue(feed.hasMoreChangeSets());
        assertTrue(feed.hasMoreChangedFiles());
        assertTrue(feed.hasMore());
    }

    @Test
    void acceptsTheDeprecatedRevisionTimeParameterAsAnAliasForTheTailCursor() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        when(changeSetMapper.selectList(any())).thenReturn(List.of());
        when(fileChangeMapper.selectList(any())).thenReturn(List.of());

        AgentSyncFeed feed = serviceWith(changeSetMapper, fileChangeMapper)
                .fetch(7, 12, 0L, "2026-09-28T09:00:00", null, null);

        // 旧参数名仍会触发尾巴扫描（不是被默默忽略）。
        verify(fileChangeMapper, times(1)).selectList(any());
        assertEquals("2026-09-28T09:59:58", feed.nextCursor().tailUpdateTime());
        assertEquals("", feed.nextCursor().tailChangeId());
    }

    @Test
    void rejectsAnUnparseableTailCursorInsteadOfSilentlySkippingTheCatchUpSweep() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        when(changeSetMapper.selectList(any())).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> serviceWith(changeSetMapper, fileChangeMapper).fetch(7, 12, 0L, "yesterday", null, null));
    }

    @Test
    void clampsRequestedPageSizeAndReturnsAnEmptyFeedWithoutOwnership() {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        AgentSyncFeedProperties properties = new AgentSyncFeedProperties();
        properties.setMaxChangeSetsPerPage(50);

        assertEquals(50, properties.clampPageSize(null));
        assertEquals(50, properties.clampPageSize(0));
        assertEquals(50, properties.clampPageSize(5000));
        assertEquals(7, properties.clampPageSize(7));

        AgentSyncFeed feed = new AgentSyncFeedService(changeSetMapper, fileChangeMapper, properties, FIXED_CLOCK)
                .fetch(null, null, null, null, null, 7);

        assertTrue(feed.changeSets().isEmpty());
        assertTrue(feed.changedFiles().isEmpty());
        assertEquals(0L, feed.nextCursor().afterChangeSetId());
        verify(changeSetMapper, never()).selectList(any());
    }

    @Test
    void serializesWithTheFieldNamesTheClientContractPromises() throws Exception {
        AgentChangeSetMapper changeSetMapper = mock(AgentChangeSetMapper.class);
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        when(changeSetMapper.selectList(any())).thenReturn(List.of(changeSet(1L)));
        when(fileChangeMapper.selectList(any())).thenReturn(List.of(
                file("f1", 1L, "create", "pending", "", "hello")));

        AgentSyncFeed feed = serviceWith(changeSetMapper, fileChangeMapper).fetch(7, 12, 0L, null, null, null);
        String json = new ObjectMapper().writeValueAsString(feed);

        for (String field : List.of("\"changeSets\"", "\"changedFiles\"", "\"nextCursor\"", "\"hasMore\"",
                "\"hasMoreChangeSets\"", "\"hasMoreChangedFiles\"",
                "\"changeSetId\"", "\"relativePath\"", "\"changeType\"", "\"status\"", "\"beforeExisted\"",
                "\"afterExisted\"", "\"beforeTruncated\"", "\"afterTruncated\"", "\"beforeContent\"",
                "\"afterContent\"", "\"afterChangeSetId\"", "\"tailUpdateTime\"", "\"tailChangeId\"")) {
            assertTrue(json.contains(field), "缺少字段 " + field + " in " + json);
        }
    }

    /** 供游标构造复用的断言辅助：避免测试里手写 (time, id) 组合时前后不一致。 */
    @Test
    void cursorRoundTripsThroughTheResponseContract() {
        AgentSyncFeed.Cursor cursor = cursor(339L, TAIL_TIME, "abc");
        assertEquals(339L, cursor.afterChangeSetId());
        assertEquals("2026-09-28T09:00", cursor.tailUpdateTime());
        assertEquals("abc", cursor.tailChangeId());
    }
}
