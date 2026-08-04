package com.labex.labexagent.execution;

/** 在允许执行继续前接收进程身份。 */
@FunctionalInterface
public interface ProcessExecutionObserver {
    void onStarted(ProcessExecutionIdentity identity);

    static ProcessExecutionObserver none() {
        return identity -> { };
    }
}
