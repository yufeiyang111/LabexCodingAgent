import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('SubagentSessionTab and CloudWorkspace model inheritance contract', async () => {
  const subagentTabSource = await readFile(
    new URL('./SubagentSessionTab.vue', import.meta.url),
    'utf8'
  )
  const cloudWorkspaceSource = await readFile(
    new URL('../../../views/CloudWorkspace.vue', import.meta.url),
    'utf8'
  )
  const popoverSource = await readFile(
    new URL('../composer/ModelSelectorPopover.vue', import.meta.url),
    'utf8'
  )

  // 1. 验证 CloudWorkspace 向 SubagentSessionTab 透传模型配置与父任务模型状态
  assert.match(
    cloudWorkspaceSource,
    /:available-models="modelConfigs"/,
    'CloudWorkspace must pass available-models to SubagentSessionTab'
  )
  assert.match(
    cloudWorkspaceSource,
    /:parent-model-config-id="selectedModelConfigId"/,
    'CloudWorkspace must pass parent-model-config-id to SubagentSessionTab'
  )
  assert.match(
    cloudWorkspaceSource,
    /:parent-model-name="currentModelName"/,
    'CloudWorkspace must pass parent-model-name to SubagentSessionTab'
  )

  // 2. 验证 SubagentSessionTab 声明对应的 props
  assert.match(
    subagentTabSource,
    /availableModels:\s*\{\s*type:\s*Array/,
    'SubagentSessionTab must declare availableModels prop'
  )
  assert.match(
    subagentTabSource,
    /parentModelConfigId:/,
    'SubagentSessionTab must declare parentModelConfigId prop'
  )

  // 3. 验证 SubagentSessionTab 实现了生效模型计算并传递给 CenterAiWorkspace
  assert.match(
    subagentTabSource,
    /const effectiveModelConfig = computed\(/,
    'SubagentSessionTab must compute effectiveModelConfig'
  )
  assert.match(
    subagentTabSource,
    /:available-models="availableModels"/,
    'SubagentSessionTab must pass availableModels down to CenterAiWorkspace'
  )
  assert.match(
    subagentTabSource,
    /:current-model="currentModelLabel"/,
    'SubagentSessionTab must pass computed currentModelLabel'
  )

  // 4. 验证 SubagentSessionTab 在 sendMessage 时透传 effectiveModelConfigId
  assert.match(
    subagentTabSource,
    /modelConfigId:\s*effectiveModelConfigId\.value/,
    'SubagentSessionTab must send effectiveModelConfigId in streamAgent request'
  )

  // 5. 验证 ModelSelectorPopover 容错保护：非空模型名称时不误显未配置模型
  assert.match(
    popoverSource,
    /props\.currentModel && props\.currentModel !== '未配置模型'/,
    'ModelSelectorPopover must respect currentModel even if models list is temporarily loading'
  )
})
