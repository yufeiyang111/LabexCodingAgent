package com.labex.labexagent.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.common.Result;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.permission.PermissionService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentCommandService;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.AgentInteractionService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.service.TokenTracker;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class StudentAgentControllerPermissionOwnershipTest {

    @Test
    void passesTheAuthenticatedUserToPermissionReplies() {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.reply(12, 7, "request-71", "allow_once", ""))
                .thenReturn(new PermissionService.PermissionApprovalResult(true, false, null));
        StudentAgentController controller = new StudentAgentController(
                mock(AgentLoopEngine.class), mock(AgentCancellationRegistry.class), mock(DiffService.class),
                mock(AgentCommandService.class), mock(AgentConversationService.class), mock(AgentTaskService.class),
                mock(TokenTracker.class), permissionService, mock(AgentInteractionService.class));

        Result<Map<String, Object>> result = controller.approvePermission(
                12, Map.of("requestId", "request-71", "action", "allow_once", "feedback", ""), authentication(7));

        assertTrue(result.isSuccess());
        verify(permissionService).reply(eq(12), eq(7), eq("request-71"), eq("allow_once"), eq(""));
    }

    /**
     * 会话用量摘要必须按 JWT 主体隔离：controller 只能把认证用户自己的 studentId 交给聚合查询，
     * 不能使用请求体/路径里可能被伪造的身份。
     */
    @Test
    void scopesConversationTokenSummariesToTheAuthenticatedStudent() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        TokenTracker tracker = mock(TokenTracker.class);
        when(tracker.getConversationSummaries(7, 12)).thenReturn(java.util.List.of());
        StudentAgentController controller = new StudentAgentController(
                mock(AgentLoopEngine.class), mock(AgentCancellationRegistry.class), mock(DiffService.class),
                mock(AgentCommandService.class), conversations, mock(AgentTaskService.class),
                tracker, mock(PermissionService.class), mock(AgentInteractionService.class));

        Result<java.util.List<Map<String, Object>>> result =
                controller.conversationTokenSummaries(12, authentication(7));

        assertTrue(result.isSuccess());
        verify(tracker).getConversationSummaries(7, 12);
        // 列表为空时不必读取会话表（没有任何需要补标题的行）。
        verify(conversations, never()).list(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * 有聚合行时必须补齐会话标题；会话可能已被删除，此时 title 为 null 由前端回落占位名。
     */
    @Test
    void fillsConversationTitlesFromOwnedConversations() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        TokenTracker tracker = mock(TokenTracker.class);
        when(tracker.getConversationSummaries(7, 12)).thenReturn(java.util.List.of(
                summary("conv-known"), summary("conv-deleted")));
        com.labex.entity.AgentConversation owned = new com.labex.entity.AgentConversation();
        owned.setConversationId("conv-known");
        owned.setTitle("修复导出忽略规则");
        when(conversations.list(7, 12)).thenReturn(java.util.List.of(owned));
        StudentAgentController controller = new StudentAgentController(
                mock(AgentLoopEngine.class), mock(AgentCancellationRegistry.class), mock(DiffService.class),
                mock(AgentCommandService.class), conversations, mock(AgentTaskService.class),
                tracker, mock(PermissionService.class), mock(AgentInteractionService.class));

        Result<java.util.List<Map<String, Object>>> result =
                controller.conversationTokenSummaries(12, authentication(7));

        assertTrue(result.isSuccess());
        assertTrue("修复导出忽略规则".equals(result.getData().get(0).get("title")));
        // 已删除的会话保留用量行（成本是真实发生的），标题留空由前端回落。
        assertTrue(result.getData().get(1).get("title") == null);
    }

    private static Map<String, Object> summary(String conversationId) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("conversationId", conversationId);
        summary.put("totalTokens", 120);
        return summary;
    }

    private Authentication authentication(int studentId) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(String.valueOf(studentId));
        return authentication;
    }
}
