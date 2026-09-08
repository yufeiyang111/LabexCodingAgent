import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

import { DEFAULT_MAX_TOKENS, modelConfigPresets } from '../../../constants/modelPresets.js'
import { MODEL_VENDOR_ICONS, getVendorIcon } from '../../../assets/icons/models/index.js'

test('modelConfigPresets includes rich Chinese and global flagship vendors with 2026 specs', () => {
  assert.equal(DEFAULT_MAX_TOKENS, 32000)
  assert.ok(modelConfigPresets.length >= 25, 'should have expanded vendor presets list')

  // 校验中国大陆主流核心厂商
  const requiredChineseVendors = [
    'DeepSeek',
    'Qwen DashScope',
    'Kimi',
    'GLM BigModel',
    'MiniMax',
    'Volcengine Ark',
    'Baidu Qianfan',
    'Tencent Hunyuan',
    'StepFun',
    'Baichuan AI',
    '01.AI',
    'SenseTime',
    'iFlytek Spark',
    'InfiniGen'
  ]

  for (const vName of requiredChineseVendors) {
    const found = modelConfigPresets.find(p => p.name === vName)
    assert.ok(found, `Must include Chinese flagship vendor: ${vName}`)
    assert.ok(found.baseUrl.startsWith('http'), `${vName} must have valid baseUrl`)
    assert.ok(found.modelName, `${vName} must have default flagship modelName`)
    assert.ok(found.iconKey, `${vName} must specify iconKey`)
    assert.equal(found.category, 'china', `${vName} should be categorized as china`)
    assert.equal(found.maxTokens, 32000)
  }

  // 校验国际前沿大模型
  const globalVendors = ['OpenAI', 'Anthropic Claude', 'Google Gemini', 'xAI', 'Mistral AI']
  for (const gName of globalVendors) {
    const found = modelConfigPresets.find(p => p.name === gName)
    assert.ok(found, `Must include global vendor: ${gName}`)
    assert.equal(found.category, 'global')
  }
})

test('MODEL_VENDOR_ICONS provides lightweight official SVG icons for all vendors', () => {
  assert.ok(Object.keys(MODEL_VENDOR_ICONS).length >= 30)

  // 验证 getVendorIcon 辅助查找器
  assert.ok(getVendorIcon('deepseek'))
  assert.ok(getVendorIcon('QWEN')) // 大小写不敏感
  assert.ok(getVendorIcon('stepfun'))
  assert.ok(getVendorIcon('baichuan'))
  assert.ok(getVendorIcon('lingyi'))
  assert.ok(getVendorIcon('sensetime'))
  assert.ok(getVendorIcon('spark'))
  assert.ok(getVendorIcon('openai'))
  assert.ok(getVendorIcon('anthropic'))
  assert.ok(getVendorIcon('ollama'))
  assert.equal(getVendorIcon('unknown_non_existent'), null)

  // 验证官方开源 SVG 格式（具备标准 viewBox 与矢量内容，而非空串或简单矩形兜底）
  const dsIcon = getVendorIcon('deepseek')
  assert.match(dsIcon, /viewBox="0 0 24 24"/)
  assert.match(dsIcon, /<path/)
})

test('Component architecture adheres to single responsibility and prop-based extensibility', async () => {
  const iconComp = await readFile(new URL('./ModelVendorIcon.vue', import.meta.url), 'utf8')
  const cardComp = await readFile(new URL('./ModelTemplateCard.vue', import.meta.url), 'utf8')
  const selectorComp = await readFile(new URL('./ModelTemplateSelector.vue', import.meta.url), 'utf8')
  const dialogComp = await readFile(new URL('../ModelConfigDialog.vue', import.meta.url), 'utf8')

  // ModelVendorIcon 接收 iconKey, name, accent, size 等 props
  assert.match(iconComp, /iconKey:/)
  assert.match(iconComp, /name:/)
  assert.match(iconComp, /accent:/)
  assert.match(iconComp, /getVendorIcon/)

  // ModelTemplateCard 接收 template prop 并触发 select 事件
  assert.match(cardComp, /template:/)
  assert.match(cardComp, /\$emit\('select'/)

  // ModelTemplateSelector 接收 templates prop，并提供分类筛选与搜索
  assert.match(selectorComp, /templates:/)
  assert.match(selectorComp, /searchKeyword/)
  assert.match(selectorComp, /categories/)
  assert.match(selectorComp, /filteredTemplates/)

  // ModelConfigDialog 引入并使用 ModelTemplateSelector，而非维护内联模板网格上帝代码
  assert.match(dialogComp, /ModelTemplateSelector/)
  assert.match(dialogComp, /:templates="state\.mcTemplateOptions"/)
})
