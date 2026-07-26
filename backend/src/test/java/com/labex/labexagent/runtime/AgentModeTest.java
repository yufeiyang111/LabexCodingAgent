package com.labex.labexagent.runtime;

import com.labex.labexagent.permission.DefaultPermissionRuleset;
import com.labex.labexagent.permission.PermissionAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentModeTest {

    @Test
    void normalizesSupportedModesAndUsesAgentAsTheBlankDefault() {
        assertEquals("agent", AgentMode.normalize(null));
        assertEquals("agent", AgentMode.normalize("  "));
        assertEquals("plan", AgentMode.normalize(" PLAN "));
        assertEquals("explore", AgentMode.normalize("Explore"));
        assertEquals("build", AgentMode.normalize("build"));
    }

    @Test
    void rejectsUnknownModesAndPermissionRulesDefaultToDeny() {
        assertThrows(IllegalArgumentException.class, () -> AgentMode.normalize("anything"));
        var rules = DefaultPermissionRuleset.getRulesForAgent("anything");
        assertEquals(1, rules.size());
        assertEquals(PermissionAction.DENY, rules.get(0).getAction());
        assertEquals("*", rules.get(0).getPermission());
    }
}