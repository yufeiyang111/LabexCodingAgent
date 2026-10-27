package com.labex.labexagent.sync;

import java.util.List;

/**
 * B1 同步供给：一次只读读取的结果，不含任何写入语义。
 *
 * <h2>客户端契约（v0，权威定义；变更需同步本文件 + sync/README.md）</h2>
 *
 * <p><b>两条进度线必须分别保存、分别回传，不能合成一个游标：</b>
 * <ul>
 *   <li>{@code changeSets} + {@code nextCursor.afterChangeSetId}：新集合的进度。
 *       每个集合携带**完整文件清单**，必须作为整体处理——集合内只应用一半就推进游标会漏同步。</li>
 *   <li>{@code changedFiles} + {@code nextCursor.tailUpdateTime/tailChangeId}：尾巴扫描的进度。
 *       内容是"change_set_id 小于等于游标、但状态或内容在该位置之后变化过"的文件。
 *       用于发现"已同步的集合被追加了文件"与"文件被 undo/apply/reject 改了状态"——
 *       这两类变化不会产生新的 change-set，只看 changeSets 永远发现不了。</li>
 * </ul>
 *
 * <p><b>续拉规则（必须照做，否则漏数据）</b>：把 {@code nextCursor} 原样作为下次请求的
 * {@code afterChangeSetId} / {@code tailUpdateTime} / {@code tailChangeId} 传回。
 * 不要用 {@code hasMore} 之外的任何推断去"跳过"某一页。
 *
 * <p><b>幂等去重</b>：为保证"同秒边界行"不丢，服务端会主动回退 {@code tailOverlapSeconds} 秒重扫，
 * 所以同一条 {@code (changeId, status, updateTime)} 可能被交付多次——客户端必须幂等处理
 * （按 changeId + 状态比对，而不是假设"每条只来一次"）。
 *
 * <p><b>精度与时区</b>：游标时间是 ISO-8601 **本地时间**，服务端按秒截断
 * （{@code t_agent_file_change.update_time} 是秒级 DATETIME）。客户端应原样回传，不要自行加工。
 */
public record AgentSyncFeed(List<ChangeSet> changeSets,
                            List<FileEntry> changedFiles,
                            Cursor nextCursor,
                            boolean hasMore,
                            boolean hasMoreChangeSets,
                            boolean hasMoreChangedFiles) {

    /** 一个 change-set 及其当前全部文件。 */
    public record ChangeSet(Long changeSetId, Long taskId, String status, String updateTime, List<FileEntry> files) {
    }

    /**
     * 单个文件变更。{@code beforeExisted}/{@code afterExisted} 由 changeType 推导，
     * 调用方不应再从"内容为空字符串"去猜文件是否存在（新增文件的 beforeContent 也是空串）。
     * {@code beforeTruncated}/{@code afterTruncated} 表示内容被服务端截断，此时内容不能作为回退依据。
     */
    public record FileEntry(String changeId, Long changeSetId, String relativePath, String changeType, String status,
                            boolean beforeExisted, boolean afterExisted, boolean beforeTruncated, boolean afterTruncated,
                            String beforeContent, String afterContent, String updateTime) {
    }

    /**
     * 下次请求要带回的游标。
     *
     * @param afterChangeSetId 新集合进度（已完整落地的最后一个 change-set）
     * @param tailUpdateTime   尾巴扫描进度时间（ISO-8601 本地时间，秒精度；不要加小数）
     * @param tailChangeId     尾巴扫描进度的 tie-breaker；空串表示"该秒全部行都已交付"
     */
    public record Cursor(Long afterChangeSetId, String tailUpdateTime, String tailChangeId) {
    }
}
