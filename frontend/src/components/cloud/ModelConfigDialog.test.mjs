import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('ModelConfigDialog receives model configuration state and actions from CloudWorkspace', async () => {
  const dialog = await readFile(new URL('./ModelConfigDialog.vue', import.meta.url), 'utf8')
  const workspace = await readFile(new URL('../../views/CloudWorkspace.vue', import.meta.url), 'utf8')

  assert.match(dialog, /state\.mcForm\.configName/)
  assert.match(dialog, /actions\.saveConfig/)
  assert.match(dialog, /actions\.testConfig\(cfg\)/)
  assert.match(dialog, /v-model\.number="state\.mcForm\.contextWindowTokens"/)
  assert.match(dialog, /placeholder="\u8bf7\u586b\u5199\u771f\u5b9e\u503c"/)
  assert.match(dialog, new RegExp('\\u4e0a\\u4e0b\\u6587\\u7a97\\u53e3'))
  assert.match(dialog, new RegExp('\\u603b\\u8f93\\u5165'))
  assert.match(dialog, new RegExp('\\u8f93\\u51fa Token \u5bb9\u91cf'))
  assert.match(dialog, /Max Tokens/)
  assert.match(dialog, new RegExp('\\u8f93\\u51fa\\u7684\\u6700\\u5927 Token'))
  assert.match(workspace, /contextWindowTokens: null/)
  assert.match(workspace, /contextWindowTokens: cfg\.contextWindowTokens \?\? null/)
  assert.match(workspace, /contextWindowTokens: tpl\.contextWindowTokens \?\? null/)
  assert.match(workspace, /contextWindowTokens: f\.contextWindowTokens/)
  assert.match(workspace, /contextWindowTokens.*Max Tokens/)
  assert.match(workspace, /!Number\.isFinite\(f\.contextWindowTokens\) \|\| !Number\.isInteger\(f\.contextWindowTokens\) \|\| f\.contextWindowTokens <= 0/)
  assert.match(workspace, /f\.contextWindowTokens <= f\.maxTokens/)
  assert.match(workspace, /!Number\.isInteger\(f\.maxTokens\)/)
  assert.match(workspace, /!Number\.isInteger\(f\.contextWindowTokens\)/)
  assert.match(workspace, /const ModelConfigDialog = defineAsyncComponent\(\(\) => import\('@\/components\/cloud\/ModelConfigDialog\.vue'\)\)/)
  assert.match(workspace, /const modelConfigDialogState = reactive\(/)
  assert.match(workspace, /<ModelConfigDialog :state="modelConfigDialogState" :actions="modelConfigDialogActions"/)
})


test('ModelConfigDialog exposes the opt-in prompt cache key setting and persists it', async () => {
  const dialog = await readFile(new URL('./ModelConfigDialog.vue', import.meta.url), 'utf8')
  const workspace = await readFile(new URL('../../views/CloudWorkspace.vue', import.meta.url), 'utf8')

  assert.match(dialog, /state\.mcForm\.promptCacheKeyEnabled/)
  assert.match(workspace, /promptCacheKeyEnabled:\s*cfg\.promptCacheKeyEnabled\s*===\s*1/)
  assert.match(workspace, /promptCacheKeyEnabled:\s*f\.promptCacheKeyEnabled/)
})

test('ModelConfigDialog exposes and persists the automatic compaction threshold', async () => {
  const dialog = await readFile(new URL('./ModelConfigDialog.vue', import.meta.url), 'utf8')
  const workspace = await readFile(new URL('../../views/CloudWorkspace.vue', import.meta.url), 'utf8')

  assert.match(dialog, /state\.mcForm\.compactionThresholdPercent/)
  assert.match(dialog, /calculateContextWindowPolicy/)
  assert.match(dialog, /effectivePolicy/)
  assert.match(dialog, /有效自动压缩线/)
  assert.doesNotMatch(dialog, /将在 180K tokens/)
  assert.match(dialog, /min="70" max="99"/)
  assert.match(workspace, /compactionThresholdPercent: 90/)
  assert.match(workspace, /compactionThresholdPercent: cfg\.compactionThresholdPercent \?\? 90/)
  assert.match(workspace, /compactionThresholdPercent: f\.compactionThresholdPercent/)
  assert.match(workspace, /Number\.isFinite\(f\.compactionThresholdPercent\)/)
})
