import assert from 'node:assert/strict'
import test from 'node:test'

import {
  canLoadLanguage,
  ensureLanguage,
  getLanguage,
  highlight,
  highlightAuto,
  isLanguageReady,
  onLanguageLoaded
} from './highlight.js'

test('高频语言在模块加载时已同步注册，首屏渲染无需等待', () => {
  for (const name of ['plaintext', 'bash', 'java', 'javascript', 'typescript', 'xml', 'json', 'yaml', 'sql', 'python', 'diff', 'ini']) {
    assert.ok(isLanguageReady(name), `${name} 应已同步注册`)
  }
})

test('冷门语言初始未注册，但标记为可按需加载', () => {
  for (const name of ['kotlin', 'swift', 'rust', 'php', 'scala']) {
    assert.equal(isLanguageReady(name), false, `${name} 初始不应注册`)
    assert.equal(canLoadLanguage(name), true, `${name} 应可按需加载`)
  }
})

test('未注册且不在按需表里的语言既不加载也不报错', () => {
  assert.equal(canLoadLanguage('no-such-language'), false)
  assert.equal(highlight('anything', 'no-such-language'), null)
  assert.equal(highlight('anything', ''), null)
})

test('同步高亮：已注册语言直接返回带 span 的 HTML', () => {
  const html = highlight('public class Demo {}', 'java')
  assert.ok(html.includes('hljs-keyword'), '应产出 hljs-keyword')
  assert.ok(html.includes('Demo'))
})

test('同步高亮：未注册语言返回 null 并触发按需加载', async () => {
  assert.equal(isLanguageReady('kotlin'), false)
  const html = highlight('fun main() {}', 'kotlin')
  assert.equal(html, null, '语言未就绪时必须返回 null，由调用方回退纯文本')

  await ensureLanguage('kotlin')
  assert.ok(isLanguageReady('kotlin'), '按需加载后应可用')

  const upgraded = highlight('fun main() {}', 'kotlin')
  assert.ok(upgraded && upgraded.includes('hljs-keyword'), '加载后应能产出高亮 HTML')
})

test('ensureLanguage 对同一语言只加载一次', async () => {
  const results = await Promise.all([ensureLanguage('rust'), ensureLanguage('rust'), ensureLanguage('rust')])
  assert.deepEqual(results, [true, true, true])
  assert.ok(isLanguageReady('rust'))
})

test('ensureLanguage 对已注册语言立即返回 true', async () => {
  assert.equal(await ensureLanguage('java'), true)
})

test('语言加载完成会通知订阅者，便于清缓存并升级已渲染块', async () => {
  const seen = []
  const off = onLanguageLoaded(name => seen.push(name))
  await ensureLanguage('swift')
  off()
  await ensureLanguage('php')
  assert.ok(seen.includes('swift'), '订阅期间应收到 swift')
  assert.equal(seen.includes('php'), false, '退订后不应再收到通知')
})

test('highlightAuto 在无法确定语言时仍返回可用 HTML', () => {
  const result = highlightAuto('SELECT * FROM t_user WHERE id = 1')
  assert.equal(typeof result.value, 'string')
  assert.ok(result.value.length > 0)
})

test('getLanguage 对未知语言返回 undefined', () => {
  assert.equal(getLanguage('definitely-not-a-language'), undefined)
})
