import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('ModelSelectorPopover does not hardcode fake model presets and handles empty state properly', async () => {
  const popover = await readFile(new URL('./ModelSelectorPopover.vue', import.meta.url), 'utf8')
  const workspace = await readFile(new URL('../../../views/CloudWorkspace.vue', import.meta.url), 'utf8')

  // 1. 验证彻底清除了写死的默认假模型预设
  assert.doesNotMatch(popover, /defaultModelPresets\s*=/, 'Must not have hardcoded defaultModelPresets')
  assert.doesNotMatch(popover, /'deepseek-v4-flash'/, 'Must not hardcode deepseek-v4-flash as default preset')

  // 2. 验证未配置模型时的空状态提示与引导
  assert.match(popover, /hasModels/, 'Must compute whether models are configured')
  assert.match(popover, /未配置模型/, 'Must display "未配置模型" when no models exist')
  assert.match(popover, /model-empty-state/, 'Must render empty state container when unconfigured')
  assert.match(popover, /立即配置模型/, 'Must provide direct call-to-action button to configure models')

  // 3. 验证 CloudWorkspace 不再将未配置模型回退为 MiniMax
  assert.doesNotMatch(workspace, /return 'MiniMax'/, 'CloudWorkspace must not fallback to hardcoded MiniMax')
  assert.match(workspace, /尚未配置 AI 模型，请先配置模型与 API Key/, 'CloudWorkspace must intercept and guide user when unconfigured')
})
