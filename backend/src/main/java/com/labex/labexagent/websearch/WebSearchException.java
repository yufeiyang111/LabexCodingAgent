package com.labex.labexagent.websearch;

public class WebSearchException extends Exception {
    private final boolean recoverable;

    public WebSearchException(String message, boolean recoverable) {
        super(message);
        this.recoverable = recoverable;
    }

    public WebSearchException(String message, Throwable cause, boolean recoverable) {
        super(message, cause);
        this.recoverable = recoverable;
    }

    public boolean isRecoverable() {
        return recoverable;
    }
}
