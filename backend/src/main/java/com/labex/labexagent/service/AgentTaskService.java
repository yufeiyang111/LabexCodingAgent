package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.labex.entity.AgentChangeSet;
import com.labex.entity.AgentFileChange;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.mapper.AgentChangeSetMapper;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentTaskService {
    private final AgentTaskMapper taskMapper;
    private final AgentChangeSetMapper changeSetMapper;
    private final AgentFileChangeMapper fileChangeMapper;

    public AgentTaskService(AgentTaskMapper taskMapper, AgentChangeSetMapper changeSetMapper, AgentFileChangeMapper fileChangeMapper) {
        this.taskMapper = taskMapper;
        this.changeSetMapper = changeSetMapper;
        this.fileChangeMapper = fileChangeMapper;
    }

    public AgentTask createTask(Integer studentId, StudentProject project, String conversationId, String sessionId, String mode, String message) {
        AgentTask task = new AgentTask();
        task.setConversationId(conversationId);
        task.setSessionId(sessionId);
        task.setStudentId(studentId);
        task.setProjectId(project.getProjectId());
        task.setTitle(this.title(message));
        task.setMode(mode);
        task.setStatus("running");
        task.setCurrentStep("\u5206\u6790\u4efb\u52a1");
        task.setSummary("");
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        this.taskMapper.insert(task);
        return task;
    }

    public void updateTask(Long taskId, String status, String currentStep, String summary) {
        if (taskId == null) {
            return;
        }
        LambdaUpdateWrapper<AgentTask> update = new LambdaUpdateWrapper<AgentTask>().eq(AgentTask::getTaskId, taskId).set(AgentTask::getUpdateTime, LocalDateTime.now());
        if (status != null) {
            update.set(AgentTask::getStatus, status);
        }
        if (currentStep != null) {
            update.set(AgentTask::getCurrentStep, currentStep);
        }
        if (summary != null) {
            update.set(AgentTask::getSummary, summary);
        }
        this.taskMapper.update(null, update);
    }

    public AgentChangeSet getOrCreateOpenChangeSet(Integer studentId, Integer projectId, String conversationId, Long taskId) {
        AgentChangeSet existing = this.changeSetMapper.selectOne(new LambdaQueryWrapper<AgentChangeSet>().eq(AgentChangeSet::getStudentId, studentId).eq(AgentChangeSet::getProjectId, projectId).eq(taskId != null, AgentChangeSet::getTaskId, taskId).eq(AgentChangeSet::getStatus, "pending").last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        AgentChangeSet changeSet = new AgentChangeSet();
        changeSet.setTaskId(taskId);
        changeSet.setConversationId(conversationId);
        changeSet.setStudentId(studentId);
        changeSet.setProjectId(projectId);
        changeSet.setStatus("pending");
        changeSet.setChangeCount(Integer.valueOf(0));
        changeSet.setSummary("\u7b49\u5f85\u786e\u8ba4\u7684\u6587\u4ef6\u4fee\u6539");
        changeSet.setCreateTime(LocalDateTime.now());
        changeSet.setUpdateTime(LocalDateTime.now());
        this.changeSetMapper.insert(changeSet);
        return changeSet;
    }

    public void incrementChangeCount(Long changeSetId) {
        if (changeSetId == null) {
            return;
        }
        List<?> changes = this.fileChangeMapper.selectList(new LambdaQueryWrapper<AgentFileChange>().eq(AgentFileChange::getChangeSetId, changeSetId));
        this.changeSetMapper.update(null, new LambdaUpdateWrapper<AgentChangeSet>().eq(AgentChangeSet::getChangeSetId, changeSetId).set(AgentChangeSet::getChangeCount, changes.size()).set(AgentChangeSet::getUpdateTime, LocalDateTime.now()));
    }

    public List<AgentTask> listTasks(Integer studentId, Integer projectId) {
        return this.taskMapper.selectList(new LambdaQueryWrapper<AgentTask>().eq(AgentTask::getStudentId, studentId).eq(AgentTask::getProjectId, projectId).orderByDesc(AgentTask::getUpdateTime).last("LIMIT 30"));
    }

    public List<AgentFileChange> listPendingChanges(Integer studentId, Integer projectId) {
        return this.fileChangeMapper.selectList(new LambdaQueryWrapper<AgentFileChange>().eq(AgentFileChange::getStudentId, studentId).eq(AgentFileChange::getProjectId, projectId).in(AgentFileChange::getStatus, List.of("pending", "applied")).orderByDesc(AgentFileChange::getUpdateTime).last("LIMIT 80"));
    }

    private String title(String message) {
        String text = message == null || message.isBlank() ? "Agent \u4efb\u52a1" : message.trim().replaceAll("\\s+", " ");
        return text.length() > 36 ? text.substring(0, 36) + "..." : text;
    }
}

