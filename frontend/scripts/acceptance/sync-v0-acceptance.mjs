// v0 验收（规划 §8 四条）：真实项目同步 + 回退演练 + 脏目录冲突拦截 + L0 回归。
//
// 用法（后端在 8081、前端 dev server 在 3000 已就绪）：
//   node scripts/acceptance/sync-v0-acceptance.mjs --project 2
//
// 它证明什么：
//   ① 真实前端项目（排除依赖目录后 patch < 100KB）能从云端落到本地目录，且逐字节一致；
//   ② 回退把本次写入的文件全部撤销，且不碰用户自己放进去的文件；
//   ③ 本地有脏改动时，strict 策略整批拦截，本地文件一个字节都不被动；
//   ④ 冲突策略 skip / overwrite 的行为与文案承诺一致，之后能收敛成"已是最新"。
// 它不证明什么：不覆盖原生目录选择弹窗（CDP 无法自动化原生对话框），
//   这里只把 showDirectoryPicker 这一个入口替换成真实的 OPFS 目录句柄，
//   其后的 attachHandle → adapterFactory → orchestrator → 写盘全部走生产代码。
//
// 退出码 0 = 全部通过；1 = 有断言失败（逐条打印）。

import { execFileSync, spawn } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { access, mkdtemp, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { CdpClient } from './cdp-client.mjs'

const args = new Map()
for (let i = 2; i < process.argv.length; i += 2) {
  args.set(process.argv[i].replace(/^--/, ''), process.argv[i + 1])
}
const projectId = Number(args.get('project') ?? 2)
const apiBase = args.get('api') ?? 'http://127.0.0.1:8081/api'
const uiBase = args.get('ui') ?? 'http://127.0.0.1:3000'
const debugPort = Number(args.get('cdp-port') ?? 19334)
const authFile = args.get('token-file') ?? 'D:/LabexAgent/design/_tok.json'
const syncDirName = args.get('sync-dir') ?? 'labex-sync-v0'
const timeoutMs = Number(args.get('timeout-ms') ?? 180000)
const patchBudgetKb = Number(args.get('patch-budget-kb') ?? 100)

const CHROME_CANDIDATES = [
  process.env.ACCEPTANCE_CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
].filter(Boolean)

const LOCAL_ONLY = '本地手工改动，绝不能被覆盖\n'

const results = []
let token = ''
let browser = null
let profileDir = null
let cdp = null

function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? ` — ${detail}` : ''}`)
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function waitFor(check, label, timeout = timeoutMs) {
  const deadline = Date.now() + timeout
  let last = null
  while (Date.now() < deadline) {
    try {
      const value = await check()
      if (value) return value
    } catch (error) {
      last = error
    }
    await delay(200)
  }
  throw new Error(`等待超时：${label}${last ? `（最后一次错误：${last.message}）` : ''}`)
}

async function api(path) {
  const response = await fetch(`${apiBase}${path}`, { headers: { Authorization: `Bearer ${token}` } })
  const body = await response.json()
  if (body.code !== 0) throw new Error(`API ${path} 失败：${body.message ?? response.status}`)
  return body.data
}

/** 按服务端契约把 feed 翻到底，返回全部 change-set（顺序即权威顺序）。 */
async function loadFeed() {
  const sets = []
  let cursor = { afterChangeSetId: 0, tailUpdateTime: '', tailChangeId: '' }
  for (let page = 0; page < 500; page += 1) {
    const query = new URLSearchParams({
      afterChangeSetId: String(cursor.afterChangeSetId),
      tailUpdateTime: cursor.tailUpdateTime,
      tailChangeId: cursor.tailChangeId,
      limit: '50'
    })
    const data = await api(`/student/projects/${projectId}/agent/changes/feed?${query}`)
    sets.push(...data.changeSets)
    cursor = data.nextCursor
    if (!data.hasMore && !data.hasMoreChangeSets && !data.hasMoreChangedFiles) {
      return { sets, nextCursor: cursor }
    }
  }
  throw new Error('feed 未在 500 页内收敛')
}

/**
 * 从数据库读"排除依赖目录后的 patch 字节数"。
 *
 * 为什么必须查库：feed 只交付 afterContent（完整文件），patch 大小（diff_text）不在接口里。
 * v0 的判据是 patch < 100KB，用内容总量冒充 patch 会把结论说大近一倍。
 * 口径与 `git diff` 一致：每个路径只看最终那一行的 diff_text。
 */
function measurePatchKb() {
  const env = {}
  for (const raw of readFileSync('D:/LabexAgent/.env', 'utf8').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const index = line.indexOf('=')
    if (index < 0) continue
    let value = line.slice(index + 1).trim()
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    env[line.slice(0, index).trim()] = value
  }
  const match = String(env.LABEX_AGENT_DB_URL ?? '').match(/jdbc:mysql:\/\/([^:/?]+)(?::(\d+))?\/([^?]+)/)
  if (!match) return null
  const [, host, port = '3306', database] = match
  const sql = `WITH ranked AS (
      SELECT relative_path, diff_text,
             ROW_NUMBER() OVER (PARTITION BY relative_path ORDER BY change_set_id DESC, update_time DESC, change_id DESC) rn
      FROM t_agent_file_change WHERE project_id = ${projectId})
    SELECT ROUND(SUM(CHAR_LENGTH(COALESCE(diff_text, ''))) / 1024, 1) FROM ranked WHERE rn = 1`
  try {
    const output = execFileSync('C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe', [
      '-h', host, '-P', port, '-u', env.LABEX_AGENT_DB_USERNAME, '-D', database, '-N', '-B', '-e', sql
    ], {
      env: { ...process.env, MYSQL_PWD: env.LABEX_AGENT_DB_PASSWORD },
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe']
    }).trim()
    const value = Number(output)
    return Number.isFinite(value) ? value : null
  } catch {
    return null
  }
}

/**
 * 期望的本地状态：与 changeSetSyncPlan 同源的判定
 *   1. 排除集合（syncExclusion）→ 不写；
 *   2. 二进制内容（含 NUL）→ 不写（服务端只存了有损文本）；
 *   3. 同路径多次变更 → 只保留最后一次（coalesceSteps）。
 * 存在性按 changeType 推导，不看内容是否为空串。
 */
function expectedLocalState(sets, isExcluded) {
  const byPath = new Map()
  for (const set of sets) {
    for (const file of set.files ?? []) {
      byPath.set(file.relativePath, file)
    }
  }
  const writes = new Map()
  const skipped = []
  for (const [path, file] of byPath) {
    if (isExcluded(path)) {
      skipped.push({ path, reason: 'excluded' })
      continue
    }
    if (file.changeType === 'delete') {
      writes.set(path, null)
      continue
    }
    const content = file.afterContent ?? ''
    if (content.includes('\u0000')) {
      skipped.push({ path, reason: 'binary-content' })
      continue
    }
    writes.set(path, content)
  }
  return { writes, skipped }
}

async function findBrowser() {
  for (const candidate of CHROME_CANDIDATES) {
    try {
      await access(candidate)
      return candidate
    } catch {}
  }
  throw new Error('未找到 Chrome/Edge，可用 ACCEPTANCE_CHROME_PATH 指定')
}

async function launch() {
  const executable = await findBrowser()
  profileDir = await mkdtemp(join(tmpdir(), 'labex-sync-v0-'))
  browser = spawn(executable, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--disable-background-networking',
    `--remote-debugging-port=${debugPort}`, `--user-data-dir=${profileDir}`, 'about:blank'
  ], { stdio: 'ignore', windowsHide: true })

  let page = null
  await waitFor(async () => {
    const targets = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json()
    page = targets.find(target => target.type === 'page' && target.webSocketDebuggerUrl)
    return Boolean(page)
  }, 'CDP 页面目标')

  cdp = await CdpClient.connect(page.webSocketDebuggerUrl, { timeoutMs: 20000 })
  await Promise.all([cdp.send('Page.enable'), cdp.send('Runtime.enable')])
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false
  })
}

/** 登录态 + 打开审查页 + 把目录选择入口接到真实 OPFS 目录句柄。 */
async function openReviewTab(auth) {
  await cdp.send('Page.navigate', { url: `${uiBase}/login` })
  await delay(2500)
  await cdp.evaluate(`(() => {
    localStorage.setItem('token', ${JSON.stringify(auth.token)});
    localStorage.setItem('userInfo', ${JSON.stringify(JSON.stringify(auth.userInfo))});
    return true;
  })()`)
  await cdp.send('Page.navigate', { url: `${uiBase}/workspace/${projectId}` })
  await waitFor(() => cdp.evaluate(`Boolean(document.querySelector('.ai-tab'))`), '工作区标签栏')
  await cdp.evaluate(`(() => {
    const tab = [...document.querySelectorAll('.ai-tab')].find(el => el.textContent.trim() === '审查');
    if (!tab) throw new Error('找不到「审查」标签');
    tab.click();
    return true;
  })()`)
  await waitFor(() => cdp.evaluate(`Boolean(document.querySelector('.sync-panel'))`), '同步面板')
  await installPicker()
}

/** 把 showDirectoryPicker 换成返回 OPFS 真实目录句柄；其余链路不动。 */
async function installPicker() {
  const name = await cdp.evaluate(`(async () => {
    const root = await navigator.storage.getDirectory();
    const dir = await root.getDirectoryHandle(${JSON.stringify(syncDirName)}, { create: true });
    window.showDirectoryPicker = async () => dir;
    window.__syncDir = dir;
    return dir.name;
  })()`)
  if (!name) throw new Error('安装目录选择器失败')
}

async function clearSyncDir() {
  return cdp.evaluate(`(async () => {
    const dir = window.__syncDir;
    const names = [];
    for await (const entry of dir.values()) names.push(entry.name);
    for (const name of names) await dir.removeEntry(name, { recursive: true });
    return names.length;
  })()`)
}

async function listSyncDir() {
  return cdp.evaluate(`(async () => {
    const out = {};
    const walk = async (handle, prefix) => {
      for await (const entry of handle.values()) {
        const path = prefix ? prefix + '/' + entry.name : entry.name;
        if (entry.kind === 'directory') await walk(entry, path);
        else out[path] = await (await entry.getFile()).text();
      }
    };
    await walk(window.__syncDir, '');
    return out;
  })()`)
}

async function writeSyncFile(path, content) {
  await cdp.evaluate(`(async () => {
    const segments = ${JSON.stringify(path)}.split('/');
    const name = segments.pop();
    let dir = window.__syncDir;
    for (const segment of segments) dir = await dir.getDirectoryHandle(segment, { create: true });
    const handle = await dir.getFileHandle(name, { create: true });
    const writable = await handle.createWritable();
    await writable.write(${JSON.stringify(content)});
    await writable.close();
    return true;
  })()`)
}

async function syncPanelState() {
  return cdp.evaluate(`(() => {
    const panel = document.querySelector('.sync-panel');
    if (!panel) return null;
    return {
      status: panel.querySelector('.sync-status-text')?.textContent.trim() ?? '',
      counts: panel.querySelector('.sync-status-counts')?.textContent.trim() ?? '',
      error: panel.querySelector('.sync-panel-error')?.textContent.trim() ?? '',
      busy: Boolean(panel.querySelector('.sync-btn.is-busy')),
      canRollback: [...panel.querySelectorAll('.sync-action')].some(el => el.textContent.includes('回退本次同步')),
      rows: [...panel.querySelectorAll('.sync-progress-row')].map(row => ({
        op: row.querySelector('.sync-progress-op')?.textContent.trim() ?? '',
        path: row.querySelector('.sync-progress-path')?.textContent.trim() ?? '',
        status: row.querySelector('.sync-progress-status')?.textContent.trim() ?? '',
        tone: [...row.classList].find(name => name.startsWith('is-')) ?? ''
      }))
    };
  })()`)
}

async function clickSync() {
  await cdp.evaluate(`(() => {
    const button = document.querySelector('.sync-btn');
    if (!button) throw new Error('找不到同步按钮');
    button.click();
    return true;
  })()`)
}

async function clickRollback() {
  await waitFor(async () => {
    const state = await syncPanelState()
    return state?.canRollback ? state : null
  }, '回退按钮可用')
  await cdp.evaluate(`(() => {
    const button = [...document.querySelectorAll('.sync-panel .sync-action')]
      .find(el => el.textContent.includes('回退本次同步'));
    button.click();
    return true;
  })()`)
}

/** 等面板状态文本落到目标集合之一，且不在忙。 */
async function waitForStatus(statuses, label) {
  return waitFor(async () => {
    const state = await syncPanelState()
    if (!state || state.busy) return null
    return statuses.some(status => state.status.includes(status)) ? state : null
  }, label)
}

async function choosePolicyAndRetry(policy) {
  await waitFor(() => cdp.evaluate(`Boolean(document.querySelector('.sync-conflict-dialog'))`), '冲突对话框')
  await cdp.evaluate(`(() => {
    const input = document.querySelector('.sync-policy-option input[value="${policy}"]');
    if (!input) throw new Error('找不到策略选项 ${policy}');
    input.click();
    return true;
  })()`)
  await delay(300)
  await cdp.evaluate(`(() => {
    const button = document.querySelector('.sync-dialog-btn.is-primary');
    if (!button) throw new Error('找不到重试按钮');
    button.click();
    return true;
  })()`)
}

function compareDir(actual, expected) {
  const problems = []
  for (const [path, content] of expected) {
    if (content === null) {
      if (path in actual) problems.push(`应删除却仍存在：${path}`)
      continue
    }
    if (!(path in actual)) {
      problems.push(`缺失：${path}`)
      continue
    }
    if (actual[path] !== content) {
      problems.push(`内容不一致：${path}（期望 ${content.length} 字符，实际 ${actual[path].length}）`)
    }
  }
  for (const path of Object.keys(actual)) {
    if (!expected.has(path)) problems.push(`多写了：${path}`)
  }
  return problems
}

async function main() {
  const auth = JSON.parse(await readFile(authFile, 'utf8'))
  token = auth.token

  console.log(`\n=== v0 验收：项目 ${projectId} ===\n`)

  // ── ① 云端供给与 patch 预算 ──────────────────────────────────
  const { sets, nextCursor } = await loadFeed()
  const fileCount = sets.reduce((sum, set) => sum + (set.files?.length ?? 0), 0)
  const { isExcludedPath } = await import('../../src/composables/sync/syncExclusion.js')
  const { writes, skipped } = expectedLocalState(sets, isExcludedPath)
  const patchKb = measurePatchKb()
  record('feed 可完整翻页', sets.length > 0 && Number(nextCursor.afterChangeSetId) > 0,
    `${sets.length} 个集合 / ${fileCount} 条变更 / 游标 ${nextCursor.afterChangeSetId}`)
  record('排除依赖目录后 patch < 预算', patchKb !== null && patchKb < patchBudgetKb,
    patchKb === null
      ? '读不到数据库，无法核对 patch 大小'
      : `${patchKb}KB / 上限 ${patchBudgetKb}KB，待写入 ${writes.size} 个路径，跳过 ${skipped.length} 个`)

  await launch()
  await openReviewTab(auth)
  await clearSyncDir()

  // ── ② 首次同步（空目录） ─────────────────────────────────────
  await clickSync()
  const first = await waitForStatus(['已完成', '存在冲突', '失败'], '首次同步结束')
  record('首次同步完成', first.status.includes('已完成'),
    `状态「${first.status}」${first.error ? ` 错误：${first.error}` : ''}${first.counts ? ` ${first.counts}` : ''}`)

  const afterFirst = await listSyncDir()
  const problems = compareDir(afterFirst, writes)
  record('写入内容与服务端逐字节一致', problems.length === 0,
    problems.length === 0 ? `${Object.keys(afterFirst).length} 个文件全部匹配` : problems.slice(0, 5).join('；'))

  const binarySkips = first.rows.filter(row => row.status.includes('二进制'))
  const expectedBinary = skipped.filter(item => item.reason === 'binary-content').map(item => item.path)
  record('二进制文件被跳过而非写坏', binarySkips.length === expectedBinary.length,
    expectedBinary.length === 0 ? '（本项目无二进制文件）' : `跳过 ${binarySkips.map(row => row.path).join(', ')}`)
  record('二进制文件确实没有落盘', expectedBinary.every(path => !(path in afterFirst)),
    expectedBinary.join(', ') || '（本项目无二进制文件）')

  // ── ③ 回退（空目录 → 全量撤销） ──────────────────────────────
  await clickRollback()
  await waitForStatus(['待同步'], '回退结束')
  const afterRollback = await listSyncDir()
  record('回退撤销全部写入', Object.keys(afterRollback).length === 0,
    `目录剩余 ${Object.keys(afterRollback).length} 个文件`)

  // ── ④ 脏目录：strict 整批拦截 ────────────────────────────────
  const targetPath = [...writes.keys()].find(path => writes.get(path) !== null)
  await writeSyncFile(targetPath, LOCAL_ONLY)
  await clickSync()
  const blocked = await waitForStatus(['存在冲突'], '脏目录拦截')
  const dirWhileBlocked = await listSyncDir()
  record('strict 策略整批拦截', blocked.status.includes('存在冲突'),
    `状态「${blocked.status}」${blocked.counts ? ` ${blocked.counts}` : ''}`)
  record('冲突文件的本地内容未被改动', dirWhileBlocked[targetPath] === LOCAL_ONLY)
  record('被拦截时零写入', Object.keys(dirWhileBlocked).length === 1,
    `目录内 ${Object.keys(dirWhileBlocked).length} 个文件（应只有手工放置的那一个）`)

  // ── ⑤ skip 策略 ──────────────────────────────────────────────
  await choosePolicyAndRetry('skip')
  const skippedRun = await waitForStatus(['已完成'], 'skip 策略同步结束')
  const afterSkip = await listSyncDir()
  // 冲突文件被跳过：它仍在本地，内容仍是用户那一份。
  const expectedSkip = new Map(writes)
  expectedSkip.set(targetPath, LOCAL_ONLY)
  const skipProblems = compareDir(afterSkip, expectedSkip)
  record('skip 策略写入其余文件并保留冲突文件', skipProblems.length === 0,
    skipProblems.length === 0
      ? `写入 ${Object.keys(afterSkip).length - 1} 个，冲突文件保持原样`
      : skipProblems.slice(0, 5).join('；'))
  record('skip 后冲突文件仍是本地版本', afterSkip[targetPath] === LOCAL_ONLY)
  record('skip 结果在面板上可见',
    skippedRun.rows.some(row => row.path === targetPath && row.op === '冲突'),
    skippedRun.counts || '（无计数）')

  // ── ⑥ 回退：只撤销本次写入，不碰用户文件 ────────────────────
  await clickRollback()
  await waitForStatus(['待同步'], '第二次回退结束')
  const afterSecondRollback = await listSyncDir()
  record('回退只撤销本次写入，保留用户自己的文件',
    Object.keys(afterSecondRollback).length === 1 && afterSecondRollback[targetPath] === LOCAL_ONLY,
    `目录内 ${Object.keys(afterSecondRollback).length} 个文件`)

  // ── ⑦ overwrite 策略 ─────────────────────────────────────────
  await clickSync()
  try {
    await waitForStatus(['存在冲突'], 'overwrite 前的冲突', 15000)
  } catch (error) {
    const state = await syncPanelState()
    console.error('诊断：点击同步后的面板状态 =', JSON.stringify(state, null, 2))
    throw error
  }
  await choosePolicyAndRetry('overwrite')
  await waitForStatus(['已完成'], 'overwrite 同步结束')
  const afterOverwrite = await listSyncDir()
  record('overwrite 策略用云端内容覆盖本地', afterOverwrite[targetPath] === writes.get(targetPath),
    afterOverwrite[targetPath] === writes.get(targetPath) ? '内容已替换为云端版本' : '内容仍是本地版本')

  // ── ⑧ 收敛 ───────────────────────────────────────────────────
  await clickSync()
  const converged = await waitForStatus(['已完成', '待同步'], '第二次同步结束')
  record('再次同步报告已是最新',
    converged.status.includes('已完成') && (converged.counts.includes('待写入 0') || converged.counts === ''),
    `状态「${converged.status}」${converged.counts ? ` ${converged.counts}` : ''}`)

  // ── 汇总 ─────────────────────────────────────────────────────
  const failed = results.filter(item => !item.ok)
  console.log(`\n${'='.repeat(56)}`)
  console.log(failed.length === 0
    ? `✅ v0 验收通过（${results.length} 项）`
    : `❌ v0 验收未通过：${failed.length}/${results.length} 项失败`)
  for (const item of failed) console.log(`   - ${item.name}${item.detail ? `：${item.detail}` : ''}`)
  console.log('')
  return failed.length === 0
}

let ok = false
try {
  ok = await main()
} catch (error) {
  console.error(`\n✗ 验收中断：${error.message}`)
  ok = false
} finally {
  try { cdp?.close() } catch {}
  try { browser?.kill() } catch {}
  process.exitCode = ok ? 0 : 1
}
