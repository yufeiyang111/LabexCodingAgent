package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentMessage;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AgentConversationForkBoundaryServiceTest {

    @BeforeAll
    static void initializeEntityMetadata() {
        for (Class<?> type : List.of(AgentConversation.class, AgentMessage.class, AgentTask.class)) {
            if (TableInfoHelper.getTableInfo(type) == null) {
                TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
            }
        }
    }

    @Test
    void lazyBackfillUsesTheLastStableTaskCompletedBeforeTheChildWasCreated() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper messages = mock(AgentMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        LocalDateTime childCreated = LocalDateTime.of(2026, 8, 4, 10, 30);
        when(tasks.selectList(any())).thenReturn(List.of(
                task(11L, "completed", childCreated.minusMinutes(5)),
                task(12L, "completed", childCreated.plusMinutes(5))));
        when(conversations.update(any(), any())).thenReturn(1);
        AgentConversationForkBoundaryService service =
                new AgentConversationForkBoundaryService(conversations, messages, tasks);
        AgentConversation child = child(childCreated);

        Long boundary = service.resolveExistingFork(child);

        assertThat(boundary).isEqualTo(11L);
        assertThat(child.getForkedFromTaskId()).isEqualTo(11L);
        verify(conversations).update(any(), any());
        verify(messages, never()).selectById(any());
    }

    @Test
    void explicitTaskFromAnotherConversationIsRejected() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper messages = mock(AgentMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentTask foreign = task(11L, "completed", LocalDateTime.now());
        foreign.setConversationId("another");
        when(tasks.selectList(any())).thenReturn(List.of(foreign));
        AgentConversationForkBoundaryService service =
                new AgentConversationForkBoundaryService(conversations, messages, tasks);

        assertThatThrownBy(() -> service.resolveNewFork(7, 3, "parent", null, 11L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stable");
    }

    private AgentConversation child(LocalDateTime createTime) {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("child");
        conversation.setParentConversationId("parent");
        conversation.setStudentId(7);
        conversation.setProjectId(3);
        conversation.setStatus(1);
        conversation.setCreateTime(createTime);
        return conversation;
    }

    private AgentTask task(long id, String status, LocalDateTime finishedAt) {
        AgentTask task = new AgentTask();
        task.setTaskId(id);
        task.setConversationId("parent");
        task.setStudentId(7);
        task.setProjectId(3);
        task.setStatus(status);
        task.setCreateTime(finishedAt.minusMinutes(1));
        task.setUpdateTime(finishedAt);
        task.setFinishedAt(finishedAt);
        return task;
    }
}