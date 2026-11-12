import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const prefetch = await readFile(new URL('./prefetch.js', import.meta.url), 'utf8')
const cloudSpace = await readFile(new URL('../views/CloudSpace.vue', import.meta.url), 'utf8')
const routerIndex = await readFile(new URL('./index.js', import.meta.url), 'utf8')

test('workspace chunk is prefetched on hover without changing the route table', () => {
  // 预热必须真的加载 CloudWorkspace 这个模块，否则等于什么都没做
  assert.match(prefetch, /import\('@\/views\/CloudWorkspace\.vue'\)/)

  // 只允许一次：重复 import 不会重复下载，但重复创建 promise 会让失败重试逻辑变复杂
  assert.match(prefetch, /if \(!pending\)/)

  // 失败必须清空缓存，否则一次网络抖动会让这个会话永久失去预热机会
  assert.match(prefetch, /pending = null/)
})

test('project list warms the route on hover and on keyboard focus', () => {
  // 鼠标悬停是桌面主路径；focusin 覆盖键盘用户（Tab 到项目行），两条都要有。
  // 两者都把项目传给预热函数：chunk 只加载一次，但数据要按项目预热。
  assert.match(cloudSpace, /@mouseenter="warmWorkspaceRoute\(proj\)"/)
  assert.match(cloudSpace, /@focusin="warmWorkspaceRoute\(proj\)"/)
  assert.match(cloudSpace, /prefetchWorkspaceRoute/)
})

test('prefetch module stays out of the router to avoid a circular import', () => {
  // 项目列表页 import prefetch；router/index.js 又动态 import 项目列表页。
  // 若 prefetch 放在 router/index.js 里就会形成循环依赖。
  assert.doesNotMatch(routerIndex, /prefetchWorkspaceRoute/)
  assert.match(cloudSpace, /from '@\/router\/prefetch'/)
})
