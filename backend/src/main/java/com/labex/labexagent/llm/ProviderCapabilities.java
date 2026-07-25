package com.labex.labexagent.llm;

/**
 * Normalized feature contract exposed by a provider adapter. A caller can negotiate the
 * features it needs before sending a request instead of relying on provider-specific fields.
 */
public record ProviderCapabilities(
        boolean streaming,
        boolean toolCalling,
        boolean parallelToolCalls,
        boolean reasoning,
        boolean usage) {

    public static final ProviderCapabilities NONE = new ProviderCapabilities(false, false, false, false, false);
    public static final ProviderCapabilities OPENAI_COMPATIBLE = new ProviderCapabilities(true, true, true, true, true);

    public ProviderCapabilities negotiate(Requirements requirements) {
        if (requirements == null) {
            return this;
        }
        return new ProviderCapabilities(
                streaming && requirements.streaming(),
                toolCalling && requirements.toolCalling(),
                parallelToolCalls && requirements.parallelToolCalls(),
                reasoning && requirements.reasoning(),
                usage && requirements.usage());
    }

    public boolean satisfies(Requirements requirements) {
        if (requirements == null) {
            return true;
        }
        return (!requirements.streaming() || streaming)
                && (!requirements.toolCalling() || toolCalling)
                && (!requirements.parallelToolCalls() || parallelToolCalls)
                && (!requirements.reasoning() || reasoning)
                && (!requirements.usage() || usage);
    }

    public record Requirements(
            boolean streaming,
            boolean toolCalling,
            boolean parallelToolCalls,
            boolean reasoning,
            boolean usage) {
    }
}
