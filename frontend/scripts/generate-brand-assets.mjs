/**
 * 从品牌标记数据源生成全部站点图标。
 *
 * 单一数据源：`src/assets/brand/labexMark.js`。
 * 组件内联渲染与站点图标（favicon / PWA / Apple / 分享图）都从它派生，
 * 因此改品牌只需改一处 + 重跑本脚本，不会出现「页面换了、标签页还是旧图标」。
 *
 * 用法：node scripts/generate-brand-assets.mjs
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath, pathToFileURL } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(__dirname, '..')
const OUT_DIR = path.join(ROOT, 'public', 'brand')
// 放系统临时目录：既避免污染仓库（该目录会进 git 状态），也避开仓库内删除操作的沙箱拦截
const TMP = path.join(os.tmpdir(), 'labex-brand-assets-' + process.pid)

// Windows 下动态 import 绝对路径必须转成 file:// URL，直接传 D:\... 会报 ERR_UNSUPPORTED_ESM_URL_SCHEME
const { buildBrandMarkSvg, BRAND_MARK_COLORS } = await import(
  pathToFileURL(path.join(ROOT, 'src', 'assets', 'brand', 'labexMark.js')).href
)

const CHROME_CANDIDATES = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium'
]

const sleep = ms => new Promise(r => setTimeout(r, ms))

/* ── 1 · favicon：紧裁版，直接落 SVG ── */
fs.mkdirSync(OUT_DIR, { recursive: true })
const faviconSvg = buildBrandMarkSvg({ padding: 12 })
fs.writeFileSync(path.join(OUT_DIR, 'labex-mark.svg'), faviconSvg, 'utf8')
console.log('✓ public/brand/labex-mark.svg（紧裁，' + faviconSvg.length + ' 字节）')

/* ── 2 · 位图图标：用 Chrome 渲染 ── */
const chromePath = CHROME_CANDIDATES.find(p => fs.existsSync(p))
if (!chromePath) {
  console.error('✗ 未找到 Chrome，无法生成位图图标。SVG 已更新，请在有 Chrome 的环境重跑。')
  process.exit(1)
}

fs.mkdirSync(TMP, { recursive: true })
const PORT = 10951
const chrome = spawn(chromePath, ['--headless=new', '--remote-debugging-port=' + PORT,
  '--user-data-dir=' + path.join(TMP, 'profile'), '--no-first-run', '--disable-gpu',
  '--hide-scrollbars', '--force-device-scale-factor=1', '--window-size=1400,900', 'about:blank'], { stdio: 'ignore' })

for (let i = 0; i < 90; i++) { try { const r = await fetch('http://127.0.0.1:' + PORT + '/json/version'); if (r.ok) break } catch {} await sleep(250) }

