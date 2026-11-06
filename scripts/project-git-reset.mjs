#!/usr/bin/env node
/**
 * 丢弃项目里被污染的版本库，为"初始导入提交"让路。
 *
 * 背景：早期快照实现用 `git add -A` 且没有排除规则，把 node_modules / npm-cache / dist
 * 全提交了进去（项目 2：23153 个文件里只有 56 个是源码，磁盘上却有 76 个）。那批历史
 * 既不能 clone 也不能回滚到正确状态，只能丢弃。
 *
 * 这个脚本**不重放 change-set**：那会把 Agent 的改动变成 git 提交，与"Agent 改动不进 git"
 * 的设计前提冲突。它只做一件事——把 `.labex/git-snapshots` 改名备份，工作区文件一个不动。
 * 之后用户在前端源码管理面板里勾选文件、写提交信息，完成第一次提交。
 *
 * 用法：
 *   node scripts/project-git-reset.mjs --project=288            # 只看会发生什么
 *   node scripts/project-git-reset.mjs --project=288 --apply    # 真的执行
 *   node scripts/project-git-reset.mjs --all --apply            # 所有项目
 *
 * 幂等：仓库已经不存在时报告"无需处理"并退出 0，重复执行结果一致。
 */
import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync, renameSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const MYSQL = process.env.LABEX_MYSQL_BIN || 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'
const DB = process.env.LABEX_DB_NAME || 'labex_agent'
const DB_USER = process.env.LABEX_DB_USER || 'root'
const SNAPSHOT_DIR = path.join('.labex', 'git-snapshots')

function parseArgs(argv) {
  const options = { apply: false, all: false, projects: [] }
  for (const arg of argv) {
    if (arg === '--apply') options.apply = true
    else if (arg === '--all') options.all = true
    else if (arg.startsWith('--project=')) options.projects.push(Number(arg.slice('--project='.length)))
    else if (arg === '--help' || arg === '-h') {
      console.log('用法: node scripts/project-git-reset.mjs (--project=<id> | --all) [--apply]')
      process.exit(0)
    } else {
      console.error(`未知参数：${arg}`)
      process.exit(2)
    }
  }
  if (!options.all && options.projects.length === 0) {
    console.error('必须指定 --project=<id> 或 --all')
    process.exit(2)
  }
  if (options.projects.some(id => !Number.isInteger(id) || id <= 0)) {
    console.error('--project 需要正整数项目 ID')
    process.exit(2)
  }
  return options
}

function loadProjects(options) {
  const where = options.all ? '' : `WHERE project_id IN (${options.projects.join(',')})`
  const sql = `SELECT project_id, project_name, workspace_path FROM t_student_project ${where} ORDER BY project_id;`
  let output
  try {
    output = execFileSync(MYSQL, ['-u', DB_USER, '-D', DB, '-N', '-B', '-e', sql], {
      encoding: 'utf8',
      env: { ...process.env, MYSQL_PWD: process.env.MYSQL_PWD || 'yu6670980506' }
    })
  } catch (error) {
    console.error(`读取项目列表失败：${error.message}`)
    console.error('需要设置 MYSQL_PWD，或确认 mysql 客户端路径（LABEX_MYSQL_BIN）。')
    process.exit(1)
  }
  return output.split('\n').filter(line => line.trim()).map(line => {
    const [projectId, projectName, workspacePath] = line.split('\t')
    // Windows 上 mysql -B 的行尾是 \r\n；路径本身不需要反转义。
    return { projectId: Number(projectId), projectName, workspacePath: workspacePath.trim() }
  })
}

function inspect(project) {
  const workspace = path.resolve(project.workspacePath)
  const snapshotDir = path.join(workspace, SNAPSHOT_DIR)
  if (!existsSync(workspace)) {
    return { ...project, workspace, snapshotDir, action: 'skip', reason: '工作区不存在' }
  }
  if (!existsSync(path.join(snapshotDir, 'HEAD'))) {
    return { ...project, workspace, snapshotDir, action: 'skip', reason: '没有版本库' }
  }
  const commits = countCommits(snapshotDir)
  const tracked = countTracked(snapshotDir)
  if (commits === 0 && tracked === 0) {
    return { ...project, workspace, snapshotDir, action: 'skip', reason: '版本库是空的，无需重置' }
  }
  return { ...project, workspace, snapshotDir, action: 'reset', commits, tracked }
}

function countCommits(snapshotDir) {
  try {
    const output = execFileSync('git', ['--git-dir', snapshotDir, 'rev-list', '--count', '--all'], { encoding: 'utf8' })
    return Number(output.trim()) || 0
  } catch {
    return 0
  }
}

function countTracked(snapshotDir) {
  try {
    const output = execFileSync('git', ['--git-dir', snapshotDir, 'ls-files'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
    return output.split('\n').filter(Boolean).length
  } catch {
    return 0
  }
}

function backupPath(workspace) {
  const base = path.join(workspace, '.labex')
  const stamp = new Date().toISOString().replace(/[:.]/g, '-')
  let candidate = path.join(base, `git-snapshots-backup-${stamp}`)
  let suffix = 1
  while (existsSync(candidate)) {
    candidate = path.join(base, `git-snapshots-backup-${stamp}-${suffix++}`)
  }
  return candidate
}

function applyReset(target) {
  const destination = backupPath(target.workspace)
  renameSync(target.snapshotDir, destination)
  const size = directorySize(destination)
  return { destination, size }
}

function directorySize(directory) {
  let total = 0
  const stack = [directory]
  while (stack.length > 0) {
    const current = stack.pop()
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name)
      if (entry.isDirectory()) stack.push(full)
      else if (entry.isFile()) total += statSync(full).size
    }
  }
  return total
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

const options = parseArgs(process.argv.slice(2))
const projects = loadProjects(options)
if (projects.length === 0) {
  console.error('没有匹配的项目')
  process.exit(1)
}

const targets = projects.map(inspect)
let changed = 0
for (const target of targets) {
  if (target.action === 'skip') {
    console.log(`跳过 ${target.projectId} ${target.projectName}：${target.reason}`)
    continue
  }
  console.log(`重置 ${target.projectId} ${target.projectName}：${target.commits} 条提交 / ${target.tracked} 个跟踪文件`)
  console.log(`  ${target.snapshotDir}`)
  if (!options.apply) continue
  const result = applyReset(target)
  changed += 1
  console.log(`  已备份到 ${result.destination}（${formatBytes(result.size)}）`)
}

if (!options.apply) {
  console.log('')
  console.log('这是预演。加 --apply 才会真的执行。工作区文件不会被修改。')
  process.exit(0)
}

console.log('')
console.log(changed === 0
  ? '没有需要重置的版本库。'
  : `已重置 ${changed} 个版本库。下一步：在源码管理面板里勾选文件并提交，完成初始导入。`)
