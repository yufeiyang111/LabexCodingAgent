package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.labex.entity.AgentChangeSet;
import com.labex.entity.AgentFileChange;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentChangeSetMapper;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.labexagent.run.BackgroundRunWorktreeService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentTaskServiceTest {

    @BeforeAll
    static void initializeEntityMetadata() {
        if (TableInfoHelper.getTableInfo(AgentFileChange.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    AgentFileChange.class);
        }
        if (TableInfoHelper.getTableInfo(AgentTask.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    AgentTask.class);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void findsTheLatestNonTerminalTaskForTheRequestedConversation() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask active = new AgentTask();
        active.setTaskId(72L);
        active.setStatus("running");
        when(taskMapper.selectOne(org.mockito.ArgumentMatchers.any(LambdaQueryWrapper.class))).thenReturn(active);
        AgentTaskService service = newTaskService(taskMapper, mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class));

        AgentTask result = service.findLatestActiveTask(7, 12, "conversation-71");

        assertEquals(active, result);
        ArgumentCaptor<LambdaQueryWrapper<AgentTask>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper).selectOne(queryCaptor.capture());
        AbstractWrapper<AgentTask, ?, ?> query = queryCaptor.getValue();
        query.getSqlSegment();
        assertTrue(query.getParamNameValuePairs().containsValue(7));
        assertTrue(query.getParamNameValuePairs().containsValue(12));
        assertTrue(query.getParamNameValuePairs().containsValue("conversation-71"));
        assertTrue(query.getParamNameValuePairs().containsValue("completed"));
        assertTrue(query.getParamNameValuePairs().containsValue("failed"));
        assertTrue(query.getParamNameValuePairs().containsValue("cancelled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listPendingChangesIncludesConflictedChanges() {
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        AgentTaskService service = newTaskService(
                mock(AgentTaskMapper.class),
                mock(AgentChangeSetMapper.class),
                fileChangeMapper);

        service.listPendingChanges(7, 12);

        ArgumentCaptor<LambdaQueryWrapper<AgentFileChange>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fileChangeMapper).selectList(queryCaptor.capture());
        AbstractWrapper<AgentFileChange, ?, ?> query = queryCaptor.getValue();
        query.getSqlSegment();

        assertTrue(query.getParamNameValuePairs().containsValue("conflicted"));
    }

    private AgentTaskService newTaskService(AgentTaskMapper taskMapper,
                                            AgentChangeSetMapper changeSetMapper,
                                            AgentFileChangeMapper fileChangeMapper) {
        return new AgentTaskService(taskMapper, changeSetMapper, fileChangeMapper,
                mock(AgentRunLifecycleService.class), mock(AgentRunExecutionLeaseService.class),
                mock(BackgroundRunWorktreeService.class));
    }
}
