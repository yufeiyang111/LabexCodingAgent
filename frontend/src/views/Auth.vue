<template>
  <!-- 智能编程工作间：理解仓库、规划改动、验证结果 -->
  <AuthLayout
    class="auth-layout"
    :class="isPage ? 'auth-canvas lg:grid-cols-[minmax(0,1fr)_460px]' : 'auth-canvas--inline'"
    :variant="variant"
  >
    <template v-if="isPage" #brand>
      <AuthBrandPanel class="auth-narrative" />
    </template>

    <template #form>
      <div class="auth-form-column">
        <div v-if="isPage" class="auth-form-column__topline">
          <span>LabexAgent / {{ mode === 'login' ? '登录' : (inviteCodeEnabled ? '邀请码注册' : '注册') }}</span>
          <button type="button" @click="toggleMode">{{ mode === 'login' ? (inviteCodeEnabled ? '使用邀请码' : '创建工作间') : '返回登录' }}</button>
        </div>

        <AuthFormContainer
          :title="mode === 'login' ? '进入你的工作间' : (inviteCodeEnabled ? '凭邀请码创建工作间' : '创建一处工作间')"
          :subtitle="mode === 'login' ? '让 Agent 帮你理解仓库、规划改动，并把验证结果留在清楚的工作流里。' : (inviteCodeEnabled ? '输入由管理员分发的专属邀请码，开启你的智能编程工作间。' : '从项目、模型配置到 Agent 会话，逐步建立属于你的开发工作流。')"
        >
          <article
            class="auth-card auth-card--expanded"
            :class="{ 'auth-card--active': isCardActive }"
            :style="cardStyle"
            @pointermove="handleCardPointerMove"
            @pointerenter="isCardActive = true"
            @pointerleave="handleCardPointerLeave"
          >
            <LoginForm
              :mode="mode"
              :invite-code-enabled="inviteCodeEnabled"
              :loading="loading"
              :captcha-required="captcha.required"
              :captcha-image="captcha.image"
              :captcha-loading="captcha.loading"
              :feedback="feedback"
              :feedback-kind="feedbackKind"
              :oauth-providers="oauthProviders"
              @submit="submit"
              @validation-error="showValidationError"
              @refresh-captcha="refreshCaptcha"
              @oauth="loginWithOAuth"
            />
          </article>

          <p class="auth-switch-line">
            <template v-if="mode === 'login'">还没有工作间？ <button type="button" @click="toggleMode">{{ inviteCodeEnabled ? '凭邀请码开启' : '创建一个' }}</button></template>
            <template v-else>已经有工作间？ <button type="button" @click="toggleMode">返回登录</button></template>
          </p>
          <p v-if="mode === 'register'" class="auth-legal-note">
            创建即表示你同意
            <RouterLink :to="legalPath('terms')" target="_blank" rel="noopener">《使用说明》</RouterLink>
            与
            <RouterLink :to="legalPath('privacy')" target="_blank" rel="noopener">《隐私说明》</RouterLink>
            。
          </p>
        </AuthFormContainer>

        <footer v-if="isPage" class="auth-form-column__footer">代码、上下文与改动，安静地流动。</footer>
      </div>
    </template>
  </AuthLayout>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { RouterLink } from 'vue-router'
import { useRoute, useRouter } from 'vue-router'
import AuthBrandPanel from '@/components/auth/AuthBrandPanel.vue'
import AuthFormContainer from '@/components/auth/AuthFormContainer.vue'
import AuthLayout from '@/components/auth/AuthLayout.vue'
import LoginForm from '@/components/LoginForm.vue'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api'
import { legalPath } from '@/data/legal/legalDocuments.js'
import { useAuthCapabilitiesStore } from '@/stores/authCapabilities'
import { useUserStore } from '@/stores/user'

