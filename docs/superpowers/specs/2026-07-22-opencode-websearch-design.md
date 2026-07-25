# OpenCode-Compatible Web Search Design

## Goal

Replace the Agent `web_search` implementation with an OpenCode-compatible, MCP-backed discovery tool. The new tool will call Exa and Parallel hosted search providers, preserve provider-produced LLM context, and leave URL retrieval exclusively to the existing `web_fetch` tool.

## Scope

- Align the tool's public parameters and provider behavior with OpenCode's `websearch` tool.
- Implement Exa and Parallel MCP JSON-RPC search providers.
- Select providers deterministically per Agent session/task, with explicit configuration overrides.
- Add a free, public-search fallback using DuckDuckGo and Bing discovery pages when Exa has a recoverable availability failure.
- Preserve the existing Agent tool registry, permission framework, output streaming, and outbound network security model.
- Retire the Agent's dependency on the RAG `WebSearchService` for `web_search`.
- Retain the RAG service unless another caller uses it; this change does not remove it globally.

Out of scope:

- Changing `web_fetch` behavior.
- Adding a frontend configuration screen.
- Replacing all RAG search behavior.
- Supporting arbitrary third-party MCP search endpoints in the first release.
- Calling Tavily as an Agent web-search provider or fallback.
- Automatically selecting Parallel unless an operator explicitly enables it.

## Current-State Gap

The current Agent tool delegates to `rag.service.WebSearchService`, which is a multi-purpose search-and-crawl service:

- It uses Tavily when configured, then scrapes DuckDuckGo, Bing, and Bing News.
- It expands queries, extracts keyword phrases, ranks and deduplicates results locally.
- It optionally fetches page bodies during search.
- It adds local, heuristic labels such as `exact_entity_match`, `evidence_level`, and `source_quality`.

OpenCode's web search tool instead:

- Calls Exa or Parallel over hosted MCP JSON-RPC.
- Exposes provider-native search options and uses provider-produced LLM context as output.
- Keeps discovery (`websearch`) separate from URL retrieval (`webfetch`).
- Chooses a stable provider from the session ID, while allowing environment/feature overrides.
- Records the provider and request parameters as tool-call metadata after permission approval.

The revised Agent tool must adopt this latter model. The Agent should use `web_search` to find sources and `web_fetch` to inspect a selected URL.

## Architecture

### Tool Layer

`WebSearchTool` remains an `AgentTool` and is limited to:

1. Validating and normalizing request arguments.
2. Selecting an enabled search provider for the current Agent context.
3. Requesting permission through the existing permission workflow.
4. Delegating search to the selected provider.
5. Returning the provider's bounded text output and non-sensitive metadata.

The canonical tool name remains `web_search`; the existing `websearch` alias remains supported. Tool descriptions must explicitly state the discovery/retrieval split and include the current year for time-sensitive queries.

### Provider Layer

Create a focused provider abstraction under the Agent core, separate from the RAG package:

```text
WebSearchProvider
  - id()
  - isEnabled()
  - search(WebSearchRequest, WebSearchExecutionContext)

ExaMcpWebSearchProvider
ParallelMcpWebSearchProvider
WebSearchProviderSelector
McpWebSearchClient
```

Responsibilities:

- `WebSearchProvider`: defines the domain contract and provider identity.
- `ExaMcpWebSearchProvider`: maps Agent parameters to Exa's `web_search_exa` MCP call.
- `ParallelMcpWebSearchProvider`: maps an Agent query to Parallel's `web_search` MCP call. It is available only when an operator explicitly enables it.
- `PublicWebSearchFallbackProvider`: queries DuckDuckGo and Bing public discovery pages, returns only title, URL, and search snippet results, and never calls Tavily or fetches result pages.
- `McpWebSearchClient`: sends a validated `tools/call` JSON-RPC request, accepts JSON or Server-Sent Events response bodies, extracts the first text content result, applies timeout/error mapping, and never logs credentials.
- `WebSearchProviderSelector`: resolves explicit provider configuration first; otherwise selects Exa as the default primary provider. It may choose Parallel only when explicitly enabled, and returns a safe, actionable error if no primary provider is enabled.
- `PublicWebSearchFallbackProvider` is invoked only after a recoverable Exa availability failure; it is never selected as the primary provider and does not receive user-controlled URLs.

