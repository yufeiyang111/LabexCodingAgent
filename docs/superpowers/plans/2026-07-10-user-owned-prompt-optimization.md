# User-Owned Prompt Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route prompt optimization through the current user's configured LLM and reject platform fallbacks.

**Architecture:** The workspace submits `modelConfigId` with optimization requests. `AgentCommandService` resolves and validates the owner-scoped model configuration, then calls the existing `LlmProvider` non-streaming API. It does not depend on `RagConfig`, `MiniMaxChat`, or `OllamaChat` for this path.

**Tech Stack:** Vue 3, Spring Boot 3, JUnit 5, Mockito, Node.js assertions.

## Global Constraints

- Keep model credentials server-side and never return API keys to the browser.
- A missing, disabled, or keyless user configuration must fail before any LLM provider invocation.
- Prompt optimization must not fall back to `MINIMAX_*` or Ollama settings.
- Do not add dependencies or modify unrelated terminal and theme changes.

---

### Task 1: Enforce User-Owned Backend Model Resolution

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/dto/PromptOptimizationRequest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/controller/StudentAgentController.java`
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentCommandService.java`
- Test: `backend/src/test/java/com/labex/labexagent/service/AgentCommandServicePromptOptimizationTest.java`

**Interfaces:**
- Consumes: `PromptOptimizationRequest#getMessage()`, `getActivePath()`, and `getModelConfigId()`.
- Produces: `AgentCommandService#optimizePrompt(Integer, Integer, PromptOptimizationRequest)` returning `Map<String, String>`.

- [x] **Step 1: Write the failing tests**

```java
assertThat(service.optimizePrompt(7, 3, request).get("optimizedPrompt"))
        .isEqualTo("structured prompt");
assertThrows(IllegalArgumentException.class,
        () -> service.optimizePrompt(7, 3, new PromptOptimizationRequest("fix it", "", null)));
```

- [x] **Step 2: Verify the tests fail**

Run: `mvn -Dtest=AgentCommandServicePromptOptimizationTest test`

Expected: compilation failure because the typed request and user-configured provider path do not exist.

- [x] **Step 3: Add the typed request and minimal provider path**

```java
AgentModelConfig config = modelConfigService.resolveForStudent(studentId, request.getModelConfigId());
if (config == null || !Integer.valueOf(1).equals(config.getStatus()) || config.getApiKey() == null || config.getApiKey().isBlank()) {
    throw new IllegalArgumentException("请先配置一个启用的模型服务后再优化提示词");
}
LlmProvider provider = providerFactory.resolveProvider(config);
Map<String, Object> response = provider.chatWithTools(system, messages, List.of(), providerFactory.buildConfig(config));
```

- [x] **Step 4: Verify the tests pass**

Run: `mvn -Dtest=AgentCommandServicePromptOptimizationTest test`

Expected: `BUILD SUCCESS`.

### Task 2: Send the Active Workspace Configuration

**Files:**
- Modify: `frontend/src/views/CloudWorkspace.vue`
- Test: `frontend/src/views/promptOptimizationByok.test.mjs`

**Interfaces:**
- Consumes: `selectedModelConfigId` from the existing workspace model picker.
- Produces: optimization request JSON with `message`, `activePath`, and `modelConfigId`.

- [x] **Step 1: Write the failing source-level regression check**

```js
assert.match(source, /optimizePrompt\(projectId\.value,\s*\{[\s\S]*modelConfigId:\s*selectedModelConfigId\.value\s*\|\|\s*null/)
```

- [x] **Step 2: Verify the check fails**

Run: `node src/views/promptOptimizationByok.test.mjs`

Expected: assertion failure because the request currently only contains `message`.

- [x] **Step 3: Send the selected model configuration**

```js
await projectApi.optimizePrompt(projectId.value, {
  message: originalPrompt,
  activePath: activePath.value || '',
  modelConfigId: selectedModelConfigId.value || null
})
```

- [x] **Step 4: Verify frontend regression check and build**

Run: `node src/views/promptOptimizationByok.test.mjs`

Expected: exit code 0.

Run: `npm run build`

Expected: Vite build completes successfully.