/**
 * 认证容器：同一份登录 / 注册逻辑同时服务两种渲染方式，避免出现第二套认证实现。
 *
 * - `variant="page"`（默认）：`/login` 路由的整页形态，含品牌叙事栏、视口元信息处理，并负责接收 OAuth 回调。
 * - `variant="inline"`：登录弹窗内嵌形态，单栏紧凑；不触碰 document 视口，认证成功后只通知宿主。
 *
 * `mode` 为受控 / 非受控两用：
 * - 不传 `mode`（如 `/login` 里的 `<Auth />`）：组件自持登录 / 注册状态，行为与改造前一致；
 * - 传 `mode` 且监听 `update:mode`（如弹窗）：状态由宿主（authModal store）单一持有。
 */
const props = defineProps({
  /** 'page' | 'inline' */
  variant: { type: String, default: 'page' },
  /** 'login' | 'register'；null 表示由组件自持 */
  mode: { type: String, default: null }
})

const emit = defineEmits(['submit', 'validation-error', 'refresh-captcha', 'oauth', 'update:mode', 'succeeded'])

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const isPage = computed(() => props.variant !== 'inline')

const internalMode = ref('login')
const isModeControlled = computed(() => props.mode === 'login' || props.mode === 'register')
const mode = computed({
  get: () => (isModeControlled.value ? props.mode : internalMode.value),
  set: value => {
    internalMode.value = value
    if (isModeControlled.value) emit('update:mode', value)
  }
})

/**
 * 认证能力（是否需要邀请码 / 支持哪些第三方登录）由 authCapabilities store 统一持有。
 *
 * 之所以不在本组件自持：落地页的 CTA 也要按「是否需要邀请码」决定显示哪几个按钮，
 * 两处各拉一份会产生重复请求，且状态可能瞬时不一致。store 是唯一事实源，
 * 并已内置「失败回落上次已确认值」的策略（见该 store 注释）。
 */
const authCapabilities = useAuthCapabilitiesStore()
const { inviteCodeEnabled, oauthProviders } = storeToRefs(authCapabilities)

const loading = ref(false)
const feedback = ref('')
const feedbackKind = ref('error')
const captcha = reactive({ id: '', image: '', required: false, loading: false })
const isCardActive = ref(false)
const pointer = ref({ x: 50, y: 50 })
let viewportEnabled = false
let createdViewportMeta = null
let existingViewportMeta = null
let previousViewportContent = null
const cardStyle = computed(() => ({
  '--card-shift-x': `${(pointer.value.x - 50) * 0.035}px`,
  '--card-shift-y': `${(pointer.value.y - 50) * 0.035}px`,
  '--ink-x': `${pointer.value.x}%`,
  '--ink-y': `${pointer.value.y}%`
}))

onMounted(async () => {
  // 视口元信息与 OAuth 回调只属于整页形态；弹窗内改动 document 视口会污染宿主页面
  if (isPage.value) enableAuthViewport()
  await loadAuthConfig()
  if (isPage.value) await handleOAuthCallback()
})

onBeforeUnmount(() => {
  if (viewportEnabled) restoreViewport()
})

function enableAuthViewport() {
  existingViewportMeta = document.head.querySelector('meta[name="viewport"]')
  if (existingViewportMeta) {
    previousViewportContent = existingViewportMeta.getAttribute('content')
    existingViewportMeta.setAttribute('content', 'width=device-width, initial-scale=1')
    viewportEnabled = true
    return
  }
  createdViewportMeta = document.createElement('meta')
  createdViewportMeta.name = 'viewport'
  createdViewportMeta.content = 'width=device-width, initial-scale=1'
  document.head.append(createdViewportMeta)
  viewportEnabled = true
}

function restoreViewport() {
  if (createdViewportMeta) {
    createdViewportMeta.remove()
    createdViewportMeta = null
  }
  if (existingViewportMeta) {
    if (previousViewportContent === null) existingViewportMeta.removeAttribute('content')
    else existingViewportMeta.setAttribute('content', previousViewportContent)
    existingViewportMeta = null
    previousViewportContent = null
  }
  viewportEnabled = false
}

function toggleMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  feedback.value = ''
  captcha.required = false
  captcha.id = ''
  captcha.image = ''
}

/** 认证能力由 authCapabilities store 统一加载（内置缓存与并发去重） */
async function loadAuthConfig() {
  await authCapabilities.ensureLoaded()
}

async function handleOAuthCallback() {
  const oauthError = String(route.query.oauth_error || '')
  const oauthCode = String(route.query.oauth_code || '')
  if (oauthError) {
    feedback.value = '第三方登录未完成，请重新尝试。'
    await clearOAuthQuery()
    return
  }
  if (!oauthCode) return
  loading.value = true
  try {
    await userStore.login({ oauthCode })
    feedbackKind.value = 'success'
    feedback.value = '欢迎回来。'
    notifyAuthSuccess(false)
    await router.replace('/projects')
  } catch (error) {
    feedbackKind.value = 'error'
    feedback.value = error instanceof Error ? error.message : '第三方登录失败，请重试。'
    await clearOAuthQuery()
  } finally {
    loading.value = false
  }
}

async function clearOAuthQuery() {
  const query = { ...route.query }
  delete query.oauth_error
  delete query.oauth_code
  delete query.oauth_state
  await router.replace({ query })
}

async function refreshCaptcha() {
  captcha.loading = true
  try {
    const response = await authApi.getCaptcha(mode.value, { silent: true })
    captcha.id = response?.data?.captchaId || ''
    captcha.image = response?.data?.image || ''
  } catch {
    feedbackKind.value = 'error'
    feedback.value = '验证码暂时不可用，请稍后重试。'
  } finally {
    captcha.loading = false
  }
}

