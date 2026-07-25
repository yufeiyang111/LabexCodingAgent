package com.labex.labexagent.runtime;

import java.util.Objects;

@FunctionalInterface
public interface CancellationToken {
    boolean isCancellationRequested();

    default Registration onCancellation(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        if (isCancellationRequested()) {
            listener.run();
        }
        return Registration.noop();
    }

    static CancellationToken none() {
        return () -> false;
    }

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();

        static Registration noop() {
            return () -> { };
        }
    }
}
