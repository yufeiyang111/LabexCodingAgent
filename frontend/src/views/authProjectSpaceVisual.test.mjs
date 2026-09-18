import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const viewDirectory = dirname(fileURLToPath(import.meta.url))
const sourceRoot = join(viewDirectory, '..')

async function readSource(...segments) {
  return readFile(join(sourceRoot, ...segments), 'utf8')
}

test('Auth uses a crisp split wabi-sabi AI coding-agent layout', async () => {
  const source = await readSource('views', 'Auth.vue')
  const styles = await readSource('styles', 'tailwind.css')

  assert.match(source, /auth-layout/)
  assert.match(source, /auth-narrative/)
  assert.match(source, /lg:grid-cols-\[minmax\(0,1fr\)_460px\]/)
  assert.match(source, /auth-card--expanded/)
  assert.match(source, /handleCardPointerMove/)
  assert.match(source, /\u667a\u80fd\u7f16\u7a0b\u5de5\u4f5c\u95f4/)
  assert.match(source, /\u7406\u89e3\u4ed3\u5e93/)
  assert.match(source, /\u89c4\u5212\u6539\u52a8/)
  assert.match(source, /\u9a8c\u8bc1\u7ed3\u679c/)
  assert.doesNotMatch(source, /SocialLogin/)
  assert.doesNotMatch(styles, /filter:\s*blur/)
  assert.match(styles, /max-width: calc\(100vw - 40px\);/)
})

test('authentication uses typed Chinese brand and form components', async () => {
  const [logo, loginForm, loginWrapper, authField] = await Promise.all([
    readSource('components', 'Logo.vue'),
    readSource('components', 'LoginForm.vue'),
    readSource('views', 'Login.vue'),
    readSource('components', 'auth', 'AuthField.vue'),
  ])

  assert.match(logo, /<script setup lang="ts">/)
  assert.match(logo, /\u667a\u80fd\u4ee3\u7801\u5de5\u4f5c\u95f4/)
  assert.match(loginForm, /<script setup lang="ts">/)
  assert.match(loginForm, /\u7528\u6237\u540d/)
  assert.match(loginWrapper, /<Auth \/>/)

  // 焦点边框的品牌色契约：换肤后从苔绿硬编码改为跟随主题令牌，
  // 断言真实的焦点样式而非注释文案 —— 注释改了不影响行为，样式改了才是真回归。
  assert.match(authField, /input:focus\s*\{[^}]*border-color:\s*var\(--theme-accent\)/,
    '输入框聚焦必须使用主题强调色，且用变量以适配暗色主题')
  assert.doesNotMatch(authField, /#5D675B|rgba?\(\s*93\s*,\s*103\s*,\s*91/,
    '不得残留暖纸色时代的苔绿聚焦色')
})

test('Tailwind keeps the auth design tokens and utilities above the legacy reset', async () => {
  const source = await readSource('styles', 'tailwind.css')

  // 换肤：登录页从暖纸色（wabi-sabi）改为冷色石板 + 靛蓝，令牌随之更新。
  // 断言仍然锁定「主题令牌存在且值为冷色」，防止未来被误改回暖纸色。
  assert.match(source, /--color-auth-background: #f7f8fb;/)
  assert.match(source, /--color-auth-accent: #4f46e5;/)
  assert.match(source, /auth-paper-grain/)
  assert.doesNotMatch(source, /#EEE9DE|#FBF8F0|#5D675B|#CFC8BA|#987562/i,
    '不得残留暖纸色调色板')
  assert.match(source, /@import "tailwindcss\/utilities\.css";/)
  assert.doesNotMatch(source, /layer\(utilities\)/)
})

test('CloudSpace project list sidebar supports smooth resizing and width persistence', async () => {
  const cloudSpace = await readSource('views', 'CloudSpace.vue')

  assert.match(cloudSpace, /class="cs-resize-handle"/, '项目列表页必须包含侧边栏拉伸手柄')
  assert.match(cloudSpace, /@pointerdown="startSidebarResize"/, '手柄必须绑定指针事件启动拉伸')
  assert.match(cloudSpace, /--cs-sidebar-w/, '侧边栏必须由 CSS变量驱动以实现高性能 60fps 跟手')
  assert.match(cloudSpace, /labex_projects_sidebar_width/, '必须持久化用户偏好侧边栏宽度')
  assert.match(cloudSpace, /requestAnimationFrame/, '必须使用 rAF 节流保证丝滑流畅')
  assert.match(cloudSpace, /\.cs-left\.is-resizing[\s\S]*transition:\s*none\s*!important/, '拖拽时必须彻底禁用侧边栏过渡动画以保证跟手流畅')
  assert.match(cloudSpace, /\.cs-right[\s\S]*contain:\s*layout paint/, '右侧面板必须开启 layout paint 隔离避免重排扩散')
})

test('CloudSpace sidebar elements prevent text breaking and adapt smoothly when squeezed', async () => {
  const cloudSpace = await readSource('views', 'CloudSpace.vue')

  assert.match(cloudSpace, /\.cs-panel-header h2\s*\{[^}]*white-space:\s*nowrap/, '项目列表标题必须禁止折行')
  assert.match(cloudSpace, /\.cs-btn\s*\{[^}]*white-space:\s*nowrap/, '操作按钮必须禁止折行竖排')
  assert.match(cloudSpace, /\.cs-item-meta\s*\{[^}]*white-space:\s*nowrap/, '项目文件数量 meta 必须禁止换行与竖排')
  assert.match(cloudSpace, /container-type:\s*inline-size/, '侧边栏必须声明容器查询以响应窄宽度')
  assert.match(cloudSpace, /@container\s*\(max-width:\s*225px\)/, '极窄侧边栏下按钮文字必须平滑降级自适应')
})

test('UserDetailButton account settings dialog appends to body and aligns to viewport center', async () => {
  const userDetail = await readSource('components', 'cloud', 'UserDetailButton.vue')

  assert.match(userDetail, /<el-dialog[\s\S]*?append-to-body/, '账号设置弹窗必须 append-to-body，脱离侧边栏 contain 限制以居中于屏幕')
  assert.match(userDetail, /<el-dialog[\s\S]*?align-center/, '账号设置弹窗必须设置 align-center 垂直居中')
})
