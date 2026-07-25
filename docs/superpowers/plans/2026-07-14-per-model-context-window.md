# Per-Model Context Window Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hard-coded 28k context-trimming threshold with a context-window value stored per model configuration, defaulting to 1,000,000 tokens in the model configuration UI while allowing the user to change it.

**Architecture:** Add a nullable `context_window_tokens` column and map it through the model-configuration entity, service, controller, and UI. Resolve the active model's context window in the Agent loop; when a value is present, calculate an input budget by reserving the configured output token allowance and apply trimming against that budget. When a legacy configuration has no context window, do not apply an invented code-side limit; rely on provider overflow recovery and prompt the user to configure it on the next edit.

**Tech Stack:** Spring Boot 3, Java 17, MyBatis-Plus, MySQL schema initialization/additive migration, Vue 3, Vite, Node built-in tests, JUnit 5/Mockito.

---

## File Structure

- Modify `backend/src/main/resources/sql/schema.sql` — add nullable `context_window_tokens` to fresh database schema.
- Modify `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java` — add the column safely for already-created databases, following the existing additive migration style.
- Modify `backend/src/main/java/com/labex/entity/AgentModelConfig.java` — persist and serialize `contextWindowTokens`.
- Modify `backend/src/main/java/com/labex/service/AgentModelConfigService.java` — accept and store the value without conflating it with `maxTokens`.
- Modify `backend/src/main/java/com/labex/labexagent/controller/AgentModelConfigController.java` — accept the new request field and return it through the existing sanitized model config response.
- Create `backend/src/main/java/com/labex/labexagent/runtime/ContextBudgetResolver.java` — resolve a configured input budget from an active model config.
- Modify `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java` — remove `CONTEXT_TOKEN_LIMIT`, derive the per-request trimming threshold, and leave legacy blank configurations untrimmed until an actual provider overflow occurs.
- Modify `frontend/src/components/cloud/ModelConfigDialog.vue` — add the editable context-window input and explain its relationship to Max Tokens.
- Modify `frontend/src/views/CloudWorkspace.vue` — default new/custom model forms to 1,000,000, persist the value, and preserve existing configured values during edit.
- Modify `frontend/src/constants/modelPresets.js` — give all presets the 1,000,000 default context window unless a later provider-specific capability source is introduced.
- Modify/add backend tests under `backend/src/test/java/com/labex/...` — prove persistence/request plumbing and budget resolution behavior.
- Modify `frontend/src/components/cloud/ModelConfigDialog.test.mjs` — prove the dialog and workspace wire the new form property and default.

### Task 1: Write the failing backend tests for model configuration and context budgeting

**Files:**
- Create: `backend/src/test/java/com/labex/labexagent/runtime/ContextBudgetResolverTest.java`
- Modify: `backend/src/test/java/com/labex/service/AgentModelConfigSecretTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/controller/AgentModelConfigControllerTest.java`

- [ ] **Step 1: Add a failing resolver test for a configured context window**

```java
@Test
void reservesConfiguredOutputTokensFromConfiguredContextWindow() {
    AgentModelConfig config = new AgentModelConfig();
    config.setContextWindowTokens(1_000_000);
    config.setMaxTokens(32_768);

    assertEquals(967_232, resolver.resolveInputBudget(config).orElseThrow());
}
```

- [ ] **Step 2: Add a failing resolver test for a legacy configuration**

```java
@Test
void leavesLegacyConfigurationWithoutProactiveBudget() {
    AgentModelConfig config = new AgentModelConfig();
    config.setContextWindowTokens(null);
    config.setMaxTokens(32_768);

    assertTrue(resolver.resolveInputBudget(config).isEmpty());
}
```

- [ ] **Step 3: Add a service persistence assertion**

```java
AgentModelConfig config = service.create(
        7, "Default", "openai_compatible", "gpt-test", "sk-model-secret",
        "https://api.example.test", 1024, 1_000_000, null, false);

assertEquals(1_000_000, config.getContextWindowTokens());
```

- [ ] **Step 4: Add controller request-plumbing coverage**

```java
verify(configService).create(
        eq(studentId), eq("Default"), eq("openai_compatible"), eq("gpt-test"),
        anyString(), eq("https://api.example.test"), eq(4096), eq(1_000_000),
        eq(0.7), eq(true));
```

Use Spring Security test authentication with an authenticated numeric principal so the controller obtains the expected student ID.

- [ ] **Step 5: Run the focused tests and confirm they fail before implementation**

Run:

```powershell
cd D:\LabexAgent\backend
mvn -Dtest=ContextBudgetResolverTest,AgentModelConfigSecretTest,AgentModelConfigControllerTest test
```

