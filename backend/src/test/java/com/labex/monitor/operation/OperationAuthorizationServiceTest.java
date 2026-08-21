package com.labex.monitor.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.monitor.auth.MonitorAccessService;
import com.labex.monitor.auth.MonitorRoleService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OperationAuthorizationServiceTest {

    private final MonitorRoleService roleService = new MonitorRoleService();
    private final MonitorAccessService accessService = mock(MonitorAccessService.class);
    private final OperationAuthorizationService service = new OperationAuthorizationService(roleService, accessService);

    private HttpServletRequest request(String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(MonitorRoleService.REQUEST_ATTRIBUTE_ROLE)).thenReturn(role);
        return request;
    }

    @Test
    void operatorSessionPassesWithoutCode() {
        assertThat(service.requireOperator(request(MonitorRoleService.OPERATOR), null))
                .isEqualTo(MonitorRoleService.OPERATOR);
    }

    @Test
    void adminSessionPassesWithoutCode() {
        assertThat(service.requireOperator(request(MonitorRoleService.ADMIN), null))
                .isEqualTo(MonitorRoleService.ADMIN);
    }

    @Test
    void viewerSessionWithValidOperatorCodeIsPromoted() {
        when(accessService.verifyOperatorCode("good-code")).thenReturn(true);

        assertThat(service.requireOperator(request(MonitorRoleService.VIEWER), "good-code"))
                .isEqualTo(MonitorRoleService.OPERATOR);
    }

    @Test
    void viewerSessionWithInvalidCodeIsRejected() {
        when(accessService.verifyOperatorCode("bad-code")).thenReturn(false);

        assertThatThrownBy(() -> service.requireOperator(request(MonitorRoleService.VIEWER), "bad-code"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void viewerSessionWithoutCodeIsRejected() {
        assertThatThrownBy(() -> service.requireOperator(request(MonitorRoleService.VIEWER), null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void viewerSessionWithBlankCodeIsRejected() {
        assertThatThrownBy(() -> service.requireOperator(request(MonitorRoleService.VIEWER), "  "))
                .isInstanceOf(ResponseStatusException.class);
    }
}