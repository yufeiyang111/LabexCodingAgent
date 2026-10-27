// 唯一职责：让非开发同学用一条命令看到「同步供给能完整翻页」这件事。
//
// 它证明什么：把分页调小（例如每页 1 条）后，一路按服务端给的游标翻下去，
//            最终拿到的文件数必须等于数据库里的真实条数（不漏、不重复、不卡死）。
// 它不证明什么：不代表浏览器端（选目录/写本地文件）已经可用——那部分还没接线。
//
// 用法（需要后端已在 8080 运行、且 design/_tok.json 里有有效 token）：
//   node scripts/verify-sync-feed.mjs --project 2 --after 339 --tail 2026-07-10T00:00:00 --expect 137
// 退出码 0 = 通过，1 = 失败（并打印失败原因）。

import { readFileSync } from 'node:fs'

const args = new Map()
for (let i = 2; i < process.argv.length; i += 2) {
  args.set(process.argv[i].replace(/^--/, ''), process.argv[i + 1])
}
const projectId = args.get('project') ?? '2'
const after = args.get('after') ?? '0'
const tail = args.get('tail') ?? ''
const expectRaw = args.get('expect')
const tokenFile = args.get('token-file') ?? 'design/_tok.json'
const maxPages = Number(args.get('max-pages') ?? 2000)
const base = args.get('base') ?? 'http://localhost:8080/api'

let token
try {
  token = JSON.parse(readFileSync(tokenFile, 'utf8')).token
} catch (error) {
  console.error(`✗ 读不到 token（${tokenFile}）：${error.message}`)
  console.error('  先按 README/交接文档用 mint-token.mjs 生成 token（需后端已启动）。')
  process.exit(1)
}

const seen = new Map()
const visitedCursors = new Set()
let cursorKey = `${after}|${tail}|`
let pages = 0
let hasMore = true
let lastPageSizes = []
let failure = ''

while (hasMore) {
  if (visitedCursors.has(cursorKey)) {
    failure = `游标没有前进，陷入死循环（停在 ${cursorKey}）`
    break
  }
  visitedCursors.add(cursorKey)
  if (pages >= maxPages) {
    failure = `超过 ${maxPages} 页仍未收敛（服务端可能一直报告 hasMore=true）`
    break
  }

  const [afterChangeSetId, tailUpdateTime, tailChangeId] = cursorKey.split('|')
  const url = `${base}/student/projects/${projectId}/agent/changes/feed`
    + `?afterChangeSetId=${afterChangeSetId}`
    + `&tailUpdateTime=${encodeURIComponent(tailUpdateTime)}`
    + `&tailChangeId=${encodeURIComponent(tailChangeId)}`
  let body
  try {
    body = await (await fetch(url, { headers: { Authorization: `Bearer ${token}` } })).json()
  } catch (error) {
    failure = `请求失败：${error.message}`
    break
  }
  if (body.code !== 0) {
    // 兼容 Spring 默认错误体（没有 code 字段）：直接把原始内容打出来，
    // 否则只会看到 “undefined”，无法区分过期 token / 权限不足 / 路径写错。
    failure = body.code === undefined
      ? `HTTP 响应不是统一格式（可能 token 过期或未鉴权）：${JSON.stringify(body).slice(0, 200)}`
      : `接口返回错误：${body.message}`
    break
  }
  const data = body.data
  for (const file of data.changedFiles) {
    seen.set(file.changeId, (seen.get(file.changeId) || 0) + 1)
  }
  pages += 1
  lastPageSizes.push(data.changedFiles.length)
  if (pages <= 3) {
    console.log(`  第 ${pages} 页：本页 ${data.changedFiles.length} 条，服务端说还有更多=${data.hasMoreChangedFiles}，`
      + `下一页游标=${data.nextCursor.tailUpdateTime}|${data.nextCursor.tailChangeId.slice(0, 8)}`)
  }
  hasMore = data.hasMore === true
  cursorKey = `${data.nextCursor.afterChangeSetId}|${data.nextCursor.tailUpdateTime}|${data.nextCursor.tailChangeId}`
}

const distinct = seen.size
const deliveries = [...seen.values()].reduce((sum, n) => sum + n, 0)
console.log('')
console.log(`总页数=${pages} 去重交付=${distinct} 重复交付次数=${deliveries - distinct} 每页条数(前5)=${lastPageSizes.slice(0, 5).join(',')}`)

let ok = true
if (failure) {
  console.log(`✗ 失败：${failure}`)
  ok = false
}
if (expectRaw !== undefined) {
  const expected = Number(expectRaw)
  if (distinct === expected) {
    console.log(`✓ 与数据库口径一致：${distinct} = ${expected}（不漏不重，翻页能收敛）`)
  } else {
    console.log(`✗ 与数据库口径不一致：拿到 ${distinct}，数据库是 ${expected}（相差 ${expected - distinct}）`)
    ok = false
  }
} else {
  console.log(`（未提供 --expect，跳过与数据库对账；本次拿到 ${distinct} 条）`)
}
// 不要用 process.exit()：fetch 的 socket 还在关闭中，Windows 上会触发
// "Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)" 假崩溃，吓到使用者。
process.exitCode = ok ? 0 : 1
