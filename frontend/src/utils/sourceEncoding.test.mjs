import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import { extname, join, relative } from 'node:path'
import test from 'node:test'

const roots = ['src', '../backend/src']
const sourceExtensions = new Set(['.js', '.mjs', '.vue', '.java'])

async function sourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(entries.map(async entry => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(path)
    return sourceExtensions.has(extname(entry.name)) ? [path] : []
  }))
  return nested.flat()
}

test('source tree contains no user-visible ASCII question-mark corruption', async () => {
  const files = (await Promise.all(roots.map(sourceFiles))).flat()
  const corruptions = []
  for (const file of files) {
    const lines = (await readFile(file, 'utf8')).split(/\r?\n/)
    lines.forEach((line, index) => {
      if (/\?{3,}/.test(line)) corruptions.push(`${relative('..', file)}:${index + 1}: ${line.trim()}`)
    })
  }
  assert.deepEqual(corruptions, [], `Found corrupted source text:\n${corruptions.join('\n')}`)
})