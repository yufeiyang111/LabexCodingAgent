import assert from 'node:assert/strict'
import test from 'node:test'
import { formatModelContextSummary, printModelContextToConsole } from './modelContextConsole.js'

test('formatModelContextSummary produces clean, structured summary text', () => {
  const summary = formatModelContextSummary({
    taskId: 42,
    iteration: 2,
    model: 'deepseek-chat',
    messages: [
      { role: 'user', content: 'hello' },
      { role: 'assistant', content: 'hi' }
    ],
    tools: [{ name: 'read_file' }]
  })

  assert.match(summary, /Task #42/)
  assert.match(summary, /第 2 轮/)
  assert.match(summary, /deepseek-chat/)
  assert.match(summary, /2 条消息/)
  assert.match(summary, /1 个工具/)
})

test('formatModelContextSummary handles missing or null fields gracefully', () => {
  assert.equal(formatModelContextSummary(null), '')
  const fallback = formatModelContextSummary({})
  assert.match(fallback, /Task #\?/)
  assert.match(fallback, /第 1 轮/)
  assert.match(fallback, /0 条消息/)
})

test('printModelContextToConsole outputs structured groups and sets window globals', () => {
  const originalGroupCollapsed = console.groupCollapsed
  const originalLog = console.log
  const originalGroupEnd = console.groupEnd

  const logs = []
  let groupedTitle = ''
  let groupClosed = false

  console.groupCollapsed = (title) => { groupedTitle = title }
  console.log = (...args) => { logs.push(args) }
  console.groupEnd = () => { groupClosed = true }

  // 模拟 window 全局对象
  globalThis.window = {}

  try {
    const snapshot = {
      taskId: 99,
      iteration: 1,
      model: 'qwen-max',
      provider: 'openai_compatible',
      filePath: '.labex/context-history/task-99-iter-1.json',
      systemPrompt: 'You are an AI assistant',
      messages: [{ role: 'user', content: 'test message' }],
      tools: [{ name: 'write_file' }]
    }

    printModelContextToConsole(snapshot)

    assert.ok(groupedTitle.includes('Task #99'))
    assert.ok(groupedTitle.includes('qwen-max'))
    assert.equal(groupClosed, true)

    // 验证 window 全局挂载
    assert.equal(globalThis.window.__LABEX_LAST_CONTEXT__, snapshot)
    assert.equal(globalThis.window.__LABEX_CONTEXT_HISTORY__.length, 1)
    assert.equal(typeof globalThis.window.__showLastContext, 'function')

    // 验证日志中输出了工作区保存路径和关键组装内容
    const hasFilePath = logs.some(args => args.some(arg => typeof arg === 'string' && arg.includes('.labex/context-history/')))
    assert.ok(hasFilePath, 'Console logs should display workspace file path')

    const hasSystemPrompt = logs.some(args => args.some(arg => typeof arg === 'string' && arg.includes('System Prompt')))
    assert.ok(hasSystemPrompt, 'Console logs should display System Prompt section')

    const hasMessages = logs.some(args => args.some(arg => typeof arg === 'string' && arg.includes('Assembled Messages')))
    assert.ok(hasMessages, 'Console logs should display Assembled Messages section')
  } finally {
    console.groupCollapsed = originalGroupCollapsed
    console.log = originalLog
    console.groupEnd = originalGroupEnd
    delete globalThis.window
  }
})
