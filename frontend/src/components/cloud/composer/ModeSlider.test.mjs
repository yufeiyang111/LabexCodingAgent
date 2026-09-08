import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('ModeSlider robustly handles subagent mode mapping, avoids displaced pills and supports dynamic resize', async () => {
  const modeSlider = await readFile(new URL('./ModeSlider.vue', import.meta.url), 'utf8')
  const subagentTab = await readFile(new URL('../chat/SubagentSessionTab.vue', import.meta.url), 'utf8')

  // 1. 验证 ModeSlider 支持别名映射，兼容 subagent / scout / general 等值
  assert.match(modeSlider, /MODE_ALIAS_MAP/, 'Must define MODE_ALIAS_MAP for safe alias resolution')
  assert.match(modeSlider, /subagent:\s*'explore'/, 'Must map subagent to explore mode')
  assert.match(modeSlider, /scout:\s*'explore'/, 'Must map scout to explore mode')

  // 2. 验证当模式不匹配时，滑块背景隐藏，绝不显示 48px 错位黑块
  assert.match(modeSlider, /opacity:\s*0/, 'Must hide pill background with opacity 0 when inactive')
  assert.match(modeSlider, /pointerEvents:\s*'none'/, 'Must disable pointer events when pill is hidden')

  // 3. 验证去除写死的 -2 偏移，采用精确的 relative offset 算法
  assert.doesNotMatch(modeSlider, /target\.offsetLeft\s*-\s*2/, 'Must not use fragile hardcoded offset - 2')
  assert.match(modeSlider, /getBoundingClientRect/, 'Must use bounding client rect for sub-pixel accuracy')

  // 4. 验证挂载 ResizeObserver 确保在 Tab 切换或尺寸变化时自适应
  assert.match(modeSlider, /ResizeObserver/, 'Must observe container resize to re-align pill')

  // 5. 验证 SubagentSessionTab 动态绑定子代理实际模式，杜绝模式错位
  assert.match(subagentTab, /effectiveSubagentMode/, 'Must compute effectiveSubagentMode based on agentType')
  assert.match(subagentTab, /type === 'general' \? 'build' : 'explore'/, 'Must map general to build and scout/explore to explore')
  assert.doesNotMatch(subagentTab, /agentMode\.value\s*=\s*'subagent'/, 'Must not reset agentMode to raw unrecognized subagent string')
})
