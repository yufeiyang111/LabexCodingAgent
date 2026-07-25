import { readdir, stat } from 'node:fs/promises'
import { resolve } from 'node:path'

const budgets = [
  { prefix: 'CloudWorkspace-', maxBytes: 1_500_000 },
  { prefix: 'index-', maxBytes: 1_300_000 },
  { prefix: 'TerminalPanel-', maxBytes: 400_000 }
]
const assetsDirectory = resolve('dist/assets')
const files = await readdir(assetsDirectory)
const failures = []

for (const budget of budgets) {
  const matches = files.filter(file => file.startsWith(budget.prefix) && file.endsWith('.js'))
  if (matches.length !== 1) {
    failures.push(`${budget.prefix}: expected exactly one JavaScript chunk, found ${matches.length}`)
    continue
  }
  const file = matches[0]
  const size = (await stat(resolve(assetsDirectory, file))).size
  if (size > budget.maxBytes) {
    failures.push(`${file}: ${size} bytes exceeds ${budget.maxBytes} byte budget`)
  } else {
    console.log(`${file}: ${size} bytes (budget ${budget.maxBytes})`)
  }
}

if (failures.length > 0) {
  console.error('Chunk budget check failed:')
  for (const failure of failures) console.error(`- ${failure}`)
  process.exit(1)
}
