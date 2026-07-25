package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    }

    @Test
    @SuppressWarnings("unchecked")
    void listPendingChangesIncludesConflictedChanges() {
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        AgentTaskService service = new AgentTaskService(
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
}
