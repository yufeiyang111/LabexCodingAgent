package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentLoopEngineFinalReplyPolicyTest {

    @Test
    void acceptsAnExplicitlyRequestedShortReply() {
        assertFalse(AgentLoopEngine.shouldRejectFinalReply("只回复：测试成功", "测试成功"));
        assertFalse(AgentLoopEngine.shouldRejectFinalReply("Reply only: pong", "pong"));
    }

    @Test
    void stillRejectsAnUnrequestedEmptyOrLowSubstanceReply() {
        assertTrue(AgentLoopEngine.shouldRejectFinalReply("修复登录问题并验证", "好的"));
        assertTrue(AgentLoopEngine.shouldRejectFinalReply("Fix the login problem and verify it", "OK"));
    }
}
