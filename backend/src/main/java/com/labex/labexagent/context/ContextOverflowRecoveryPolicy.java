package com.labex.labexagent.context;

/** 单次 Agent run 的有限 context overflow 策略状态。 */
public final class ContextOverflowRecoveryPolicy {
    private int attempts;

    public Action next(boolean toolSchemaCanBeReduced) {
        if (attempts == 0) {
            attempts++;
            return Action.COMPACT;
        }
        if (attempts == 1 && toolSchemaCanBeReduced) {
            attempts++;
            return Action.REDUCE_TOOL_SCHEMA;
        }
        return Action.STOP;
    }

    public int attempts() {
        return attempts;
    }

    public enum Action {
        COMPACT,
        REDUCE_TOOL_SCHEMA,
        STOP
    }
}