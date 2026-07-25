import { readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'

async function collectTests(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = await Promise.all(entries.map(async entry => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return collectTests(path)
    return entry.name.endsWith('.test.mjs') ? [path] : []
  }))
  return files.flat()
}

const tests = await collectTests('src')
if (tests.length === 0) {
  console.error('No frontend test files found.')
  process.exit(1)
}
const result = spawnSync(process.execPath, ['--test', ...tests], { stdio: 'inherit' })
process.exit(result.status ?? 1)
