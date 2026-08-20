package com.labex.labexagent.run;

/** Conversation Message/Part 图的持久化投影版本标识。 */
public final class AgentConversationMessageGraphVersion {
    public static final String VALUE = "conversation_graph_v1";

    private AgentConversationMessageGraphVersion() {
    }

    public static boolean matches(String historyProjectionVersion) {
        return VALUE.equals(historyProjectionVersion);
    }
}
