package com.labex.labexagent.execution;

import com.labex.labexagent.runtime.CancellationToken;
import java.util.function.Consumer;

public interface ProcessExecutor {
    default ProcessExecutionResult execute(ProcessExecutionRequest request) {
        return execute(request, CancellationToken.none(), chunk -> { });
    }

    default ProcessExecutionResult execute(ProcessExecutionRequest request, CancellationToken cancellationToken) {
        return execute(request, cancellationToken, chunk -> { });
    }

    ProcessExecutionResult execute(
            ProcessExecutionRequest request,
            CancellationToken cancellationToken,
            Consumer<String> outputListener);

    default ProcessExecutionResult execute(
            ProcessExecutionRequest request,
            CancellationToken cancellationToken,
            Consumer<String> outputListener,
            ProcessExecutionObserver observer) {
        return execute(request, cancellationToken, outputListener);
    }
}
