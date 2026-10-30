// 唯一职责：把 /agent/changes/feed 分页拉取到两条进度线都收敛。
// 边界：只调用注入的 fetchPage，不直接发 HTTP、不读写本地文件、不渲染 UI。
// 安全不变量：
//   1. nextCursor 三个字段原样回传；
//   2. hasMoreChangeSets / hasMoreChangedFiles 任一为 true 就继续；
//   3. 重复行按 changeId + status 幂等去重；
//   4. 游标不前进或页数失控时显式失败，禁止无限循环。

const DEFAULT_MAX_PAGES = 1000

function normalizeCursor(cursor) {
  return {
    afterChangeSetId: Number(cursor?.afterChangeSetId ?? 0) || 0,
    tailUpdateTime: cursor?.tailUpdateTime ?? null,
    tailChangeId: cursor?.tailChangeId ?? ''
  }
}

function sameCursor(left, right) {
  return left.afterChangeSetId === right.afterChangeSetId
    && left.tailUpdateTime === right.tailUpdateTime
    && left.tailChangeId === right.tailChangeId
}

function rowKey(row) {
  return `${String(row?.changeId ?? '')}\u0000${String(row?.status ?? '')}`
}

export async function loadAllAgentChangeFeed(fetchPage, options = {}) {
  if (typeof fetchPage !== 'function') throw new Error('loadAllAgentChangeFeed requires a fetchPage function')

  const maxPages = Number(options.maxPages ?? DEFAULT_MAX_PAGES)
  if (!Number.isInteger(maxPages) || maxPages < 1) throw new Error('maxPages must be a positive integer')

  let cursor = normalizeCursor(options)
  const changeSets = []
  const changedFiles = []
  const seenChangeSets = new Set()
  const seenChangedFiles = new Set()

  for (let pageNumber = 1; pageNumber <= maxPages; pageNumber += 1) {
    const page = await fetchPage({
      afterChangeSetId: cursor.afterChangeSetId,
      tailUpdateTime: cursor.tailUpdateTime,
      tailChangeId: cursor.tailChangeId,
      limit: options.limit
    })
    if (!page || typeof page !== 'object') throw new Error('change feed page must be an object')

    for (const changeSet of Array.isArray(page.changeSets) ? page.changeSets : []) {
      const key = String(changeSet?.changeSetId ?? '')
      if (seenChangeSets.has(key)) continue
      seenChangeSets.add(key)
      changeSets.push(changeSet)
    }
    for (const file of Array.isArray(page.changedFiles) ? page.changedFiles : []) {
      const key = rowKey(file)
      if (seenChangedFiles.has(key)) continue
      seenChangedFiles.add(key)
      changedFiles.push(file)
    }

    const nextCursor = normalizeCursor(page.nextCursor ?? cursor)
    const hasMoreChangeSets = page.hasMoreChangeSets === true || page.hasMore === true
    const hasMoreChangedFiles = page.hasMoreChangedFiles === true || page.hasMore === true
    const hasMore = hasMoreChangeSets || hasMoreChangedFiles
    if (!hasMore) {
      return {
        changeSets,
        changedFiles,
        nextCursor,
        hasMore: false,
        hasMoreChangeSets: false,
        hasMoreChangedFiles: false
      }
    }
    if (sameCursor(cursor, nextCursor)) throw new Error('change feed cursor did not advance')
    cursor = nextCursor
  }

  throw new Error(`change feed page limit exceeded: ${maxPages}`)
}
