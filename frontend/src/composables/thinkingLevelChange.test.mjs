import assert from 'node:assert/strict'
import test from 'node:test'

import { persistThinkingLevel, labelOfReasoningEffort } from './thinkingLevelChange.js'

/*
 * 这些用例真正执行保存逻辑（而不是对源码做文本断言）。
 *
 * 背景：该逻辑原先内联在 CloudWorkspace.vue，因 `saved` 声明在 if 块内、块外引用，
 * 运行时抛 ReferenceError —— 静态检查与构建都发现不了，部署后才暴露。
 * 抽成模块并在此真正调用后，这类错误会在测试阶段直接失败。
 */

const OPTIONS = [
  { value: 'low', label: '低', effective: 'low' },
  { value: 'medium', label: '中', effective: 'high' },
  { value: 'high', label: '高', effective: 'high' },
  { value: 'xhigh', label: '超高', effective: 'max' },
  { value: 'max', label: '极致', effective: 'max' }
]

const config = (id, effort, extra = {}) => ({
  configId: id,
  configName: 'cfg' + id,
  modelName: 'model-' + id,
  reasoningEffort: effort,
  reasoningOptions: OPTIONS,
  ...extra
})

test('档位变化时调用保存接口，并返回服务端的最新配置', async () => {
  const configs = [config(1, 'high')]
  const calls = []
  const update = async (id, body) => {
    calls.push({ id, body })
    return { data: { ...configs[0], reasoningEffort: body.reasoningEffort } }
  }

  const r = await persistThinkingLevel({
    payload: { configId: 1, modelName: 'model-1', value: 'max' },
    configs,
    update
  })

  assert.equal(r.ok, true)
  assert.equal(calls.length, 1, '应恰好调用一次保存接口')
  assert.deepEqual(calls[0], { id: 1, body: { reasoningEffort: 'max' } })
  assert.equal(r.value, 'max')
  // 关键：返回的配置必须带 reasoningOptions，调用方据此解析档位中文名。
  // 若这里退化为 undefined，成功提示会退化成英文档位名（或直接抛错）。
  assert.equal(Array.isArray(r.config.reasoningOptions), true)
  assert.equal(r.config.reasoningEffort, 'max')
})

test('档位未变化时不发起请求，直接返回原配置', async () => {
  const configs = [config(2, 'xhigh')]
  let called = 0
  const update = async () => { called++; return { data: null } }

  const r = await persistThinkingLevel({
    payload: { configId: 2, value: 'xhigh' },
    configs,
    update
  })

  assert.equal(r.ok, true)
  assert.equal(called, 0, '同值不应产生网络请求')
  assert.equal(r.config, configs[0])
})

test('大小写与空白差异不算变化（与后端归一规则一致）', async () => {
  const configs = [config(3, 'high')]
  let called = 0
  const r = await persistThinkingLevel({
    payload: { configId: 3, value: '  HIGH ' },
    configs,
    update: async () => { called++; return { data: config(3, 'high') } }
  })
  assert.equal(r.ok, true)
  assert.equal(called, 0)
})

test('服务端未返回配置时如实失败，不谎报成功', async () => {
  const configs = [config(4, 'low')]
  const r = await persistThinkingLevel({
    payload: { configId: 4, value: 'max' },
    configs,
    update: async () => ({ data: null })
  })
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'empty-response')
  assert.equal(r.target.configId, 4, '失败时仍要给出目标配置，供调用方提示')
})

test('找不到目标配置时如实失败', async () => {
  const r = await persistThinkingLevel({
    payload: { configId: 999, value: 'max' },
    configs: [config(1, 'low')],
    update: async () => { throw new Error('不应被调用') }
  })
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'target-not-found')
})

test('空档位直接拒绝，不触碰接口', async () => {
  let called = 0
  const r = await persistThinkingLevel({
    payload: { configId: 1, value: '' },
    configs: [config(1, 'low')],
    update: async () => { called++; return { data: null } }
  })
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'empty-value')
  assert.equal(called, 0)
})

test('保存接口失败时异常必须向上抛出，由调用方保持状态不变', async () => {
  await assert.rejects(
    () => persistThinkingLevel({
      payload: { configId: 5, value: 'max' },
      configs: [config(5, 'low')],
      update: async () => { throw new Error('网络错误') }
    }),
    /网络错误/,
    '异常不能被吞掉，否则调用方会误判为成功'
  )
})

test('可按 modelName / configName 回落定位目标配置', async () => {
  const configs = [config(6, 'low'), config(7, 'low')]
  const byModel = await persistThinkingLevel({
    payload: { modelName: 'model-7', value: 'high' },
    configs,
    update: async (id, body) => ({ data: { ...configs.find(c => c.configId === id), reasoningEffort: body.reasoningEffort } })
  })
  assert.equal(byModel.ok, true)
  assert.equal(byModel.target.configId, 7)

  const byName = await persistThinkingLevel({
    payload: { modelName: 'cfg6', value: 'high' },
    configs,
    update: async (id, body) => ({ data: { ...configs.find(c => c.configId === id), reasoningEffort: body.reasoningEffort } })
  })
  assert.equal(byName.target.configId, 6)
})

test('档位中文名取自后端下发的档位表，未知取值不伪装', () => {
  assert.equal(labelOfReasoningEffort(OPTIONS, 'xhigh'), '超高')
  assert.equal(labelOfReasoningEffort(OPTIONS, 'max'), '极致')
  assert.equal(labelOfReasoningEffort(OPTIONS, 'unknown-level'), 'unknown-level')
  assert.equal(labelOfReasoningEffort(null, 'low'), 'low')
})
