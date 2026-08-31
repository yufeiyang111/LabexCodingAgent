package com.labex.labexagent.diff;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.StudentProject;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.entity.AgentFileChange;
import com.labex.service.StudentProjectService;
import com.labex.labexagent.workspace.WorkspaceFileOperationProperties;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 文件时间线（Agent 改动历史）的唯一查询入口，复用审批/回滚链路写入的
 * t_agent_file_change 数据。列表查询显式排除 before/after_content、diff_text 等
 * 大 TEXT 列（时间线只展示元数据）；diff 按需单条获取并截断，避免一次拖出几十 MB。
 */
@Service
public class AgentFileHistoryService {

    /** 单条 diff 返回给前端的字符上限；超出部分截断并提示。 */
    private static final int MAX_DIFF_CHARS = 100_000;

    private final AgentFileChangeMapper fileChangeMapper;
    private final StudentProjectService studentProjectService;
    private final WorkspaceFileOperationProperties properties;

    public AgentFileHistoryService(AgentFileChangeMapper fileChangeMapper,
                                   StudentProjectService studentProjectService,
                                   WorkspaceFileOperationProperties properties) {
        this.fileChangeMapper = fileChangeMapper;
        this.studentProjectService = studentProjectService;
        this.properties = properties;
    }

    public record HistoryItem(String changeId, Long taskId, String conversationId, String relativePath,
                              String changeType, String status, LocalDateTime createTime,
                              LocalDateTime updateTime, LocalDateTime appliedTime) {
    }

    public record HistoryPage(List<HistoryItem> items, long total, int page, int pageSize) {
    }

    /**
     * 查询某个文件/文件夹的改动历史。
     * path 以 "/" 结尾视为文件夹前缀匹配，否则按文件精确匹配；
     * 查询条件始终叠加 studentId + projectId，杜绝跨租户读取。
     */
    public HistoryPage history(Integer studentId, Integer projectId, String relativePath,
                               int page, int pageSize) {
        requireOwnedProject(studentId, projectId);
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        String normalized = relativePath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        boolean folderMode = normalized.endsWith("/");
        String matchPath = folderMode ? normalized.substring(0, normalized.length() - 1) : normalized;

        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize <= 0 ? properties.getHistoryPageSize() : pageSize));

        LambdaQueryWrapper<AgentFileChange> wrapper = new LambdaQueryWrapper<AgentFileChange>()
                .eq(AgentFileChange::getStudentId, studentId)
                .eq(AgentFileChange::getProjectId, projectId);
        if (folderMode) {
            // 前缀带 "/"，避免 "a/a" 误匹配到同级文件 "a/ab.txt"。
            wrapper.likeRight(AgentFileChange::getRelativePath, matchPath + "/");
        } else {
            wrapper.eq(AgentFileChange::getRelativePath, matchPath);
        }
        wrapper.orderByDesc(AgentFileChange::getCreateTime)
                .select(AgentFileChange::getChangeId, AgentFileChange::getTaskId,
                        AgentFileChange::getConversationId, AgentFileChange::getRelativePath,
                        AgentFileChange::getChangeType, AgentFileChange::getStatus,
                        AgentFileChange::getCreateTime, AgentFileChange::getUpdateTime,
                        AgentFileChange::getAppliedTime);
        Page<AgentFileChange> result = fileChangeMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<HistoryItem> items = result.getRecords().stream()
                .map(row -> new HistoryItem(row.getChangeId(), row.getTaskId(), row.getConversationId(),
                        row.getRelativePath(), row.getChangeType(), row.getStatus(), row.getCreateTime(),
                        row.getUpdateTime(), row.getAppliedTime()))
                .toList();
        return new HistoryPage(items, result.getTotal(), safePage, safeSize);
    }

    /** 单条改动的 diff 文本；归属校验不通过时按不存在处理。 */
    public String diffText(Integer studentId, Integer projectId, String changeId) {
        requireOwnedProject(studentId, projectId);
        if (changeId == null || changeId.isBlank()) {
            throw new IllegalArgumentException("changeId 不能为空");
        }
        AgentFileChange row = fileChangeMapper.selectById(changeId);
        if (row == null || !studentId.equals(row.getStudentId()) || !projectId.equals(row.getProjectId())) {
            throw new IllegalArgumentException("改动记录不存在");
        }
        String diff = row.getDiff();
        if (diff == null || diff.isBlank()) {
            return "";
        }
        if (diff.length() > MAX_DIFF_CHARS) {
            return diff.substring(0, MAX_DIFF_CHARS) + "\n... [diff 已截断]";
        }
        return diff;
    }

    private void requireOwnedProject(Integer studentId, Integer projectId) {
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
    }
}
