package com.labex.labexagent.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentChangeSet;
import com.labex.entity.AgentFileChange;
import com.labex.labexagent.diff.GitSnapshotService;
import com.labex.mapper.AgentChangeSetMapper;
import com.labex.mapper.AgentFileChangeMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * B1 同步供给：把既有 t_agent_change_set / t_agent_file_change 投影成
 * "有序、可分页、可续拉"的只读增量流，供本地（浏览器 / daemon）按 change-set 落地。
 *
 * <p>边界：只读；不新建事实源；不决定客户端是否应用成功（那是客户端 journal 的职责）。
 *
 * <h2>两条互相独立的进度线</h2>
 * 客户端必须分别保存并在下次请求里原样带回，**不能只带一个游标**：
 * <ol>
 *   <li><b>change-set 进度</b> {@code afterChangeSetId}：已完整落地的最后一个 change-set。
 *       只决定"哪些新集合要下发"。</li>
 *   <li><b>尾巴扫描进度</b> {@code (tailUpdateTime, tailChangeId)}：已交付过的"游标之内变更"
 *       位置。只决定"旧集合里哪些文件的新状态还要补发"。</li>
 * </ol>
 * 两者若合成一个游标，就会出现"新集合推进了、尾巴没扫完"或反之的互相吞没
 * （P0 回归：尾巴页被截断后按水位线续拉会永久漏掉剩余文件）。
 *
 * <h2>时间精度与重叠窗口</h2>
 * {@code t_agent_file_change.update_time} 是**秒级** DATETIME，而 MySQL 对超出精度的写入是四舍五入。
 * 因此：
 * <ul>
 *   <li>游标时间一律**截断到秒**后再进 SQL：带纳秒的游标会比库里任何行都"新"，直接吃掉待交付的行；</li>
 *   <li>一次扫描完成（未截断）后，续拉点回退 {@code tailOverlapSeconds} 秒（默认 2）并清空
 *       change_id 条件，使与边界同秒的行会被**重复交付**而不是丢失——重复由客户端按
 *       {@code (changeId, status, updateTime)} 幂等去重，漏交付无法事后补救；</li>
 *   <li>扫描上界取"请求时刻 - 重叠"，保证边界行留到下一轮，而不是依赖两个时钟完全同步。</li>
 * </ul>
 */
@Service
public class AgentSyncFeedService {

    private static final String CHANGE_TYPE_CREATE = "create";
    private static final String CHANGE_TYPE_DELETE = "delete";

    /**
     * 游标时间一律用定宽格式输出。
     * 不能用 {@code LocalDateTime.toString()}：秒为 0 时它会省略秒（{@code 09:30}），
     * 游标字符串会在调用之间变形，日志/契约核对/客户端缓存都难以比对。
     */
    private static final DateTimeFormatter CURSOR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AgentChangeSetMapper changeSetMapper;
    private final AgentFileChangeMapper fileChangeMapper;
    private final AgentSyncFeedProperties properties;
    private final Clock clock;

    @Autowired
    public AgentSyncFeedService(AgentChangeSetMapper changeSetMapper,
                                AgentFileChangeMapper fileChangeMapper,
                                AgentSyncFeedProperties properties) {
        // 水位线必须与库里 update_time 的写入时钟一致：既有代码（DiffService 等）用
        // LocalDateTime.now() 写行，MySQL 的 CURRENT_TIMESTAMP 也用会话时区，
        // 所以只能用默认时区。用 systemUTC 会把水位线整体提前一个时区偏移量。
        this(changeSetMapper, fileChangeMapper, properties, Clock.systemDefaultZone());
    }

