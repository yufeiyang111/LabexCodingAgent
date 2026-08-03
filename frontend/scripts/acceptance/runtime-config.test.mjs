import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const browserRuntimeUrl = new URL('../../../scripts/acceptance/browser-runtime.ps1', import.meta.url)
const browserRuntimeBytes = await readFile(browserRuntimeUrl)
const browserRuntime = browserRuntimeBytes.toString('utf8')

test('browser acceptance script preserves the UTF-8 BOM required by Windows PowerShell 5', () => {
  assert.deepEqual([...browserRuntimeBytes.subarray(0, 3)], [0xef, 0xbb, 0xbf])
})

test('browser acceptance bounds JVM native and heap memory', () => {
  assert.match(browserRuntime, /'-Xms64m'/)
  assert.match(browserRuntime, /'-Xmx512m'/)
  assert.match(browserRuntime, /'-XX:MaxMetaspaceSize=256m'/)
  assert.match(browserRuntime, /'-XX:ReservedCodeCacheSize=128m'/)
})