import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

// 冲突策略的作用域。
//
// 对话框写的是"按所选方式重试"，语义上只对那一次重试有效。
// 如果把它存成持久 ref，后果不是文案不准，而是**冲突保护被永久关掉**：
// 用户为一次冲突选了「覆盖」之后，之后每次点同步都会静默覆盖本地改动、也不再弹提示。
// 这里用源码约束固定这一点——视图层没有单元测试环境，行为由 live 验收覆盖。
const VIEW = new URL('../../views/CloudWorkspace.vue', import.meta.url)

async function readView() {
  return readFile(VIEW, 'utf8')
}

test('a plain sync never reuses the policy chosen in a previous conflict dialog', async () => {
  const source = await readView()
  const request = source.slice(
    source.indexOf('async function handleSyncRequest()'),
    source.indexOf('async function handleSyncRetry()')
  )
  assert.ok(request.length > 0, 'handleSyncRequest must exist')
  assert.ok(
    request.includes('SYNC_DEFAULT_CONFLICT_POLICY'),
    'plain sync must run with the safe default, not the dialog selection'
  )
  assert.ok(
    !request.includes('syncConflictPolicy.value'),
    'plain sync must not read the dialog selection'
  )
})

test('the dialog selection is consumed by exactly one retry and then reset', async () => {
  const source = await readView()
  const retry = source.slice(
    source.indexOf('async function handleSyncRetry()'),
    source.indexOf('async function handleSyncRollback()')
  )
  assert.ok(retry.length > 0, 'handleSyncRetry must exist')
  assert.ok(retry.includes('syncConflictPolicy.value'), 'retry must honour the chosen policy')
  assert.ok(
    /syncConflictPolicy\.value\s*=\s*SYNC_DEFAULT_CONFLICT_POLICY/.test(retry),
    'retry must reset the policy so the next plain sync is safe again'
  )
})