    AgentSyncFeedService(AgentChangeSetMapper changeSetMapper,
                         AgentFileChangeMapper fileChangeMapper,
                         AgentSyncFeedProperties properties,
                         Clock clock) {
        this.changeSetMapper = changeSetMapper;
        this.fileChangeMapper = fileChangeMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 读取一页同步供给。
     *
     * @param studentId        归属校验，null 时返回空页（控制器已保证非空）
     * @param projectId        归属校验，null 时返回空页
     * @param afterChangeSetId 已完整落地的最后一个 change-set；null 视为 0
     * @param tailUpdateTime   尾巴扫描续拉点（ISO-8601 本地时间，秒精度）；null 表示首次同步
     * @param tailChangeId     尾巴扫描续拉点的 tie-breaker；null 视为空串
     * @param limit            期望的 change-set 条数，经配置夹取
     */
    public AgentSyncFeed fetch(Integer studentId, Integer projectId, Long afterChangeSetId,
                               String tailUpdateTime, String tailChangeId, Integer limit) {
        long after = afterChangeSetId == null || afterChangeSetId < 0L ? 0L : afterChangeSetId;
        int pageSize = properties.clampPageSize(limit);
        int tailPageSize = properties.getMaxChangedFilesPerPage();
        // 上界必须在任何查询之前确定，并回退一个重叠窗口：这样"请求期间写入的行"
        // 与"游标同秒的行"都留给下一轮，而不是依赖 JVM 时钟与 DB 时钟完全一致。
        LocalDateTime scanUpperBound = floorToSecond(LocalDateTime.now(clock))
                .minusSeconds(properties.getTailOverlapSeconds());
        LocalDateTime tailFrom = parseTailTime(tailUpdateTime);
        String tailFromChangeId = tailChangeId == null ? "" : tailChangeId;

        if (studentId == null || projectId == null) {
            return new AgentSyncFeed(List.of(), List.of(),
                    new AgentSyncFeed.Cursor(after, scanUpperBound.toString(), ""), false, false, false);
        }

        List<AgentChangeSet> changeSets = loadChangeSets(studentId, projectId, after, pageSize);
        boolean hasMoreChangeSets = changeSets.size() > pageSize;
        if (hasMoreChangeSets) {
            changeSets = new ArrayList<>(changeSets.subList(0, pageSize));
        }
        Map<Long, List<AgentFileChange>> filesByChangeSet = loadFilesByChangeSet(changeSets);

        List<AgentSyncFeed.ChangeSet> projected = new ArrayList<>(changeSets.size());
        for (AgentChangeSet changeSet : changeSets) {
            List<AgentFileChange> files = filesByChangeSet.getOrDefault(changeSet.getChangeSetId(), List.of());
            projected.add(new AgentSyncFeed.ChangeSet(
                    changeSet.getChangeSetId(),
                    changeSet.getTaskId(),
                    changeSet.getStatus(),
                    format(changeSet.getUpdateTime()),
                    files.stream().map(this::toEntry).toList()));
        }

        List<AgentSyncFeed.FileEntry> changedFiles = List.of();
        boolean hasMoreChangedFiles = false;
        String nextTailTime = scanUpperBound.toString();
        String nextTailChangeId = "";
        if (tailFrom != null) {
            List<AgentFileChange> dirty = loadFilesChangedSince(studentId, projectId, after, tailFrom,
                    tailFromChangeId, scanUpperBound, tailPageSize);
            if (dirty.size() > tailPageSize) {
                // 截断：续拉点必须是**最后一条已交付行的键**，这样下一页正好从它之后继续。
                // 用水位线续拉会跳过这一页没装下的行（P0 回归）。
                dirty = new ArrayList<>(dirty.subList(0, tailPageSize));
                AgentFileChange last = dirty.get(dirty.size() - 1);
                nextTailTime = format(last.getUpdateTime());
                nextTailChangeId = last.getChangeId() == null ? "" : last.getChangeId();
                hasMoreChangedFiles = true;
            }
            changedFiles = dirty.stream().map(this::toEntry).toList();
        }

        long nextAfterChangeSetId = changeSets.isEmpty()
                ? after
                : changeSets.get(changeSets.size() - 1).getChangeSetId();
        return new AgentSyncFeed(projected, changedFiles,
                new AgentSyncFeed.Cursor(nextAfterChangeSetId, nextTailTime, nextTailChangeId),
                hasMoreChangeSets || hasMoreChangedFiles, hasMoreChangeSets, hasMoreChangedFiles);
    }

    private List<AgentChangeSet> loadChangeSets(Integer studentId, Integer projectId, long after, int pageSize) {
        // 多取一条用于判断 hasMore，避免额外 count 查询。
        return this.changeSetMapper.selectList(new LambdaQueryWrapper<AgentChangeSet>()
                .eq(AgentChangeSet::getStudentId, studentId)
                .eq(AgentChangeSet::getProjectId, projectId)
                .gt(AgentChangeSet::getChangeSetId, after)
                .orderByAsc(AgentChangeSet::getChangeSetId)
                .last("LIMIT " + (pageSize + 1)));
    }

    private Map<Long, List<AgentFileChange>> loadFilesByChangeSet(List<AgentChangeSet> changeSets) {
        if (changeSets.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = changeSets.stream().map(AgentChangeSet::getChangeSetId).toList();
        List<AgentFileChange> files = this.fileChangeMapper.selectList(new LambdaQueryWrapper<AgentFileChange>()
                .in(AgentFileChange::getChangeSetId, ids));
        Map<Long, List<AgentFileChange>> grouped = new HashMap<>();
        for (AgentFileChange file : files) {
            grouped.computeIfAbsent(file.getChangeSetId(), key -> new ArrayList<>()).add(file);
        }
        Map<Long, List<AgentFileChange>> ordered = new LinkedHashMap<>();
        for (Long id : ids) {
            ordered.put(id, sortFiles(grouped.getOrDefault(id, List.of())));
        }
        return ordered;
    }

    /**
     * 尾巴扫描：游标之内的文件，按 (update_time, change_id) 复合键严格推进。
     *
     * <p>用复合键而不是单纯 {@code update_time >}：update_time 只有秒精度，
     * 同一秒内多行（含同一路径被连续修改）在同一秒的情况很常见，只比时间会把它们整批跳过。
     */
    private List<AgentFileChange> loadFilesChangedSince(Integer studentId, Integer projectId, long after,
                                                        LocalDateTime tailFrom, String tailFromChangeId,
                                                        LocalDateTime scanUpperBound, int tailPageSize) {
        return this.fileChangeMapper.selectList(new LambdaQueryWrapper<AgentFileChange>()
                .eq(AgentFileChange::getStudentId, studentId)
                .eq(AgentFileChange::getProjectId, projectId)
                .le(AgentFileChange::getChangeSetId, after)
                .le(AgentFileChange::getUpdateTime, scanUpperBound)
                .and(outer -> outer
                        .gt(AgentFileChange::getUpdateTime, tailFrom)
                        .or(sameSecond -> sameSecond
                                .eq(AgentFileChange::getUpdateTime, tailFrom)
                                .gt(AgentFileChange::getChangeId, tailFromChangeId)))
                .orderByAsc(AgentFileChange::getUpdateTime)
                .orderByAsc(AgentFileChange::getChangeId)
                .last("LIMIT " + (tailPageSize + 1)));
    }

    /**
     * 集合内文件顺序：create_time 升序、同秒再按 change_id。
     * 注意 create_time 是秒级 DATETIME，同一秒内只能靠 change_id（UUID）定序——
     * 这是"同一秒内对同一路径的多次修改顺序不可靠"的已知残留风险，见评审报告 C04。
     */
    private List<AgentFileChange> sortFiles(List<AgentFileChange> files) {
        List<AgentFileChange> sorted = new ArrayList<>(files);
        sorted.sort(Comparator
                .comparing(AgentFileChange::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AgentFileChange::getChangeId, Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }

    private AgentSyncFeed.FileEntry toEntry(AgentFileChange file) {
        String changeType = file.getChangeType();
        return new AgentSyncFeed.FileEntry(
                file.getChangeId(),
                file.getChangeSetId(),
                file.getRelativePath(),
                changeType,
                file.getStatus(),
                !CHANGE_TYPE_CREATE.equalsIgnoreCase(changeType),
                !CHANGE_TYPE_DELETE.equalsIgnoreCase(changeType),
                isTruncated(file.getBeforeContent()),
                isTruncated(file.getAfterContent()),
                file.getBeforeContent(),
                file.getAfterContent(),
                format(file.getUpdateTime()));
    }

    private boolean isTruncated(String content) {
        return content != null && content.endsWith(GitSnapshotService.TRUNCATION_MARKER);
    }

    private LocalDateTime parseTailTime(String tailUpdateTime) {
        if (tailUpdateTime == null || tailUpdateTime.isBlank()) {
            return null;
        }
        try {
            // 截断到秒：游标精度高于列精度时，游标会比库中任何行都"新"，从而吃掉未交付的行。
            return floorToSecond(LocalDateTime.parse(tailUpdateTime.trim()));
        } catch (DateTimeParseException failure) {
            // 不静默降级：游标格式错误若被当成"首次同步"，会丢掉游标之内发生的全部状态变化。
            throw new IllegalArgumentException(
                    "tailUpdateTime \u683c\u5f0f\u65e0\u6cd5\u89e3\u6790\uff0c\u5e94\u4e3a ISO-8601 \u672c\u5730\u65f6\u95f4\uff1a" + tailUpdateTime);
        }
    }

    private LocalDateTime floorToSecond(LocalDateTime value) {
        return value.withNano(0);
    }

    private String format(LocalDateTime value) {
        return value == null ? null : CURSOR_FORMAT.format(value);
    }
}
