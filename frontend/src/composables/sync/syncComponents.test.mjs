import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

// 本文件位于 src/composables/sync/，组件与常量分别在上两级的 components/ 与 constants/。
const COMPONENT_DIR = new URL('../../components/sync/', import.meta.url)
const CONSTANTS_URL = new URL('../../constants/sync.js', import.meta.url)
const SESSION_STATE_URL = new URL('./syncSessionState.js', import.meta.url)

async function readComponent(name) {
  return readFile(new URL(name, COMPONENT_DIR), 'utf8')
}

const COMPONENTS = ['SyncToLocalButton.vue', 'SyncPanel.vue', 'SyncProgressList.vue', 'SyncConflictDialog.vue']

test('sync components style themselves only through theme tokens', async () => {
  for (const name of COMPONENTS) {
    const source = await readComponent(name)
    const style = source.slice(source.indexOf('<style'))
    assert.ok(style.includes('var(--theme-'), `${name} must use theme variables`)

    // 硬编码色值（#rgb / #rrggbb / rgb() / hsl()）在样式块里一律不允许：
    // 明暗主题各有一份取值，写死任何一种都会在另一主题下破相。
    const hardCoded = style.match(/#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(/g) ?? []
    assert.deepEqual(hardCoded, [], `${name} must not hardcode colors`)
  }
})

test('sync components never call the network directly', async () => {
  for (const name of COMPONENTS) {
    const source = await readComponent(name)
    assert.ok(!source.includes('axios'), `${name} must not import axios`)
    assert.ok(!/@\/api/.test(source), `${name} must not import the api module`)
    assert.ok(!source.includes('fetch('), `${name} must not call fetch`)
  }
})

test('each sync component documents its single responsibility', async () => {
  for (const name of COMPONENTS) {
    const source = await readComponent(name)
    assert.ok(source.includes('唯一职责'), `${name} must declare its single responsibility`)
  }
})

test('the container component delegates actions upward instead of performing them', async () => {
  const source = await readComponent('SyncPanel.vue')
  for (const action of ['sync', 'pick-directory', 'rollback', 'review-conflicts', 'reset-progress']) {
    assert.ok(
      source.includes(`emit('${action}')`) || source.includes(`@${action}`),
      `SyncPanel must delegate ${action}`
    )
  }
  assert.ok(source.includes('defineEmits'))
  // 容器组件自己不得直接调用同步实现。
  assert.ok(!source.includes('@/composables/sync/'))
})

test('the conflict dialog offers exactly the three documented strategies', async () => {
  const source = await readComponent('SyncConflictDialog.vue')
  assert.ok(source.includes('SYNC_CONFLICT_POLICY_OPTIONS'))
  const constants = await readFile(CONSTANTS_URL, 'utf8')
  for (const policy of ['strict', 'skip', 'overwrite']) {
    assert.ok(constants.includes(`value: '${policy}'`), `missing policy ${policy}`)
  }
})

test('sync state labels cover every state the session machine can emit', async () => {
  const { SYNC_STATES } = await import('./syncSessionState.js')
  const { SYNC_STATE_LABELS, syncStateLabel } = await import('../../constants/sync.js')
  const declared = Object.values(SYNC_STATES)
  assert.equal(declared.length, 8)
  for (const state of declared) {
    assert.ok(Object.hasOwn(SYNC_STATE_LABELS, state), `missing label for state ${state}`)
    assert.notEqual(syncStateLabel(state), state, `state ${state} must have a human label`)
  }
})
