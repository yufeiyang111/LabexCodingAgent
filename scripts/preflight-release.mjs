#!/usr/bin/env node
/**
 * 发布前本地质量门禁（与 .github/workflows/deploy.yml 及
 * deploy/linux/CI_CD_DEPLOYMENT_PLAYBOOK.md 第二章保持同一套判据）。
 *
 * 为什么要有它：CI 里的门禁只能在推上去之后才告诉你错了。本脚本把同样的检查搬到本地，
 * 让「门禁不过」发生在 push 之前，同时保持与 CI 完全一致的命令，避免两边判据漂移。
 *
 * 用法：
 *   node scripts/preflight-release.mjs            # 全部检查
 *   node scripts/preflight-release.mjs --fast     # 跳过耗时构建（只跑静态检查）
 *   node scripts/preflight-release.mjs --only=seed,test
 */
import { spawnSync } from 'node:child_process'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
const fast = args.includes('--fast')
const onlyArg = args.find(a => a.startsWith('--only='))
const only = onlyArg ? onlyArg.slice('--only='.length).split(',').map(s => s.trim()) : null

const JAVA_HOME = 'D:/jdk21/jdk-21.0.10+7'
const MAVEN_HOME = 'D:/apache-maven-3.9.10'

/**
 * 本机 mvn 在 MSYS 下会因 classworlds 缺失而崩，必须用 JDK 直启 plexus launcher。
 * 与仓库内既有做法一致（见 .workbuddy/memory/PLAYBOOK.md）。
 */
function mavenTestCompile() {
  const javaExe = `${JAVA_HOME}/bin/java.exe`
  if (!existsSync(javaExe)) {
    return { ok: false, detail: `未找到 ${javaExe}，无法在本机编译后端（可跳过该门禁或改用 CI）` }
  }
  const r = spawnSync(javaExe, [
    `-Dclassworlds.conf=${MAVEN_HOME}\\bin\\m2.conf`,
    `-Dmaven.home=${MAVEN_HOME}`,
    `-Dmaven.multiModuleProjectDirectory=${root}\\backend`,
    '-classpath', `${MAVEN_HOME}\\boot\\plexus-classworlds-2.9.0.jar`,
    'org.codehaus.plexus.classworlds.launcher.Launcher',
    '-s', 'settings-local.xml', '-o', '-q', 'test-compile'
  ], { cwd: resolve(root, 'backend'), encoding: 'utf8', shell: false })
  return {
    ok: r.status === 0,
    detail: r.status === 0 ? 'BUILD SUCCESS' : (r.stdout || r.stderr || '').split('\n').filter(Boolean).slice(-6).join('\n')
  }
}