The provider layer owns provider protocol details. `WebSearchTool` must not contain JSON-RPC construction, response parsing, API-key handling, or network code.

### Request Contract

The tool accepts OpenCode-compatible parameters:

```text
query: required string
num_results: optional integer, default 8, range 1..20
livecrawl: optional enum: fallback | preferred, default fallback
type: optional enum: auto | fast | deep, default auto
context_max_characters: optional integer, range 1..50000
```

The tool also accepts OpenCode's camel-case names (`numResults`, `contextMaxCharacters`) as input aliases. During the transition, the existing `max_results` alias is accepted and mapped to `num_results`; it is not advertised in the new tool definition. `fetch_pages` is removed because the search tool no longer fetches result URLs.

Exa receives:

```json
{
  "query": "...",
  "type": "auto | fast | deep",
  "numResults": 8,
  "livecrawl": "fallback | preferred",
  "contextMaxCharacters": 10000
}
```

Parallel receives:

```json
{
  "objective": "...",
  "search_queries": ["..."],
  "session_id": "...",
  "model_name": "..."
}
```

Parallel does not expose every Exa option; unsupported parameters are accepted for contract parity but not sent to Parallel. The output metadata identifies the selected provider so behavior remains observable.

### MCP Transport

`McpWebSearchClient` sends:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "provider tool name",
    "arguments": { "...": "..." }
  }
}
```

It uses `Accept: application/json, text/event-stream`, a 25-second timeout, and a bounded response size. It supports either a complete JSON response or a `data:` Server-Sent Event containing the JSON-RPC result. It extracts the first textual item from `result.content`.

All fixed provider endpoint URLs are validated through the existing `OutboundUrlPolicy` before opening a connection. No user-controlled URL is passed to this transport. Requests must use the project's existing HTTP approach where practical, with redirects disabled or each redirect revalidated by `OutboundUrlPolicy`.

### Provider Selection and Configuration

Add a typed configuration bean under `labex-agent.web-search` with:

```text
provider: auto | exa | parallel
exa-enabled: boolean
parallel-enabled: boolean
public-fallback-enabled: boolean
exa-api-key: environment-backed secret, optional
parallel-api-key: environment-backed secret, optional
request-timeout-seconds: 25
max-response-bytes: 1048576
```

Suggested environment variables:

```text
LABEX_AGENT_WEB_SEARCH_PROVIDER
enable flags: LABEX_AGENT_WEB_SEARCH_EXA_ENABLED, LABEX_AGENT_WEB_SEARCH_PARALLEL_ENABLED,
              LABEX_AGENT_WEB_SEARCH_PUBLIC_FALLBACK_ENABLED
