package com.labex.labexagent.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.AgentFileChange;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.service.StudentProjectService;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentFileHistoryServiceTest {

    private AgentFileChangeMapper mapper;
    private StudentProjectService projectService;
    private AgentFileHistoryService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 纯单测环境没有 MP 启动流程，LambdaQueryWrapper 需要实体的列缓存。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentFileChange.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AgentFileChangeMapper.class);
        projectService = mock(StudentProjectService.class);
        service = new AgentFileHistoryService(mapper, projectService,
                new com.labex.labexagent.workspace.WorkspaceFileOperationProperties());
        var project = new com.labex.entity.StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        org.mockito.Mockito.when(projectService.getOwnedProject(7, 12)).thenReturn(project);
        org.mockito.Mockito.when(projectService.getOwnedProject(7, 99)).thenReturn(null);
    }

    private AgentFileChange row(String changeId, String relativePath) {
        AgentFileChange change = new AgentFileChange();
        change.setChangeId(changeId);
        change.setTaskId(88L);
        change.setConversationId("conv-1");
        change.setRelativePath(relativePath);
        change.setChangeType("edit");
        change.setStatus("applied");
        change.setDiff("- old\n+ new");
        return change;
    }

    @Test
    void rejectsQueriesForProjectsTheUserDoesNotOwn() {
        assertThrows(IllegalArgumentException.class, () -> service.history(7, 99, "a.txt", 1, 50));
        assertThrows(IllegalArgumentException.class, () -> service.diffText(7, 99, "change-1"));
    }

    @Test
    void mapsMetadataRowsWithoutLargeContentColumns() {
        Page<AgentFileChange> page = new Page<>(1, 50);
        page.setRecords(List.of(row("c-2", "src/a.txt"), row("c-1", "src/a.txt")));
        page.setTotal(2);
        doReturn(page).when(mapper).selectPage(any(), any());

        var result = service.history(7, 12, "src/a.txt", 1, 50);

        assertEquals(2, result.items().size());
        assertEquals("c-2", result.items().get(0).changeId());
        assertEquals(88L, result.items().get(0).taskId());
        assertEquals("applied", result.items().get(0).status());
        assertEquals(2, result.total());
    }

    @Test
    void clampsPageSizeToConfiguredBounds() {
        Page<AgentFileChange> page = new Page<>(1, 50);
        page.setRecords(List.of());
        doReturn(page).when(mapper).selectPage(any(), any());

        service.history(7, 12, "a.txt", 0, 500);
        service.history(7, 12, "a.txt", 1, 0);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Page<AgentFileChange>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Page.class);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(2)).selectPage(captor.capture(), any());
        assertEquals(100, captor.getAllValues().get(0).getSize());
        assertEquals(50, captor.getAllValues().get(1).getSize());
    }

    @Test
    void diffTextRequiresRowOwnership() {
        AgentFileChange foreign = row("c-9", "src/leak.txt");
        foreign.setStudentId(8);
        foreign.setProjectId(12);
        org.mockito.Mockito.when(mapper.selectById("c-9")).thenReturn(foreign);
        assertThrows(IllegalArgumentException.class, () -> service.diffText(7, 12, "c-9"));

        AgentFileChange owned = row("c-10", "src/mine.txt");
        owned.setStudentId(7);
        owned.setProjectId(12);
        org.mockito.Mockito.when(mapper.selectById("c-10")).thenReturn(owned);
        assertEquals("- old\n+ new", service.diffText(7, 12, "c-10"));

        org.mockito.Mockito.when(mapper.selectById("c-missing")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.diffText(7, 12, "c-missing"));
    }

    @Test
    void truncatesOversizedDiffPayloads() {
        AgentFileChange huge = row("c-big", "src/big.txt");
        huge.setStudentId(7);
        huge.setProjectId(12);
        StringBuilder diff = new StringBuilder();
        while (diff.length() <= 150_000) {
            diff.append("+ line\n");
        }
        String payload = diff.toString();
        huge.setDiff(payload);
        org.mockito.Mockito.when(mapper.selectById("c-big")).thenReturn(huge);

        String text = service.diffText(7, 12, "c-big");

        assertTrue(text.length() < payload.length());
        assertTrue(text.endsWith("[diff 已截断]"));
    }
}
