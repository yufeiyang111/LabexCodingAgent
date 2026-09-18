import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthModalStore, AUTH_MODES } from '../../stores/authModal.js'

const authDirectory = dirname(fileURLToPath(import.meta.url))
const sourceRoot = join(authDirectory, '..', '..')
const directory = authDirectory

async function readSource(...segments) {
  return readFile(join(...segments), 'utf8')
}

function freshStore() {
  setActivePinia(createPinia())
  return useAuthModalStore()
}

/* ==========================================================================
   1 · 弹窗状态机：打开 / 关闭 / 登录注册互切
   ========================================================================== */

test('auth modal store opens closed by default so no auth form is rendered up front', () => {
  const store = freshStore()
  assert.equal(store.visible, false, '默认必须是关闭状态，否则登录组件会随页面常驻')
  assert.equal(store.mode, AUTH_MODES.login)
})

test('auth modal store opens in the requested mode and can be closed again', () => {
  const store = freshStore()

  store.open('register')
  assert.equal(store.visible, true)
  assert.equal(store.mode, AUTH_MODES.register)

  store.close()
  assert.equal(store.visible, false)
  assert.equal(store.mode, AUTH_MODES.register, '关闭不应丢弃模式，复位由弹窗关闭回调负责')
})

test('auth modal store defaults to login and rejects unknown modes', () => {
  const store = freshStore()

  store.open()
  assert.equal(store.mode, AUTH_MODES.login)

  store.open('nonsense')
  assert.equal(store.mode, AUTH_MODES.login, '非法模式必须回落到登录，避免出现第三套状态字面量')
})

test('auth modal store toggles between login and register both ways', () => {
  const store = freshStore()

  store.toggleMode()
  assert.equal(store.mode, AUTH_MODES.register)

  store.toggleMode()
  assert.equal(store.mode, AUTH_MODES.login)
})

/* ==========================================================================
   2 · 按需挂载：认证组件不得常驻
   ========================================================================== */

test('auth components mount only while the modal is open', async () => {
  const modal = await readSource(directory, 'AuthModal.vue')

  assert.match(modal, /v-if="authModal\.visible"/, '认证组件必须由 v-if 控制，关闭时销毁而非常驻')
  assert.match(modal, /<el-dialog[\s\S]*?append-to-body/, '弹窗必须 append-to-body，脱离页面层叠上下文')
  assert.match(modal, /<el-dialog[\s\S]*?align-center/, '弹窗必须垂直居中')
  assert.match(modal, /variant="inline"/, '弹窗内的认证容器必须使用内嵌形态')
  assert.match(modal, /v-model:mode="authModal\.mode"/, '模式必须受控于 store，保证单一事实源')
  assert.match(modal, /@succeeded="onAuthenticated"/, '认证成功必须回调宿主决定落位')
  assert.match(modal, /@closed="onDialogClosed"/, '关闭后必须复位模式，避免残留上一次的注册态')
})

test('modal close button and click-outside both route through the store', async () => {
  const modal = await readSource(directory, 'AuthModal.vue')

  assert.match(modal, /@click="authModal\.close\(\)"/, '关闭按钮必须调用 store 的 close')
  assert.match(modal, /@update:model-value="onDialogValueChange"/, '遮罩/ESC 关闭必须同步回 store')
})

test('App mounts the auth modal host but not the auth form itself', async () => {
  const app = await readSource(sourceRoot, 'App.vue')

  assert.match(app, /<AuthModal \/>/, 'App 必须挂载登录弹窗宿主')
  assert.doesNotMatch(app, /<LoginForm/, 'App 不得直接渲染认证表单')
  assert.doesNotMatch(app, /<Auth\s/, 'App 不得直接渲染认证容器')
})

/* ==========================================================================
   3 · 单一登录逻辑：整页与弹窗复用同一容器
   ========================================================================== */

test('Auth container serves both page and inline variants from one implementation', async () => {
  const auth = await readSource(sourceRoot, 'views', 'Auth.vue')

  assert.match(auth, /variant: \{ type: String, default: 'page' \}/)
  assert.match(auth, /const isPage = computed\(\(\) => props\.variant !== 'inline'\)/)
  assert.match(auth, /:variant="variant"/, '必须把形态透传给布局容器')

  // 受控 / 非受控两用：不传 mode 时行为与改造前一致
  assert.match(auth, /isModeControlled/)
  assert.match(auth, /emit\('update:mode', value\)/)
  assert.match(auth, /emit\('succeeded'\)/)

  // 整页形态保留视口与 OAuth 回调；弹窗形态不得触碰 document
  assert.match(auth, /if \(isPage\.value\) enableAuthViewport\(\)/)
  assert.match(auth, /if \(viewportEnabled\) restoreViewport\(\)/)
  assert.match(auth, /if \(isPage\.value\) await handleOAuthCallback\(\)/)
})

