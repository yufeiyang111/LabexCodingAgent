package com.labex.monitor.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.labex.entity.AgentTask;
import com.labex.entity.AppUser;
import com.labex.mapper.AgentTaskMapper;
import com.labex.mapper.AppUserMapper;
import com.labex.monitor.audit.AuditRecordingService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitorUserActionService {

    private final AppUserMapper appUserMapper;
    private final AgentTaskMapper agentTaskMapper;
    private final AuditRecordingService auditRecordingService;

    public MonitorUserActionService(AppUserMapper appUserMapper,
                                   AgentTaskMapper agentTaskMapper,
                                   AuditRecordingService auditRecordingService) {
        this.appUserMapper = appUserMapper;
        this.agentTaskMapper = agentTaskMapper;
        this.auditRecordingService = auditRecordingService;
    }

    @Transactional
    public void freezeUser(Integer userId, String operatorId, String operatorRole, String sourceIp, String reason) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        int beforeStatus = user.getStatus() == null ? 1 : user.getStatus();
        user.setStatus(0);
        appUserMapper.updateById(user);

        auditRecordingService.record(
                "USER_FREEZE",
                "USER",
                String.valueOf(userId),
                operatorId,
                operatorRole,
                sourceIp,
                reason == null || reason.isBlank() ? "管理员手动冻结用户" : reason,
                "status=" + beforeStatus,
                "status=0",
                "SUCCESS",
                null
        );
    }

    @Transactional
    public void unfreezeUser(Integer userId, String operatorId, String operatorRole, String sourceIp, String reason) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        int beforeStatus = user.getStatus() == null ? 0 : user.getStatus();
        user.setStatus(1);
        appUserMapper.updateById(user);

        auditRecordingService.record(
                "USER_UNFREEZE",
                "USER",
                String.valueOf(userId),
                operatorId,
                operatorRole,
                sourceIp,
                reason == null || reason.isBlank() ? "管理员手动解冻用户" : reason,
                "status=" + beforeStatus,
                "status=1",
                "SUCCESS",
                null
        );
    }

    @Transactional
    public int terminateActiveTasks(Integer userId, String operatorId, String operatorRole, String sourceIp, String reason) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }

        List<String> activeStatuses = List.of("queued", "preparing", "running", "recovering", "waiting_approval", "waiting_user", "waiting_workspace");
        LambdaQueryWrapper<AgentTask> query = new LambdaQueryWrapper<>();
        query.eq(AgentTask::getStudentId, userId).in(AgentTask::getStatus, activeStatuses);
        List<AgentTask> activeTasks = agentTaskMapper.selectList(query);

        if (activeTasks.isEmpty()) {
            return 0;
        }

        LambdaUpdateWrapper<AgentTask> update = new LambdaUpdateWrapper<>();
        update.eq(AgentTask::getStudentId, userId)
                .in(AgentTask::getStatus, activeStatuses)
                .set(AgentTask::getStatus, "cancelled")
                .set(AgentTask::getSummary, "运维管理员强制终止");
        agentTaskMapper.update(null, update);

        auditRecordingService.record(
                "USER_TERMINATE_TASKS",
                "USER",
                String.valueOf(userId),
                operatorId,
                operatorRole,
                sourceIp,
                reason == null || reason.isBlank() ? "管理员中断所有运行中任务" : reason,
                "activeTaskCount=" + activeTasks.size(),
                "cancelledCount=" + activeTasks.size(),
                "SUCCESS",
                null
        );
        return activeTasks.size();
    }
}