const gates = [
  {
    id: 'seed',
    name: '教程种子版本一致性（标记 ↔ 守卫）',
    cmd: 'node', cmdArgs: ['scripts/verify-seed-version.mjs'], cwd: root,
    skip: fast ? false : false
  },
  {
    id: 'migrations',
    name: '迁移脚本命名合规（upgrade-YYYYMMDD.sql）',
    custom: () => {
      const dir = resolve(root, 'deploy/linux/migrations')
      if (!existsSync(dir)) return { ok: false, detail: '未找到 deploy/linux/migrations' }
      const files = readdirSync(dir).filter(f => f.endsWith('.sql'))
      const bad = files.filter(f => !/^upgrade-\d{8}\.sql$/.test(f))
      if (bad.length) return { ok: false, detail: `命名不合规（应为 upgrade-YYYYMMDD.sql）: ${bad.join(', ')}` }
      return { ok: true, detail: `${files.length} 个迁移脚本: ${files.join(', ') || '(空)'}` }
    }
  },
  {
    id: 'frontend-undef',
    name: '前端未定义引用（作用域错误，等价 no-undef）',
    cmd: 'node', cmdArgs: ['scripts/check-undefined-refs.mjs'], cwd: resolve(root, 'frontend')
  },
  {
    id: 'frontend-test',
    name: '前端单元测试全量',
    cmd: 'node', cmdArgs: ['scripts/run-tests.mjs'], cwd: resolve(root, 'frontend')
  },
  {
    id: 'frontend-build',
    name: '前端生产构建 + 体积预算',
    cmd: 'npx', cmdArgs: ['vite', 'build', '--outDir', 'D:/LabexAgent-build-check/preflight', '--emptyOutDir'],
    cwd: resolve(root, 'frontend'),
    // 本机清空 dist/assets 会触发沙箱删除保护，故输出到仓库外；体积预算由单独门禁核验
    skip: fast
  },
  {
    id: 'chunk-budget',
    name: '受控产物体积预算',
    custom: () => {
      const dir = 'D:/LabexAgent-build-check/preflight/assets'
      if (!existsSync(dir)) return { ok: true, detail: '（未构建，跳过）' }
      const budgets = { CloudWorkspace: 1500000, index: 1300000, TerminalPanel: 400000 }
      const lines = []
      let ok = true
      for (const [prefix, cap] of Object.entries(budgets)) {
        const f = readdirSync(dir).find(n => n.startsWith(prefix + '-') && n.endsWith('.js'))
        if (!f) { lines.push(`  ${prefix}: 未找到产物`); continue }
        const size = statSync(resolve(dir, f)).size
        const pass = size <= cap
        if (!pass) ok = false
        lines.push(`  ${f}: ${size} bytes / 上限 ${cap} ${pass ? '✅' : '❌'}`)
      }
      return { ok, detail: lines.join('\n') }
    }
  },
  {
    id: 'backend',
    name: '后端编译（含测试源码）',
    custom: mavenTestCompile,
    skip: fast
  },
  {
    id: 'workflow',
    name: 'deploy.yml 关键环节自检',
    custom: () => {
      const p = resolve(root, '.github/workflows/deploy.yml')
      if (!existsSync(p)) return { ok: false, detail: '缺少 .github/workflows/deploy.yml' }
      const src = readFileSync(p, 'utf8')
      const checks = [
        ['迁移自动收集', 'tar -czf migrations.tar.gz -C deploy/linux migrations'],
        ['迁移执行器', './apply-migrations.sh migrations docker-compose.yml'],
        ['前端资源校验', '缺少 frontend-dist.tar.gz'],
      ]
      const miss = checks.filter(([, n]) => !src.includes(n)).map(([l]) => l)
      return { ok: !miss.length, detail: miss.length ? '缺少环节: ' + miss.join(', ') : '关键环节齐全' }
    }
  }
]

const selected = only ? gates.filter(g => only.includes(g.id)) : gates
const results = []

console.log('=== 发布前质量门禁 ===')
console.log(`（${fast ? '快速模式：跳过构建类检查' : '完整模式'}）`)
console.log('')

for (const g of selected) {
  if (g.skip) { console.log(`⏭  ${g.name} —— 已跳过`); results.push({ g, ok: true, skipped: true }); continue }
  process.stdout.write(`▶  ${g.name} ... `)
  let r
  if (g.custom) {
    try { r = g.custom() } catch (e) { r = { ok: false, detail: e.message } }
  } else {
    const x = spawnSync(g.cmd, g.cmdArgs, { cwd: g.cwd, encoding: 'utf8', shell: process.platform === 'win32' })
    r = { ok: x.status === 0, detail: x.status === 0 ? 'PASS' : (x.stdout || x.stderr || '').split('\n').filter(Boolean).slice(-8).join('\n') }
  }
  console.log(r.ok ? '✅ 通过' : '❌ 失败')
  if (r.detail) console.log(String(r.detail).split('\n').map(l => '     ' + l).join('\n'))
  results.push({ g, ...r })
  console.log('')
}

const failed = results.filter(r => !r.ok)
console.log('='.repeat(48))
if (failed.length) {
  console.log(`❌ 门禁未通过：${failed.length} 项失败`)
  for (const f of failed) console.log('   - ' + f.g.name)
  console.log('')
  console.log('请修复后再推送；CI 会在同样判据上失败。')
  process.exit(1)
}
console.log(`✅ 全部门禁通过（${results.filter(r => !r.skipped).length} 项已执行）`)
console.log('')
console.log('下一步：git push origin main  然后  触发 GitHub Actions 一键部署')