Expected: compilation/test failure because `contextWindowTokens`, changed service signatures, and `ContextBudgetResolver` do not yet exist.

### Task 2: Add database, entity, API, and service support

**Files:**
- Modify: `backend/src/main/resources/sql/schema.sql`
- Modify: `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- Modify: `backend/src/main/java/com/labex/entity/AgentModelConfig.java`
- Modify: `backend/src/main/java/com/labex/service/AgentModelConfigService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/controller/AgentModelConfigController.java`

- [ ] **Step 1: Add a nullable schema column after `max_tokens`**

```sql
context_window_tokens INT NULL,
```

Do not add a database default. New UI-created configurations provide `1_000_000`; existing rows remain `NULL` so the code can distinguish legacy/unknown provider capacity from an intentional value.

- [ ] **Step 2: Add a safe additive migration**

Follow the existing migrator conventions to issue an idempotent `ALTER TABLE t_agent_model_config ADD COLUMN context_window_tokens INT NULL` only when the column is absent. Do not alter existing model rows and do not write a backfill value.

- [ ] **Step 3: Add the entity mapping and accessors**

```java
@TableField(value = "context_window_tokens")
private Integer contextWindowTokens;

public Integer getContextWindowTokens() { return contextWindowTokens; }
public void setContextWindowTokens(Integer contextWindowTokens) {
    this.contextWindowTokens = contextWindowTokens;
}
```

- [ ] **Step 4: Thread the request value through create and update**

Change the service signatures to place `Integer contextWindowTokens` immediately after `Integer maxTokens`:

```java
public AgentModelConfig create(..., Integer maxTokens,
        Integer contextWindowTokens, Double temperature, boolean makeDefault)
```

On create:

```java
config.setMaxTokens(maxTokens != null ? maxTokens : 32768);
config.setContextWindowTokens(contextWindowTokens);
```

On update, only apply the context-window field when it is present so partial legacy API updates do not erase it:

```java
if (contextWindowTokens != null) {
    config.setContextWindowTokens(contextWindowTokens);
}
```

- [ ] **Step 5: Validate values at the controller boundary**

Add a private validator used by create and update:

```java
private void validateTokenConfiguration(Integer maxTokens, Integer contextWindowTokens) {
    if (maxTokens != null && maxTokens <= 0) {
        throw new IllegalArgumentException("maxTokens must be positive");
    }
    if (contextWindowTokens != null && contextWindowTokens <= 0) {
        throw new IllegalArgumentException("contextWindowTokens must be positive");
    }
    if (maxTokens != null && contextWindowTokens != null && maxTokens >= contextWindowTokens) {
        throw new IllegalArgumentException("contextWindowTokens must be greater than maxTokens");
    }
}
```

Call this before the service on both endpoints. Add `public Integer contextWindowTokens;` to both request classes and pass it to the service.

- [ ] **Step 6: Run the focused backend tests**

Run:

```powershell
cd D:\LabexAgent\backend
mvn -Dtest=AgentModelConfigSecretTest,AgentModelConfigControllerTest test
```

Expected: PASS, including encrypted API-key behavior and the new context-window request flow.

### Task 3: Replace AgentLoopEngine's hard-coded proactive limit

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/runtime/ContextBudgetResolver.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/ContextBudgetResolverTest.java`

- [ ] **Step 1: Implement the resolver with no hard-coded fallback window**

```java
@Component
public class ContextBudgetResolver {
    public OptionalInt resolveInputBudget(AgentModelConfig config) {
        if (config == null || config.getContextWindowTokens() == null
                || config.getContextWindowTokens() <= 0) {
            return OptionalInt.empty();
        }
        int reservedOutput = config.getMaxTokens() == null ? 0 : Math.max(0, config.getMaxTokens());
        int budget = config.getContextWindowTokens() - reservedOutput;
        if (budget <= 0) {
            throw new IllegalArgumentException("Configured context window must exceed max tokens");
        }
        return OptionalInt.of(budget);
    }
}
```

The resolver deliberately returns empty for legacy rows. It does not substitute 28k, 128k, or any other code-side window.

- [ ] **Step 2: Resolve the active model configuration before context trimming**

At the existing `AgentLoopEngine` call site, retain the resolved `AgentModelConfig` for the request and call the resolver once. Pass the resulting optional input budget to all proactive trim/compact helpers that currently use `CONTEXT_TOKEN_LIMIT`.

- [ ] **Step 3: Replace the fixed trim guard**

Replace:

```java
if (totalTokens <= CONTEXT_TOKEN_LIMIT) return;
```

with:

