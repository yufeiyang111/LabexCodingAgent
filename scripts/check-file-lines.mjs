// 唯一职责：检查指定目录下源码文件是否超过行数上限（默认 400 行），
//            目标目录不可读或扫不到源码时同样失败退出。
// 用法：node scripts/check-file-lines.mjs [--max=400] [目录...]
//
// 默认只检查新增模块目录；既有历史目录不纳入，避免与存量代码冲突。
// **落地新的受约束目录时必须在这里登记**（如 I3-c 的 frontend/src/components/sync），
// 否则"≤400 行"这条硬约束对新目录不生效。
//
// 为什么缺失目录也算失败：曾经的实现把 readdir 的异常吞成空数组，于是
//   node scripts/check-file-lines.mjs <写错的目录>
// 会打印"文件行数检查通过：0 个文件均不超过 400 行"并 exit 0 ——
// 门禁永远通过，比没有门禁更危险（它会让"检查过了"变成一句假话）。
// 现在：目标不可读 / 目标下没有可检查源码 / 总检查数为 0，一律 exit 1。

import { readdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// 目标目录一律相对**仓库根**解析，而不是 cwd：在 frontend/ 里执行时按 cwd 解析会得到
// frontend/frontend/src/... 这种假失败（报“目标目录不可读”），误导性强。
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const DEFAULT_TARGETS = ['frontend/src/composables/sync']
const SOURCE_EXTENSIONS = ['.js', '.mjs', '.vue', '.ts', '.go', '.java']
const SKIPPED_DIRECTORIES = new Set(['node_modules', '.git'])

async function collectFiles(directory) {
  const absolute = path.resolve(repoRoot, directory)
  let entries
  try {
    entries = await readdir(absolute, { withFileTypes: true })
  } catch (error) {
    throw new Error(`目标目录不可读：${absolute}（${error.code || error.message}）`)
  }
  const files = []
  for (const entry of entries) {
    const full = path.join(absolute, entry.name)
    if (entry.isDirectory()) {
      if (SKIPPED_DIRECTORIES.has(entry.name)) continue
      files.push(...await collectFiles(full))
      continue
    }
    if (SOURCE_EXTENSIONS.includes(path.extname(entry.name))) files.push(full)
  }
  return files
}

const args = process.argv.slice(2)
const maxArgument = args.find(arg => arg.startsWith('--max='))
if (maxArgument && !Number.isFinite(Number(maxArgument.slice('--max='.length)))) {
  console.error(`--max 需要一个数字，收到：${maxArgument.slice('--max='.length)}`)
  process.exit(2)
}
const maxLines = maxArgument ? Number(maxArgument.slice('--max='.length)) : 400
const targets = args.filter(arg => !arg.startsWith('--'))
const scanTargets = targets.length > 0 ? targets : DEFAULT_TARGETS

const violations = []
const emptyTargets = []
let checked = 0

for (const target of scanTargets) {
  let files
  try {
    files = await collectFiles(target)
  } catch (error) {
    console.error(`文件行数检查失败：${error.message}`)
    process.exit(1)
  }
  if (files.length === 0) {
    // 目标存在但没有任何受检源码：要么路径写错，要么模块被误删，两者都必须让人看见。
    emptyTargets.push(target)
    continue
  }
  for (const file of files) {
    const content = await readFile(file, 'utf8')
    const lines = content.split('\n').length
    checked += 1
    if (lines > maxLines) {
      violations.push({ file: path.relative(repoRoot, file), lines })
    }
  }
}

if (violations.length > 0) {
  console.error(`文件行数超限（上限 ${maxLines} 行）：`)
  for (const item of violations) console.error(`  ${item.lines} 行  ${item.file}`)
  console.error('')
  console.error('请拆分文件：单文件单一职责是硬约束，不接受"先合并、以后再说"。')
  process.exit(1)
}

if (emptyTargets.length > 0 || checked === 0) {
  console.error('文件行数检查失败：以下目标没有可检查的源码文件')
  for (const target of emptyTargets) console.error(`  ${target}`)
  console.error('')
  console.error(`受检扩展名：${SOURCE_EXTENSIONS.join(' / ')}`)
  console.error('若是路径写错或目录被改名，请修正参数或 DEFAULT_TARGETS；')
  console.error('若是新目录尚未创建，请不要提前登记——登记即代表必须随时可检查。')
  process.exit(1)
}

console.log(`文件行数检查通过：${checked} 个文件均不超过 ${maxLines} 行。`)
