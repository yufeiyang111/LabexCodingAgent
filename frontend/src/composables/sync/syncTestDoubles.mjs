// 同步测试共享替身：只被 *.test.mjs 引用，不进入生产代码路径。
export function createMemoryAdapter(initial = {}, options = {}) {
  const files = new Map(Object.entries(initial))
  const writes = []
  return {
    files,
    writes,
    async listPaths() { return [...files.keys()].sort() },
    async fileExists(path) { return files.has(path) },
    async readTextFile(path) { return files.has(path) ? files.get(path) : null },
    async writeTextFile(path, content) {
      if (path === options.failOnWrite) throw new Error('disk full')
      files.set(path, content)
      writes.push(path)
    },
    async deleteFile(path) { return files.delete(path) }
  }
}

export function createJournalStore(initial) {
  return {
    stored: initial,
    async load() { return this.stored },
    async save(journal) { this.stored = journal }
  }
}

export function modify(changeSetId, path, before, after) {
  return {
    id: `c${changeSetId}`,
    changeSetId,
    relativePath: path,
    changeType: 'modify',
    beforeContent: before,
    afterContent: after
  }
}