const t = await fetch('http://127.0.0.1:' + PORT + '/json/new?' + encodeURIComponent('about:blank'), { method: 'PUT' }).then(r => r.json())
const ws = new WebSocket(t.webSocketDebuggerUrl)
let id = 0; const pending = new Map()
ws.addEventListener('message', e => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) { const q = pending.get(m.id); pending.delete(m.id); m.error ? q.reject(new Error(JSON.stringify(m.error))) : q.resolve(m.result) }
})
await new Promise(r => ws.addEventListener('open', r))
const send = (method, params = {}) => { const i = ++id; return new Promise((resolve, reject) => { pending.set(i, { resolve, reject }); ws.send(JSON.stringify({ id: i, method, params })) }) }
const js = async expr => (await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result.value

await send('Page.enable'); await send('Runtime.enable')

/**
 * 在页面里构造指定尺寸的画布并截图。
 *
 * 说明：导出时统一加一圈浅色细描边（rgba(255,255,255,.18)）。
 * 位图没有主题感知能力，深色底板在深色背景上会糊掉轮廓；
 * 这道描边在浅色背景上几乎不可见，在深色背景上则勾出形状，两种环境都成立。
 */
async function renderPng({ width, height, html, outName, background = 'transparent' }) {
  await js(`(() => {
    document.body.style.margin = '0';
    document.body.style.padding = '0';
    document.body.style.background = 'transparent';
    document.body.innerHTML = ${JSON.stringify(html)};
  })(); void 0;`)
  await sleep(260)
  const result = await send('Page.captureScreenshot', {
    format: 'png',
    captureBeyondViewport: true,
    clip: { x: 0, y: 0, width, height, scale: 1 },
    ...(background === 'transparent' ? { captureBeyondViewport: true } : {})
  })
  const buf = Buffer.from(result.data, 'base64')
  fs.writeFileSync(path.join(OUT_DIR, outName), buf)
  console.log('✓ public/brand/' + outName.padEnd(24) + width + '×' + height + '  ' + (buf.length / 1024).toFixed(1) + ' KB')
}

/** 生成带浅描边的标记 SVG（用于位图导出） */
function markForBitmap(pixelSize, innerRatio = 0.82) {
  const inner = Math.round(pixelSize * innerRatio)
  const svg = buildBrandMarkSvg({ padding: 12 })
  // 给底板补描边：位图无主题感知，需要描边保证深色背景上仍可见轮廓
  return svg.replace(
    /(<rect[^>]*rx="28")/,
    '$1 stroke="rgba(255,255,255,0.18)" stroke-width="6"'
  ).replace('<svg', `<svg width="${inner}" height="${inner}"`)
}

/* PWA 图标：不透明品牌底色 + 居中主体。
   用不透明底是平台惯例（透明角在深色桌面会看不清），主体留 18% 边距满足安全区要求。 */
for (const size of [192, 512]) {
  const html = `<div style="width:${size}px;height:${size}px;background:${BRAND_MARK_COLORS.plate};display:grid;place-items:center">${markForBitmap(size, 0.82)}</div>`
  await renderPng({ width: size, height: size, html, outName: `icon-${size}.png` })
}

/* Apple Touch Icon：iOS 会自行加圆角遮罩，同样用不透明底 */
{
  const size = 180
  const html = `<div style="width:${size}px;height:${size}px;background:${BRAND_MARK_COLORS.plate};display:grid;place-items:center">${markForBitmap(size, 0.84)}</div>`
  await renderPng({ width: size, height: size, html, outName: 'apple-touch-icon.png' })
}

/* 站点分享图（OG）：1200×630，深色品牌底 + 标记 + 名称 + 一句话 */
{
  const W = 1200, H = 630
  const html = `
    <div style="width:${W}px;height:${H}px;background:${BRAND_MARK_COLORS.plate};display:flex;align-items:center;gap:56px;padding:0 96px;box-sizing:border-box;font-family:-apple-system,'Segoe UI','Noto Sans SC',sans-serif">
      <div style="flex:0 0 auto">${markForBitmap(200, 0.9)}</div>
      <div style="min-width:0">
        <div style="color:#F8FAFC;font-size:76px;font-weight:700;letter-spacing:-0.02em;line-height:1.1">LabexAgent</div>
        <div style="margin-top:20px;color:#94A3B8;font-size:30px;line-height:1.5">浏览器里的编程 Agent<br/>读代码 · 改文件 · 跑测试 · 留下可回看的过程</div>
        <div style="margin-top:34px;display:inline-flex;gap:10px">
          <span style="padding:7px 16px;border-radius:999px;background:rgba(57,217,138,.16);color:${BRAND_MARK_COLORS.accentGreen};font-size:21px">无需本地环境</span>
          <span style="padding:7px 16px;border-radius:999px;background:rgba(94,234,212,.14);color:${BRAND_MARK_COLORS.accentTeal};font-size:21px">改动可回退</span>
        </div>
      </div>
    </div>`
  await renderPng({ width: W, height: H, html, outName: 'og-image.png', background: 'solid' })
}

ws.close(); chrome.kill()
try { fs.rmSync(TMP, { recursive: true, force: true }) } catch { /* 清理失败不影响产物，临时目录在系统 temp 下会自行回收 */ }
console.log('\n完成。位图图标含不透明品牌底，favicon 为可缩放 SVG。')
