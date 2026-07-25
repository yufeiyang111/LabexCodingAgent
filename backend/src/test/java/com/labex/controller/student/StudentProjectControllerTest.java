package com.labex.controller.student;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.common.Result;
import com.labex.entity.StudentProject;
import com.labex.service.StudentProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

class StudentProjectControllerTest {

    @Test
    void detailReturnsTheOwnedProjectWithoutRefreshingItsEntireWorkspace() {
        StudentProjectService projectService = mock(StudentProjectService.class);
        StudentProject project = new StudentProject();
        project.setProjectId(2);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("7");
        when(projectService.getOwnedProject(7, 2)).thenReturn(project);
        StudentProjectController controller = new StudentProjectController();
        ReflectionTestUtils.setField(controller, "studentProjectService", projectService);

        Result<StudentProject> response = controller.detail(2, authentication);

        assertSame(project, response.getData());
        verify(projectService).getOwnedProject(7, 2);
        verify(projectService, never()).refreshProjectMetadata(7, 2);
    }
}