EXA_API_KEY
PARALLEL_API_KEY
```

Configuration precedence:

1. Explicit `provider=exa` selects Exa and fails safely if Exa is disabled.
2. Explicit `provider=parallel` selects Parallel and fails safely unless an operator has enabled Parallel.
3. With `provider=auto`, Exa is always primary when enabled. Parallel is considered only when an operator explicitly enabled it and Exa is disabled or explicitly unavailable; the system does not randomly send ordinary searches to Parallel.
4. `PublicWebSearchFallbackProvider` is attempted only when Exa fails with a recoverable availability condition: timeout, connection failure, HTTP 429, or upstream 5xx. It is not used for invalid tool arguments, permission denials, rejected endpoints, explicit-provider configuration errors, or malformed local configuration.
5. The fallback runs only when `public-fallback-enabled=true`, never uses Tavily, never reads `TAVILY_API_KEY`, and only performs public result discovery. It queries DuckDuckGo and Bing with bounded requests, deduplicates result URLs, and returns title, URL, snippet, and `provider: public_fallback`.
6. With no enabled primary provider or after all eligible providers/fallbacks fail, return a configuration-safe, actionable error; never silently call a paid provider.

Exa uses its hosted MCP endpoint without a key when no key is configured; when present, the key is supplied only to Exa's endpoint query parameter as required by its MCP service. Parallel adds `Authorization: Bearer <key>` only when its key exists. Secrets are never included in tool output, event metadata, or logs.

### Permissions and Metadata

The tool's existing permission name remains `web_search` and its alias remains `websearch`. Permission matching uses the query as the pattern, preserving the current project authorization model.

The permission/tool-call metadata contains only:

- query;
- normalized result count;
- live-crawl mode;
- search type;
- requested context limit; and
- selected provider ID.

This mirrors OpenCode's observability without disclosing provider credentials or raw upstream headers.

## Output and Errors

A successful MCP result returns the text supplied by the selected provider. A successful public fallback result returns bounded, deduplicated title/URL/snippet entries labelled `provider: public_fallback`. The title/output prefix identifies the provider and query, but the tool does not locally re-rank results, fetch result pages, derive source-quality claims, or label results as verified.

When a provider returns no textual result, return `No search results found. Please try a different query.`

Failures are mapped into concise, actionable messages:

- no enabled provider;
- selected provider unavailable or misconfigured;
- timeout;
- blocked outbound endpoint;
- malformed upstream MCP response; and
- upstream non-success response.

Detailed HTTP bodies, stack traces, request headers, and credentials remain server-side only and are not sent to the model or frontend.

## Migration

1. Add the new Agent web-search provider modules and configuration.
2. Switch `WebSearchTool` from `WebSearchService` to the provider selector.
3. Retain the `websearch` alias and backward-compatible `max_results` input alias.
4. Stop advertising or handling `fetch_pages`.
5. Do not delete `WebSearchService` in this change; first locate and preserve any RAG callers. A later, separately reviewed cleanup can remove unused scraping code.
6. Update `.env.example` and README configuration guidance with placeholder values only.

## Testing and Verification

Add focused backend tests for:

- request parameter defaults, bounds, enum validation, and legacy aliases;
- explicit provider selection, disabled-provider behavior, and confirmation that Parallel is never selected in auto mode unless explicitly enabled;
- recoverable Exa failures invoke the free public fallback, while invalid arguments, permission denial, explicit-provider configuration errors, and endpoint-policy rejection do not;
- public fallback never reads Tavily configuration, never calls Tavily, never fetches result URLs, and returns only title/URL/snippet fields;
- Exa and Parallel JSON-RPC request payloads;
- optional API-key header/endpoint behavior without exposing key values;
- direct JSON and SSE response parsing;
- blank provider output, malformed output, non-2xx, timeout, and response-size errors;
- outbound URL-policy rejection before a connection attempt; and
- `WebSearchTool` output without page-body crawling or heuristic result annotations.

Run the smallest focused Maven tests first, then run `mvn test` from `backend/` if the local suite is practical. Compile/package checks should use the existing Maven configuration. No new runtime dependency is required.

## Trade-offs

This intentionally removes the Agent's dependency on the old all-in-one RAG search-and-crawl service. The default path is provider-backed Exa discovery, which better matches OpenCode and keeps discovery separate from retrieval. A bounded DuckDuckGo/Bing fallback preserves basic no-key search availability during recoverable Exa outages without invoking Tavily or an implicitly enabled paid provider.

Public search markup and anti-bot policies can change, so the fallback is intentionally narrow and must not be treated as provider-quality LLM context. It returns only search-result metadata; `web_fetch` remains responsible for reading a selected public URL. Operators can explicitly enable and force Parallel when they accept its account, cost, and availability policy.
