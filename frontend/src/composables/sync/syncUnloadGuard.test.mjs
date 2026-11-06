import assert from 'node:assert/strict'
import test from 'node:test'

import { createUnloadGuard } from './syncUnloadGuard.js'

// 需求（用户 2026-11-05）：同步过程中离开页面（切标签页、最小化、路由跳转）不应中断。
//
// 技术边界：File System Access 的目录句柄只在页面存活期间可用，
// 因此"关掉标签页后继续写盘"在浏览器里**无法实现**（那是 daemon 的职责，属于 L3）。
// 能做到的是两件事：
//   1. 切标签页/最小化/路由跳转时同步继续跑（Promise 不受影响，本来就是）；
//   2. 用户真的要关闭/刷新时，浏览器弹出"离开此页面？"确认，避免误操作丢进度。

test('the unload guard is inactive until a sync starts', () => {
  const guard = createUnloadGuard()

  assert.equal(guard.active, false)
  assert.equal(guard.shouldWarn(), false)
})

test('the guard warns while a sync is in flight', () => {
  const guard = createUnloadGuard()

  guard.begin()
  assert.equal(guard.active, true)
  assert.equal(guard.shouldWarn(), true)
})

test('the guard stops warning after the sync finishes', () => {
  const guard = createUnloadGuard()

  guard.begin()
  guard.end()
  assert.equal(guard.active, false)
  assert.equal(guard.shouldWarn(), false)
})

test('begin and end are idempotent so overlapping calls cannot leak the guard', () => {
  const guard = createUnloadGuard()

  guard.begin()
  guard.begin()
  assert.equal(guard.depth, 2)

  guard.end()
  // 还有一层在跑，不能提前解除
  assert.equal(guard.shouldWarn(), true)

  guard.end()
  assert.equal(guard.shouldWarn(), false)
})

test('end never drives depth below zero', () => {
  const guard = createUnloadGuard()

  guard.end()
  guard.end()
  assert.equal(guard.depth, 0)
  assert.equal(guard.shouldWarn(), false)
})

test('attaching a target registers and removes the beforeunload listener', () => {
  const listeners = new Map()
  const target = {
    addEventListener(type, handler) { listeners.set(type, handler) },
    removeEventListener(type) { listeners.delete(type) }
  }

  const guard = createUnloadGuard()
  guard.attach(target)
  assert.equal(listeners.has('beforeunload'), true)

  guard.begin()
  // 有同步在跑时，beforeunload 必须阻止离开
  const event = { preventDefault() { this.prevented = true }, returnValue: undefined }
  listeners.get('beforeunload')(event)
  assert.equal(event.prevented, true)
  assert.equal(typeof event.returnValue, 'string')

  guard.end()
  const idleEvent = { preventDefault() { this.prevented = true }, returnValue: undefined }
  listeners.get('beforeunload')(idleEvent)
  assert.equal(idleEvent.prevented, undefined)

  guard.detach()
  assert.equal(listeners.has('beforeunload'), false)
})
