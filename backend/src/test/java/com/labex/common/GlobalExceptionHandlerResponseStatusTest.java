package com.labex.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.monitor.operation.OperationAuthorizationService;
import com.labex.monitor.auth.MonitorAccessService;
import com.labex.monitor.auth.MonitorRoleService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

/**
 * 回归：ResponseStatusException 必须保留原始 HTTP 状态码与 reason，
 * 不能落入 Exception 兜底变成 500（否则前端 403 弹操作码逻辑失效）。
 */
class GlobalExceptionHandlerResponseStatusTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void forbiddenStatusExceptionKeeps403AndReason() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseStatusException e = new ResponseStatusException(HttpStatus.FORBIDDEN,
                "当前角色无权执行此操作，请使用操作者校验码登录");

        Result<Void> result = handler.handleResponseStatus(e, response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(result.getMessage()).contains("操作者校验码");
    }

    @Test
    void unauthorizedStatusExceptionKeeps401() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseStatusException e = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");

        handler.handleResponseStatus(e, response);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void serverErrorStatusExceptionIsMaskedAs500() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseStatusException e = new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "boom");

        Result<Void> result = handler.handleResponseStatus(e, response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(result.getMessage()).contains("服务器内部错误");
    }

    @Test
    void realWorldPath_viewerRecoverLeasesProduces403Not500() {
        MonitorAccessService accessService = Mockito.mock(MonitorAccessService.class);
        OperationAuthorizationService service =
                new OperationAuthorizationService(new MonitorRoleService(), accessService);
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getAttribute(MonitorRoleService.REQUEST_ATTRIBUTE_ROLE))
                .thenReturn(MonitorRoleService.VIEWER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        Result<Void> result = null;
        try {
            service.requireOperator(request, null);
        } catch (ResponseStatusException e) {
            result = handler.handleResponseStatus(e, response);
        }

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(result).isNotNull();
        assertThat(result.getMessage()).contains("操作者校验码");
    }
}