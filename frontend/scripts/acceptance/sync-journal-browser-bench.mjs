// journal 增量存储的端到端测量：真实 Chromium + 真实 IndexedDB + 真实 OPFS 目录。
//
// 为什么必须在浏览器里测：IndexedDB 的事务开销、序列化、配额行为在 Node 里没有等价物。
// 这个脚本回答的是"改完之后，一次同步到底往 IndexedDB 写了多少"。
import { spawn } from 'node:child_process'
import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { CdpClient } from './cdp-client.mjs'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const PORT = 19355
const FILE_COUNT = Number(process.argv[2] ?? 500)
const BODY_BYTES = Number(process.argv[3] ?? 4096)

const profile = await mkdtemp(join(tmpdir(), 'labex-journal-bench-'))
const browser = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, 'about:blank'
], { stdio: 'ignore', windowsHide: true })

async function waitJson(url, predicate) {
  for (let i = 0; i < 400; i += 1) {
    try {
      const value = await (await fetch(url)).json()
      if (predicate(value)) return value
    } catch {}
    await new Promise(r => setTimeout(r, 150))
  }
  throw new Error('timeout ' + url)
}

const targets = await waitJson(`http://127.0.0.1:${PORT}/json/list`,
  v => Array.isArray(v) && v.some(t => t.type === 'page' && t.webSocketDebuggerUrl))
const page = targets.find(t => t.type === 'page' && t.webSocketDebuggerUrl)
const cdp = await CdpClient.connect(page.webSocketDebuggerUrl, { timeoutMs: 30000 })
await Promise.all([cdp.send('Page.enable'), cdp.send('Runtime.enable')])

// 需要一个同源页面才能用 IndexedDB / OPFS
await cdp.send('Page.navigate', { url: 'http://127.0.0.1:3000/login' })
await new Promise(r => setTimeout(r, 3000))

const result = await cdp.evaluate(`(async () => {
  const FILE_COUNT = ${FILE_COUNT};
  const BODY_BYTES = ${BODY_BYTES};
  const mod = await import('/src/composables/sync/journalStore.js');
  const journalMod = await import('/src/composables/sync/syncJournal.js');

  const DB = 'journal-bench-' + Date.now();
  const factory = {
    open(name, version) {
      const request = { result: null, error: null, onupgradeneeded: null, onsuccess: null, onerror: null };
      const req = version === undefined ? indexedDB.open(name) : indexedDB.open(name, version);
      req.onupgradeneeded = event => {
        const db = req.result;
        for (const store of ['directory-handles', 'sync-journals', 'sync-journal-entries', 'sync-journal-cursors']) {
          if (!db.objectStoreNames.contains(store)) db.createObjectStore(store);
        }
        request.onupgradeneeded?.(event);
      };
      req.onsuccess = () => { request.result = req.result; request.onsuccess?.(); };
      req.onerror = () => { request.error = req.error; request.onerror?.(); };
      return request;
    }
  };

  // 统计实际写入 IndexedDB 的字节数：包一层 put。
  let bytesWritten = 0;
  let putCount = 0;
  const originalPut = IDBObjectStore.prototype.put;
  IDBObjectStore.prototype.put = function (value, key) {
    try { bytesWritten += JSON.stringify(value).length; putCount += 1; } catch {}
    return originalPut.call(this, value, key);
  };

  const body = 'x'.repeat(BODY_BYTES);
  let journal = journalMod.createJournal();
  const started = performance.now();
  const saves = [];
  for (let step = 1; step <= FILE_COUNT; step += 1) {
    journal = journalMod.recordApplied(journal, {
      changeId: 'c' + step, changeSetId: 1, path: 'src/f' + step + '.js',
      op: 'write', content: body, previousContent: null, previousExisted: false
    });
    if (step % 8 === 0 || step === FILE_COUNT) {
      journal = journalMod.updateJournalCursor(journal, {
        afterChangeSetId: 1, tailUpdateTime: '2026-11-06T10:00:00', tailChangeId: ''
      });
      const before = bytesWritten;
      await mod.saveJournal(DB, journal, { indexedDb: factory });
      saves.push(bytesWritten - before);
    }
  }
  const elapsed = performance.now() - started;

  IDBObjectStore.prototype.put = originalPut;

  // 回读校验：条目数与游标必须完整
  const loaded = await mod.loadJournal(DB, { indexedDb: factory });
  const ok = loaded && loaded.entries.length === FILE_COUNT
    && loaded.changeSetCursor.afterChangeSetId === 1;

  indexedDB.deleteDatabase(DB);
  return {
    fileCount: FILE_COUNT,
    bodyBytes: BODY_BYTES,
    contentMB: (FILE_COUNT * BODY_BYTES / 1024 / 1024).toFixed(2),
    elapsedMs: Math.round(elapsed),
    saveCount: saves.length,
    totalWrittenMB: (bytesWritten / 1024 / 1024).toFixed(2),
    putCount,
    roundTripOk: ok,
    loadedEntries: loaded?.entries.length ?? -1
  };
})()`)

console.log(JSON.stringify(result, null, 2))
cdp.close()
browser.kill()
process.exitCode = result?.roundTripOk ? 0 : 1
