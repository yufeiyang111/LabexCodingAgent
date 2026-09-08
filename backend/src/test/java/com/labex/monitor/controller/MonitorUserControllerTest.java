package com.labex.monitor.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.monitor.access.ClientIpResolver;
import com.labex.monitor.auth.MonitorAccessService;
import com.labex.monitor.auth.MonitorAuthInterceptor;
import com.labex.monitor.auth.MonitorRoleService;
import com.labex.monitor.config.MonitorProperties;
import com.labex.monitor.user.MonitorUserActionService;
import com.labex.monitor.user.MonitorUserQueryService;
import com.labex.monitor.user.dto.UserDetailDto;
import com.labex.monitor.user.dto.UserOverviewDto;
import com.labex.monitor.user.dto.UserProfileDto;
import com.labex.monitor.user.dto.UserSummaryDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MonitorUserControllerTest {

    private final MonitorAccessService accessService = mock(MonitorAccessService.class);
    private final MonitorUserQueryService queryService = mock(MonitorUserQueryService.class);
    private final MonitorUserActionService actionService = mock(MonitorUserActionService.class);
    private final MonitorRoleService roleService = new MonitorRoleService();
    private final ClientIpResolver ipResolver = mock(ClientIpResolver.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MonitorProperties properties = new MonitorProperties();
        properties.setEnabled(true);
        properties.setAccessCode("s3cret");

        MonitorAuthInterceptor interceptor =
                new MonitorAuthInterceptor(accessService, properties, new ObjectMapper());

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new MonitorUserController(queryService, actionService, roleService, ipResolver))
                .addInterceptors(interceptor)
                .build();
    }

    private void allowAccess(String token, String role) {
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken(token)).thenReturn(true);
        when(accessService.sessionRole(token)).thenReturn(role);
    }

    @Test
    void unauthenticatedAccessIsRejected() throws Exception {
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken(null)).thenReturn(false);

        mockMvc.perform(get("/ops/users/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void overviewReturnsData() throws Exception {
        allowAccess("token-123", MonitorRoleService.VIEWER);
        UserOverviewDto overview = UserOverviewDto.builder()
                .onlineCount(10)
                .todayDau(50)
                .dauChangeRatio(0.1)
                .weeklyWau(200)
                .totalRegistered(1000)
                .avgTokensPerActiveUser(25000)
                .avgTasksPerActiveUser(4.5)
                .trendSeries(List.of())
                .build();

        when(queryService.getOverview("7d")).thenReturn(overview);

        mockMvc.perform(get("/ops/users/overview")
                        .header("X-Monitor-Token", "token-123")
                        .param("range", "7d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.onlineCount").value(10))
                .andExpect(jsonPath("$.data.todayDau").value(50));
    }

    @Test
    void userListReturnsPage() throws Exception {
        allowAccess("token-123", MonitorRoleService.VIEWER);
        UserSummaryDto u = UserSummaryDto.builder()
                .userId(101)
                .username("testuser")
                .role("STUDENT")
                .status(1)
                .online(true)
                .build();

        when(queryService.getUsers(any(), any(), any(), any(), any(), eq(1), eq(20)))
                .thenReturn(Map.of("list", List.of(u), "total", 1, "page", 1, "pageSize", 20));

        mockMvc.perform(get("/ops/users")
                        .header("X-Monitor-Token", "token-123")
                        .param("page", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].userId").value(101))
                .andExpect(jsonPath("$.data.list[0].username").value("testuser"));
    }

    @Test
    void userDetailReturnsNotFoundWhenMissing() throws Exception {
        allowAccess("token-123", MonitorRoleService.VIEWER);
        when(queryService.getUserDetail(999)).thenReturn(null);

        mockMvc.perform(get("/ops/users/999").header("X-Monitor-Token", "token-123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void userDetailReturnsDataWhenExists() throws Exception {
        allowAccess("token-123", MonitorRoleService.VIEWER);
        UserDetailDto detail = UserDetailDto.builder()
                .profile(UserProfileDto.builder().userId(101).username("testuser").build())
                .build();
        when(queryService.getUserDetail(101)).thenReturn(detail);

        mockMvc.perform(get("/ops/users/101").header("X-Monitor-Token", "token-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.profile.userId").value(101));
    }

    @Test
    void userActivitiesReturnsList() throws Exception {
        allowAccess("token-123", MonitorRoleService.VIEWER);
        when(queryService.getUserActivities(eq(101), any(), anyBoolean(), eq(1), eq(30)))
                .thenReturn(Map.of("list", List.of(), "total", 0, "page", 1, "pageSize", 30));

        mockMvc.perform(get("/ops/users/101/activities").header("X-Monitor-Token", "token-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void viewerRoleCannotPerformUserAction() throws Exception {
        allowAccess("token-123", MonitorRoleService.VIEWER);

        mockMvc.perform(post("/ops/users/101/actions/FREEZE")
                        .header("X-Monitor-Token", "token-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"恶意请求\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void operatorRoleCanFreezeUser() throws Exception {
        allowAccess("token-123", MonitorRoleService.OPERATOR);
        when(ipResolver.resolve(any())).thenReturn("127.0.0.1");

        mockMvc.perform(post("/ops/users/101/actions/FREEZE")
                        .header("X-Monitor-Token", "token-123")
                        .header("X-Operator-Code", "OP-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"恶意请求\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.action").value("FREEZE"));

        verify(actionService).freezeUser(eq(101), eq("OP-01"), eq(MonitorRoleService.OPERATOR), eq("127.0.0.1"), eq("恶意请求"));
    }

    @Test
    void operatorRoleCanTerminateTasks() throws Exception {
        allowAccess("token-123", MonitorRoleService.OPERATOR);
        when(ipResolver.resolve(any())).thenReturn("127.0.0.1");
        when(actionService.terminateActiveTasks(eq(101), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(3);

        mockMvc.perform(post("/ops/users/101/actions/TERMINATE_ACTIVE_TASKS")
                        .header("X-Monitor-Token", "token-123")
                        .header("X-Operator-Code", "OP-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"紧急中断\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cancelledCount").value(3));
    }
}