```java
if (inputBudget.isEmpty() || totalTokens <= inputBudget.getAsInt()) {
    return;
}
```

Update the log line to include the active configuration/model name and resolved budget. Remove `CONTEXT_TOKEN_LIMIT` completely.

- [ ] **Step 4: Preserve provider-overflow recovery for legacy settings**

Do not change `isContextOverflowError(...)` or its retry/compaction path. This ensures an old row with a null context-window value remains usable and is compacted only when the actual provider rejects the request.

- [ ] **Step 5: Run the resolver and loop regression tests**

Run:

```powershell
cd D:\LabexAgent\backend
mvn -Dtest=ContextBudgetResolverTest,AgentLoopEngineCancellationTest,AgentLoopEngineStartupFailureTest test
```

Expected: PASS. The resolver proves the 1M/32,768 configuration yields a 967,232-token input budget and legacy configurations have no proactive hard-coded threshold.

### Task 4: Add the model-configuration UI field and default it to 1M

**Files:**
- Modify: `frontend/src/components/cloud/ModelConfigDialog.vue`
- Modify: `frontend/src/views/CloudWorkspace.vue`
- Modify: `frontend/src/constants/modelPresets.js`
- Modify: `frontend/src/components/cloud/ModelConfigDialog.test.mjs`

- [ ] **Step 1: Add a separate UI input next to Max Tokens**

```vue
<div class="mc-field mc-field-half">
  <label>上下文窗口 Tokens</label>
  <input v-model.number="state.mcForm.contextWindowTokens" class="mc-input" type="number" min="1" step="1" placeholder="1000000" />
  <div class="mc-hint">输入与输出合计上限；留空时仅在供应商返回超限后压缩</div>
</div>
```

Keep `Max Tokens` labeled as the output limit, and do not rename its API property.

- [ ] **Step 2: Default all new configuration paths to 1,000,000**

Use the same value in `emptyModelConfigForm`, `mcCustomTemplate`, and each preset:

```js
contextWindowTokens: 1000000
```

When editing an existing record, preserve the returned value rather than coercing it:

```js
contextWindowTokens: cfg.contextWindowTokens ?? null
```

This keeps legacy rows blank so users can see that their provider capacity was previously unknown.

- [ ] **Step 3: Send the new property in create/update payloads**

```js
contextWindowTokens: f.contextWindowTokens || null,
```

Before saving, reject non-positive values and values that are not larger than `maxTokens`:

```js
if (f.contextWindowTokens != null && f.contextWindowTokens <= f.maxTokens) {
  ElMessage.warning('上下文窗口必须大于单次回复最大 Tokens')
  return
}
```

- [ ] **Step 4: Extend the UI source test**

```js
assert.match(dialog, /state\.mcForm\.contextWindowTokens/)
assert.match(workspace, /contextWindowTokens:\s*1000000/)
assert.match(workspace, /contextWindowTokens:\s*f\.contextWindowTokens\s*\|\|\s*null/)
```

- [ ] **Step 5: Run the frontend targeted test and build**

Run:

```powershell
cd D:\LabexAgent\frontend
node --test src/components/cloud/ModelConfigDialog.test.mjs
npm run build
```

Expected: the source test and Vite production build both pass.

### Task 5: Verify the complete behavior and document the runtime semantics

**Files:**
- Modify: `README.md` only if it already documents model configuration fields; otherwise do not add unrelated documentation.

- [ ] **Step 1: Confirm the removed hard-code**

Run:

```powershell
Select-String -Path D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\AgentLoopEngine.java -Pattern 'CONTEXT_TOKEN_LIMIT'
```

Expected: no output.

- [ ] **Step 2: Run the broadest safe backend verification**

Run:

```powershell
cd D:\LabexAgent\backend
mvn test
```

Expected: PASS. If unrelated existing-worktree failures appear, report their exact failing class and avoid changing unrelated code.

- [ ] **Step 3: Inspect the final diff without staging or committing**

Run:

```powershell
cd D:\LabexAgent
git diff -- backend/src/main/resources/sql/schema.sql backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java backend/src/main/java/com/labex/entity/AgentModelConfig.java backend/src/main/java/com/labex/service/AgentModelConfigService.java backend/src/main/java/com/labex/labexagent/controller/AgentModelConfigController.java backend/src/main/java/com/labex/labexagent/runtime/ContextBudgetResolver.java backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java frontend/src/components/cloud/ModelConfigDialog.vue frontend/src/views/CloudWorkspace.vue frontend/src/constants/modelPresets.js
```

Expected: only the configurable context-window feature and its tests are included. Do not stage, commit, or modify unrelated pre-existing changes.
