<template>
  <!-- 智能编程工作间：理解仓库、规划改动、验证结果 -->
  <AuthLayout class="auth-canvas auth-layout lg:grid-cols-[minmax(0,1fr)_460px]">
    <template #brand>
      <AuthBrandPanel class="auth-narrative" />
    </template>

    <template #form>
      <div class="auth-form-column">
        <div class="auth-form-column__topline">
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
          <p v-if="mode === 'register'" class="auth-legal-note">创建即表示你同意使用说明与隐私说明。</p>
        </AuthFormContainer>

        <footer class="auth-form-column__footer">代码、上下文与改动，安静地流动。</footer>
      </div>
    </template>
  </AuthLayout>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthBrandPanel from '@/components/auth/AuthBrandPanel.vue'
import AuthFormContainer from '@/components/auth/AuthFormContainer.vue'
import AuthLayout from '@/components/auth/AuthLayout.vue'
import LoginForm from '@/components/LoginForm.vue'
import { authApi } from '@/api'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const mode = ref('login')
const loading = ref(false)
const feedback = ref('')
const feedbackKind = ref('error')
const inviteCodeEnabled = ref(false)
const oauthProviders = ref([])
const captcha = reactive({ id: '', image: '', required: false, loading: false })
const isCardActive = ref(false)
const pointer = ref({ x: 50, y: 50 })
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
  enableAuthViewport()
  await loadAuthConfig()
  await handleOAuthCallback()
})

onBeforeUnmount(() => {
  restoreViewport()
})

function enableAuthViewport() {
  existingViewportMeta = document.head.querySelector('meta[name="viewport"]')
  if (existingViewportMeta) {
    previousViewportContent = existingViewportMeta.getAttribute('content')
    existingViewportMeta.setAttribute('content', 'width=device-width, initial-scale=1')
    return
  }
  createdViewportMeta = document.createElement('meta')
  createdViewportMeta.name = 'viewport'
  createdViewportMeta.content = 'width=device-width, initial-scale=1'
  document.head.append(createdViewportMeta)
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
}

function toggleMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  feedback.value = ''
  captcha.required = false
  captcha.id = ''
  captcha.image = ''
}

async function loadAuthConfig() {
  try {
    const response = await authApi.getAuthConfig({ silent: true })
    inviteCodeEnabled.value = Boolean(response?.data?.inviteCodeEnabled)
    const providers = response?.data?.oauthProviders
    oauthProviders.value = Array.isArray(providers?.providers)
      ? providers.providers
      : Array.isArray(providers)
      ? providers
      : []
  } catch {
    inviteCodeEnabled.value = false
    oauthProviders.value = []
  }
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
    } else {
      await userStore.login(payload)
      feedbackKind.value = 'success'
      feedback.value = '欢迎回来。'
    }
    captcha.required = false
    captcha.id = ''
    captcha.image = ''
    await router.replace('/projects')
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

function showValidationError() {
  feedbackKind.value = 'error'
  feedback.value = '请检查表单中的提示后再提交。'
}

function loginWithOAuth(provider) {
  if (!['github', 'google'].includes(provider)) return
  loading.value = true
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
  background: #fbf8f0;
  color: #34352f;
  color-scheme: light;
}

.auth-form-column { width: min(100%, 520px); }
.auth-form-column__topline { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 22px; color: #8b887e; font-size: 11px; letter-spacing: .08em; }
.auth-form-column__topline button, .auth-switch-line button { border: 0; padding: 0; background: transparent; color: #5d675b; cursor: pointer; font: inherit; font-weight: 600; }
.auth-form-column__topline button:hover, .auth-switch-line button:hover { text-decoration: underline; text-underline-offset: 3px; }
.auth-card { position: relative; transform: translate3d(var(--card-shift-x, 0px), var(--card-shift-y, 0px), 0); border: 1px solid #cfc8ba; border-radius: 26px 22px 28px 24px; padding: 30px; background: rgb(251 248 240 / 92%); transition: transform 260ms ease, border-color 220ms ease, box-shadow 260ms ease; }
.auth-card::after { position: absolute; inset: -1px; z-index: -1; border-radius: inherit; content: ''; opacity: 0; background: radial-gradient(circle at var(--ink-x, 50%) var(--ink-y, 50%), rgb(93 103 91 / 12%), transparent 43%); transition: opacity 260ms ease; pointer-events: none; }
.auth-card--active { border-color: #aab1a3; box-shadow: 0 14px 30px rgb(71 66 52 / 7%); }
.auth-card--active::after { opacity: 1; }
.auth-switch-line { margin: 22px 0 0; color: #76756d; font-size: 13px; text-align: center; }
.auth-legal-note { margin: 17px 0 0; border-top: 1px solid #ded8cc; padding-top: 14px; color: #8b887e; font-size: 11px; line-height: 1.6; text-align: center; }
.auth-form-column__footer { margin-top: 34px; color: #8b887e; font-size: 11px; text-align: center; }
@media (max-width: 520px) { .auth-card { padding: 22px 18px; } .auth-form-column__topline { margin-bottom: 17px; } }
@media (prefers-reduced-motion: reduce) { .auth-card, .auth-card::after { transition: none; } }
</style>

<style>
.auth-canvas ~ .theme-settings-launcher { display: none; }
</style>
