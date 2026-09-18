#!/usr/bin/env node
/**
 * 校验 backend/src/main/resources/sql/data.sql 的种子版本一致性。
 *
 * 背景（真实故障）：教程种子正文的标记已升到 v3，但 18 处 ON DUPLICATE KEY UPDATE 的版本守卫
 * 仍停在 v2。守卫语义是「库里是 v2 就保留旧值」，于是所有 v2 行被判为保留、永远不升级，
 * 新写的教程内容在开发与生产都静默不生效 —— 部署一切正常，页面上却还是旧文案。
 *
 * 本脚本把「标记版本」与「守卫版本」的一致性变成可机械校验的约束：
 * 版本号只允许出现在一个地方被改，漏改守卫会在这里直接失败。
 *
 * 用法：node scripts/verify-seed-version.mjs [sql文件路径]
 *       缺省校验 backend/src/main/resources/sql/data.sql；传路径可用于自检本脚本。
 *       非零退出码即失败。
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const seedFile = process.argv[2]
  ? resolve(process.cwd(), process.argv[2])
  : resolve(here, '../backend/src/main/resources/sql/data.sql')
const sql = readFileSync(seedFile, 'utf8')

const problems = []
const report = []

// 1. 正文标记：VALUES 里出现的 '<!-- labex-tutorial-seed:vN -->'
const bodyMarkers = [...sql.matchAll(/'<!--\s*labex-tutorial-seed:(v\d+)\s*-->/g)].map(m => m[1])
const markerVersions = [...new Set(bodyMarkers)]
report.push(`正文标记: ${bodyMarkers.length} 处，版本 ${markerVersions.join(', ') || '(无)'}`)

if (!bodyMarkers.length) {
  problems.push('未找到任何正文种子标记（形如 <!-- labex-tutorial-seed:v3 -->），约定被破坏或文件结构已变')
}
if (markerVersions.length > 1) {
  problems.push(`正文标记存在多个版本：${markerVersions.join(', ')} —— 一次升级必须全量替换`)
}

// 2. 守卫：IF(content_markdown LIKE '%labex-tutorial-seed:vN%', ...)
const guardVersions = [...sql.matchAll(/IF\s*\(\s*content_markdown\s+LIKE\s+'%labex-tutorial-seed:(v\d+)%'/g)].map(m => m[1])
const guardSet = [...new Set(guardVersions)]
report.push(`版本守卫: ${guardVersions.length} 处，版本 ${guardSet.join(', ') || '(无)'}`)

if (!guardVersions.length) {
  problems.push('未找到任何版本守卫（IF(content_markdown LIKE ...)），约定被破坏')
}
if (guardSet.length > 1) {
  problems.push(`版本守卫存在多个版本：${guardSet.join(', ')} —— 升级时必须全部同步`)
}

// 3. 守卫数量必须与 upsert 块数成比例
const upsertBlocks = (sql.match(/ON DUPLICATE KEY UPDATE/g) || []).length
report.push(`upsert 块: ${upsertBlocks} 个`)
if (upsertBlocks > 0 && guardVersions.length !== upsertBlocks * 5) {
  problems.push(
    `守卫行数与 upsert 块数不匹配：期望 ${upsertBlocks} × 5 = ${upsertBlocks * 5} 处，实际 ${guardVersions.length} 处` +
    '（每块需覆盖 title/summary/category/sort_order/content_markdown 五个字段）'
  )
}

// 4. 核心约束：标记版本 == 守卫版本
if (markerVersions.length === 1 && guardSet.length === 1) {
  if (markerVersions[0] !== guardSet[0]) {
    problems.push(
      `版本不一致：正文标记为 ${markerVersions[0]}，而版本守卫为 ${guardSet[0]}。\n` +
      `    后果：库中存量 ${guardSet[0]} 行会被判定为「保留」，内容永远不升级到 ${markerVersions[0]}。\n` +
      `    修复：把全部守卫改为 '%labex-tutorial-seed:${markerVersions[0]}%'。`
    )
  } else {
    report.push(`版本一致: ${markerVersions[0]} ✅`)
  }
}

console.log('=== 教程种子版本一致性校验 ===')
for (const line of report) console.log('  ' + line)

if (problems.length) {
  console.log('')
  console.log('❌ 校验失败：')
  for (const p of problems) console.log('  - ' + p)
  process.exit(1)
}

console.log('')
console.log('✅ 校验通过：标记与守卫版本一致，种子内容会在下次启动时按预期升级。')