test('auth config failure keeps cached capabilities instead of blanking third-party login', async () => {
  // 认证能力（邀请码开关 / 第三方登录列表）已收敛到 authCapabilities store 单一持有：
  // 落地页 CTA 与认证表单都要读它，各自请求会造成重复请求与状态分叉。
  const store = await readSource(sourceRoot, 'stores', 'authCapabilities.js')

  // 第三方登录列表与邀请码开关都由后端 /auth/config 下发。若请求失败就清空，
  // 一次后端抖动或重启会让整个第三方登录区消失，用户无法区分「被下线」与「暂时不可用」。
  assert.match(store, /writeCachedCapabilities\(/, '成功取到配置时必须写入会话缓存')
  assert.match(store, /readCachedCapabilities\(\)/, '失败时必须尝试回落到缓存')
  assert.match(store, /fallback\?\.inviteCodeEnabled \?\? false/, '邀请码开关应回落到缓存值')
  assert.match(store, /Array\.isArray\(fallback\?\.oauthProviders\)/, 'OAuth 列表应回落到缓存值')

  // 锁死反例：回落分支不得出现无依据的清空
  const fallbackBlock = store.slice(store.indexOf('function fallbackToCache'))
  const fallbackBody = fallbackBlock.slice(0, fallbackBlock.indexOf('\n  }'))
  assert.doesNotMatch(fallbackBody, /oauthProviders\.value = \[\]/,
    '请求失败时不得直接把 OAuth 列表清空')
  assert.doesNotMatch(fallbackBody, /inviteCodeEnabled\.value = false/,
    '请求失败时不得直接把邀请码开关判为 false')
})

test('auth page falls back to the home page, never auto-redirects to the login page', async () => {
  const [loginWrapper, router] = await Promise.all([
    readSource(sourceRoot, 'views', 'Login.vue'),
    readSource(sourceRoot, 'router', 'index.js')
  ])

  assert.match(loginWrapper, /<Auth \/>/, '/login 的入口写法必须保持，否则 OAuth 回调与深链失效')
  assert.match(router, /path: '\/login'/, 'OAuth 回调固定回落到 /login，该路由必须保留')

  // 落点契约（产品要求）：登录页只作为子页面，不作为任何自动跳转的目标。
  // 未登录撞到受保护路由时回落首页，由用户在首页主动进入登录。
  assert.match(router, /next\('\/'\)/, '未登录访问受保护路由必须回落首页')
  assert.doesNotMatch(router, /next\('\/login'\)/, '不得再自动跳转到登录页')
})

test('AuthLayout supports an inline variant without the full-page brand column', async () => {
  const layout = await readSource(directory, 'AuthLayout.vue')

  assert.match(layout, /variant: \{ type: String, default: 'page' \}/)
  assert.match(layout, /auth-module-layout--inline/)
  assert.match(layout, /<aside v-if="!isInline" class="auth-module-layout__brand">/, '内嵌形态不得再渲染整页品牌叙事栏')
})

test('inline auth flattens the card so it does not nest a second frame inside the dialog', async () => {
  const auth = await readSource(sourceRoot, 'views', 'Auth.vue')

  const inlineBlock = auth.slice(auth.indexOf('.auth-canvas--inline .auth-card'))
  assert.ok(inlineBlock.length > 0, '必须存在内嵌形态的卡片覆盖规则')
  assert.match(inlineBlock, /border: 0;/, '内嵌时卡片边框必须归零，避免与 el-dialog 形成框套框')
  assert.match(inlineBlock, /box-shadow: none;/, '内嵌时卡片阴影必须归零')
  assert.match(inlineBlock, /transform: none;/, '内嵌时不得再对卡片做指针位移')
  assert.match(auth, /\.auth-canvas--inline \.auth-card::after \{ display: none; \}/, '内嵌时必须关闭指针跟随光层')
})

/* ==========================================================================
   4 · 登录入口：只有用户主动点击才唤起
   ========================================================================== */

test('auth entry button opens the modal instead of rendering the form inline', async () => {
  const entry = await readSource(directory, 'AuthEntryButton.vue')

  assert.match(entry, /useAuthModalStore/)
  assert.match(entry, /authModal\.open\('login'\)/)
  assert.match(entry, /authModal\.open\('register'\)/)
  assert.doesNotMatch(entry, /LoginForm/, '入口按钮不得内联渲染认证表单')
  assert.match(entry, /userStore\.isLoggedIn/, '已登录时不得再提示登录')
})

test('public pages prompt auth through the modal rather than a hard redirect', async () => {
  const tutorials = await readSource(sourceRoot, 'views', 'Tutorials.vue')

  assert.match(tutorials, /<AuthEntryButton[\s\S]*?primary/, '教程页顶栏必须提供登录入口')
  assert.match(tutorials, /authModal\.open\('login'\)/, '返回按钮在未登录时应唤起弹窗而非整页跳转')
  assert.doesNotMatch(tutorials, /router\.push\(\{ name: userStore\.isLoggedIn \? 'Projects' : 'Login' \}\)/)
})
