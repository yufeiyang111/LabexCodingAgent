import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

import { MODEL_VENDOR_ICONS, getVendorIcon } from '../../../assets/icons/models/index.js'

const PRESETS_URL = new URL('../../../constants/modelPresets.js', import.meta.url)

/**
 * 经与 @lobehub/icons-static-svg 1.95.0 逐路径比对核验通过的厂商 key。
 * 这份清单是「来源可信」的白名单：任何增删都必须重新完成上游比对并在
 * THIRD_PARTY_NOTICES.md 登记，然后同步更新此列表。
 */
const VERIFIED_VENDOR_KEYS = [
  'anthropic', 'baichuan', 'baidu', 'cerebras', 'deepinfra', 'deepseek',
  'doubao', 'fireworks', 'github', 'google', 'groq', 'huggingface',
  'infinigence', 'kimi', 'lingyi', 'minimax', 'mistral', 'modelscope',
  'nvidia', 'ollama', 'openai', 'openrouter', 'perplexity', 'qwen',
  'sambanova', 'sensetime', 'siliconflow', 'spark', 'stepfun', 'tencent',
  'together', 'xai', 'zhipu'
]

test('注册表只包含经上游核验的厂商，不得出现编造或自绘条目', () => {
  assert.deepEqual(
    Object.keys(MODEL_VENDOR_ICONS).sort(),
    [...VERIFIED_VENDOR_KEYS].sort(),
    '注册表条目发生变化：新增厂商图标必须先在 @lobehub/icons 中核验并登记来源'
  )
})

test('曾出现的无来源条目不得被重新引入', () => {
  // aimlapi：编造的品牌标识；custom：手绘占位图形。二者均无上游来源且无业务引用。
  assert.equal(MODEL_VENDOR_ICONS.aimlapi, undefined, 'aimlapi 图标无官方来源，不应重新加入')
  assert.equal(MODEL_VENDOR_ICONS.custom, undefined, 'custom 图标为手绘占位，不应重新加入')
  assert.equal(getVendorIcon('aimlapi'), null)
  assert.equal(getVendorIcon('custom'), null)
})

test('每个图标都是带 title 的完整 SVG，具备可读标识', () => {
  for (const [key, svg] of Object.entries(MODEL_VENDOR_ICONS)) {
    assert.match(svg, /^<svg[\s>]/, `${key} 不是合法 SVG`)
    assert.match(svg, /xmlns="http:\/\/www\.w3\.org\/2000\/svg"/, `${key} 缺少 SVG 命名空间`)
    assert.match(svg, /<title>[^<]+<\/title>/, `${key} 缺少 <title>，无法确认品牌归属`)
    assert.match(svg, /viewBox="0 0 \d+ \d+"/, `${key} 缺少 viewBox`)
  }
})

test('模型预设实际引用的每个 iconKey 都有对应图标', async () => {
  const presets = await readFile(PRESETS_URL, 'utf8')
  const usedKeys = [...presets.matchAll(/iconKey:\s*'([^']+)'/g)].map(m => m[1])

  assert.ok(usedKeys.length > 0, '未解析到任何 iconKey')

  const missing = usedKeys.filter(key => !getVendorIcon(key))
  assert.deepEqual(missing, [], `以下预设引用了不存在的图标：${missing.join(', ')}`)
})

test('查找函数大小写不敏感，未知 key 返回 null（触发字母兜底）', () => {
  assert.ok(getVendorIcon('DeepSeek'))
  assert.ok(getVendorIcon(' qwen '))
  assert.equal(getVendorIcon('not-a-real-vendor'), null)
  assert.equal(getVendorIcon(''), null)
})
