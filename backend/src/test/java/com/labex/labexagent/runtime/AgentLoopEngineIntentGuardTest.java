package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 对齐 opencode 意图规则（kimi/gpt：可问可做一律按任务）与 max-steps 哨兵注入方式。 */
class AgentLoopEngineIntentGuardTest {

    @Test
    void treatsAmbiguousRequestsAsEngineeringTasks() {
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("修复登录页面的样式问题")).isTrue();
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("帮我实现一个用户注册功能")).isTrue();
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("fix the login bug in auth.py")).isTrue();
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("add error handling to the api")).isTrue();
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("把配置改一下")).isTrue();
    }

    @Test
    void exemptsExplicitExplanationRequests() {
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("解释一下这段代码是什么意思")).isFalse();
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("what does this function do")).isFalse();
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("为什么编译报错")).isFalse();
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("how do I fix this")).isFalse();
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("can you explain the difference")).isFalse();
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("你好")).isFalse();
        assertThat(AgentLoopEngine.isEngineeringTaskRequest(null)).isFalse();
        assertThat(AgentLoopEngine.isEngineeringTaskRequest("")).isFalse();
    }

    @Test
    void leavesNoTransientMaxStepsSentinelInTheRequestProjection() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "do the work"));
        messages.add(Map.of("role", "assistant", "content", "ok"));
        List<Map<String, Object>> projected = AgentLoopEngine.withMaxStepsSentinel(messages);

        assertThat(projected).containsExactlyElementsOf(messages);
        assertThat(messages).hasSize(2);
    }
}
