# User-Owned Prompt Optimization Design

## Goal

Ensure prompt optimization uses only the current user's saved LLM configuration and never falls back to platform MiniMax or Ollama settings.

## Request Flow

The workspace sends the currently selected `modelConfigId` with the prompt and active file path. The backend resolves that configuration only within the authenticated user's records; if no ID is sent, it resolves the user's default configuration.

The optimization service rejects missing, disabled, or keyless configurations before it creates an LLM request. For valid configurations it uses the existing `LlmProviderFactory` and `LlmProvider` OpenAI-compatible path, with no tools and no streaming.

## Error Behavior

The API returns a clear message asking the user to configure an enabled model when no valid user-owned configuration is available. It must never inspect `MINIMAX_*` values as a fallback for prompt optimization.