async function submit(credentials) {
  loading.value = true
  feedback.value = ''
  const payload = { ...credentials, captchaId: captcha.id }
  try {
    if (mode.value === 'register') {
      if (inviteCodeEnabled.value) {
        await userStore.inviteRegister(payload)
      } else {
        await userStore.register(payload)
      }
      feedbackKind.value = 'success'
      feedback.value = '工作间创建成功。'
      notifyAuthSuccess(true)
    } else {
      await userStore.login(payload)
      feedbackKind.value = 'success'
      feedback.value = '欢迎回来。'
      notifyAuthSuccess(false)
    }
    captcha.required = false
    captcha.id = ''
    captcha.image = ''
    if (isPage.value) {
      await router.replace('/projects')
    } else {
      // 弹窗形态：落位由宿主决定（例如停留在当前公开页面）
      emit('succeeded')
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : '认证失败，请重试。'
    feedbackKind.value = 'error'
    feedback.value = message
    if (/验证码/.test(message) || /captcha/i.test(message)) {
      captcha.required = true
      await refreshCaptcha()
    }
  } finally {
    loading.value = false
  }
}

/**
 * 登录 / 注册成功后的顶部通知。
 *
 * 放在本组件的原因：整页形态与弹窗形态共用这一份认证逻辑，
 * 通知在此处发出可保证两条路径都不会漏（此前两种形态都是静默跳转，用户无从确认是否成功）。
 */
function notifyAuthSuccess(isRegister) {
  const info = userStore.userInfo || {}
  const name = info.displayName || info.username || ''
  ElMessage.success({
    message: isRegister
      ? (name ? `工作间已创建，欢迎 ${name}` : '工作间已创建，欢迎加入')
      : (name ? `欢迎回来，${name}` : '欢迎回来'),
    duration: 3000,
    showClose: true
  })
}

function showValidationError() {
  feedbackKind.value = 'error'
  feedback.value = '请检查表单中的提示后再提交。'
}

function loginWithOAuth(provider) {
  if (!['github', 'google'].includes(provider)) return
  loading.value = true
  // 后端 oauth-frontend-callback 固定回落到 /login，第三方登录最终由整页形态接续
  window.location.href = `/api/auth/oauth/${encodeURIComponent(provider)}/authorize`
}

function handleCardPointerMove(event) {
  const rect = event.currentTarget.getBoundingClientRect()
  pointer.value = { x: ((event.clientX - rect.left) / rect.width) * 100, y: ((event.clientY - rect.top) / rect.height) * 100 }
}

function handleCardPointerLeave() {
  isCardActive.value = false
  pointer.value = { x: 50, y: 50 }
}
</script>

<style scoped>
.auth-canvas {
  background: var(--theme-surface);
  color: var(--theme-text);
  color-scheme: light;
}

/* 弹窗内嵌：不重复铺设整页背景，交由 el-dialog 承担 */
.auth-canvas--inline {
  color: var(--theme-text);
  color-scheme: light;
}

.auth-form-column { width: min(100%, 520px); }
.auth-form-column__topline { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 22px; color: #5f6b7c; font-size: 11px; letter-spacing: .08em; }
.auth-form-column__topline button, .auth-switch-line button { border: 0; padding: 0; background: transparent; color: var(--theme-accent); cursor: pointer; font: inherit; font-weight: 600; }
.auth-form-column__topline button:hover, .auth-switch-line button:hover { text-decoration: underline; text-underline-offset: 3px; }
/* 冷色版卡片：规整圆角 + 静置一档浅投影，不做指针跟随位移与光斑（悬停不再抬升） */
.auth-card { position: relative; border: 1px solid var(--theme-border); border-radius: 16px; padding: 30px; background: var(--theme-surface); box-shadow: 0 1px 2px rgb(24 33 51 / 4%); transition: border-color 220ms ease, background-color 400ms cubic-bezier(.4, 0, .2, 1); }
/* 指针光层保留占位：内嵌形态用 display:none 关闭它（测试契约），整页形态也不再显示 */
.auth-card::after { position: absolute; inset: -1px; z-index: -1; border-radius: inherit; content: ''; opacity: 0; background: none; pointer-events: none; }
.auth-card--active { border-color: #c7d2fe; }
.auth-switch-line { margin: 22px 0 0; color: var(--theme-text-secondary); font-size: 13px; text-align: center; }
.auth-legal-note { margin: 17px 0 0; border-top: 1px solid var(--theme-border); padding-top: 14px; color: #5f6b7c; font-size: 11px; line-height: 1.6; text-align: center; }
.auth-legal-note a { color: var(--theme-accent); text-decoration: underline; text-underline-offset: 2px; }
.auth-form-column__footer { margin-top: 34px; color: #5f6b7c; font-size: 11px; text-align: center; }

/* 弹窗内：放开整页宽度约束，并收紧切换行的上间距 */
.auth-canvas--inline .auth-form-column { width: 100%; }
.auth-canvas--inline .auth-switch-line { margin-top: 18px; }

/* 弹窗内嵌时 el-dialog 已提供外框与背景，卡片自身再画一层会形成「框套框」，
   故在此扁平化；同时关闭指针跟随光与位移，避免与外层的聚焦框互相干扰。 */
.auth-canvas--inline .auth-card,
.auth-canvas--inline .auth-card--active {
  border: 0;
  border-radius: 0;
  padding: 0;
  background: transparent;
  box-shadow: none;
  transform: none;
}

.auth-canvas--inline .auth-card::after { display: none; }

@media (max-width: 640px) {
  .auth-form-column { width: 100%; }
  .auth-card { padding: 20px 16px; border-radius: 18px; transform: none !important; }
  .auth-form-column__topline { margin-bottom: 14px; }
  .auth-form-column__footer { margin-top: 20px; }
}
@media (max-width: 520px) { .auth-card { padding: 18px 14px; } .auth-form-column__topline { margin-bottom: 14px; } }
@media (prefers-reduced-motion: reduce) { .auth-card, .auth-card::after { transition: none; } }
</style>

<style>
.auth-canvas ~ .theme-settings-launcher { display: none; }
</style>
