import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('blob 端点解析值就是 Blob 本体，composable 不得再取 .data', async () => {
  // request.js 对 responseType==='blob' 直接返回 response.data（Blob 本体），
  // 再取一层 .data 会得到 undefined，触发 createObjectURL Overload resolution failed。
  const source = await readFile(new URL('./useFilePreviewActions.js', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /\.data\b/)
  assert.match(source, /const blob = await options\.api\.downloadFile\(/)
  assert.match(source, /saveBlobAs\(blob,/)
  assert.match(source, /const blob = await options\.api\.fileImage\(/)
  assert.match(source, /URL\.createObjectURL\(blob\)/)
})
