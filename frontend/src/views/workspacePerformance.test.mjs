import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const workspace = await readFile(new URL('./CloudWorkspace.vue', import.meta.url), 'utf8')
const center = await readFile(new URL('../components/cloud/chat/CenterAiWorkspace.vue', import.meta.url), 'utf8')
const loading = await readFile(new URL('../components/cloud/layout/AsyncLoadingState.vue', import.meta.url), 'utf8')

test('workspace heavy panels use immediate async loading states', () => {
  assert.match(workspace, /defineAsyncComponent\(\{ loader: \(\) => import\('\@\/components\/cloud\/chat\/CenterAiWorkspace\.vue'\), loadingComponent: AsyncLoadingState/)
  assert.match(workspace, /defineAsyncComponent\(\{ loader: \(\) => import\('\@\/components\/cloud\/chat\/SubagentSessionTab\.vue'\), loadingComponent: AsyncLoadingState/)
  assert.match(workspace, /defineAsyncComponent\(\{ loader: \(\) => import\('\@\/components\/terminal\/TerminalPanel\.vue'\), loadingComponent: AsyncLoadingState/)
  assert.match(workspace, /defineAsyncComponent\(\{ loader: \(\) => import\('\@\/components\/MonacoEditor\.vue'\), loadingComponent: AsyncLoadingState/)
  assert.match(workspace, /loadEcharts/)
  assert.match(center, /v-if="currentTab === 'review'"/)
  assert.match(center, /v-if="currentTab === 'usage'"/)
  assert.match(loading, /正在加载/)
  assert.match(loading, /加载失败，请重试/)
})
