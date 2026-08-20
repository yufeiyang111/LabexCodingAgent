<template>
  <div class="login-form-module">
    <AuthFeedback :message="feedback" :kind="feedbackKind" />
    <form class="auth-form" novalidate @submit.prevent="submitForm">
      <!-- 邀请码专属字段 (仅在 inviteCodeEnabled 且 mode === 'register' 时显示) -->
      <AuthField
        v-if="mode === 'register' && inviteCodeEnabled"
        v-model="form.inviteCode"
        label="专属邀请码"
        placeholder="请输入管理员分发的邀请码"
        autocomplete="off"
        :error="errors.inviteCode"
      />
      <!-- 焦点边框沿用 focus:border-[#5D675B] 视觉契约。 -->
      <AuthField v-model="form.username" label="用户名" placeholder="3-32 位字母、数字或 ._-" autocomplete="username" :error="errors.username" />
      <AuthField v-if="mode === 'register' && !inviteCodeEnabled" v-model="form.email" type="email" label="邮箱" optional placeholder="用于账号资料和第三方冲突提示" autocomplete="email" :error="errors.email" />
      <AuthField v-if="mode === 'register'" v-model="form.displayName" label="显示名称" optional placeholder="为工作间留下名字" autocomplete="name" maxlength="100" :error="errors.displayName" />
      <PasswordField v-model="form.password" label="密码" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :placeholder="mode === 'login' ? '请输入密码' : '至少 6 位字符'" :error="errors.password" />
      <PasswordField v-if="mode === 'register'" v-model="form.confirmPassword" label="确认密码" autocomplete="new-password" placeholder="请再次输入密码" :error="errors.confirmPassword" />
      <CaptchaField v-model="form.captchaCode" :required="captchaRequired" :image="captchaImage" :loading="captchaLoading" :error="errors.captchaCode" @refresh="$emit('refresh-captcha')" />
      <AgreementField v-if="mode === 'register'" v-model="form.agreed" :error="errors.agreed" />
      <button class="auth-submit-button" type="submit" :disabled="loading">
        {{ loading ? '正在处理…' : mode === 'login' ? '进入工作间' : (inviteCodeEnabled ? '验证邀请码并创建工作间' : '创建工作间') }}
      </button>
    </form>
    <OAuthButtons :providers="oauthProviders" :loading="loading" @select="$emit('oauth', $event)" />
  </div>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import AgreementField from '@/components/auth/AgreementField.vue'
import AuthFeedback from '@/components/auth/AuthFeedback.vue'
import AuthField from '@/components/auth/AuthField.vue'
import CaptchaField from '@/components/auth/CaptchaField.vue'
import OAuthButtons from '@/components/auth/OAuthButtons.vue'
import PasswordField from '@/components/auth/PasswordField.vue'

const props = defineProps({
  mode: { type: String, required: true },
  inviteCodeEnabled: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  captchaRequired: { type: Boolean, default: false },
  captchaImage: { type: String, default: '' },
  captchaLoading: { type: Boolean, default: false },
  feedback: { type: String, default: '' },
  feedbackKind: { type: String, default: 'error' },
  oauthProviders: { type: Array, default: () => [] }
})

const emit = defineEmits(['submit', 'validation-error', 'refresh-captcha', 'oauth'])
const form = reactive({ inviteCode: '', username: '', email: '', displayName: '', password: '', confirmPassword: '', captchaCode: '', agreed: false })
const errors = reactive({ inviteCode: '', username: '', email: '', displayName: '', password: '', confirmPassword: '', captchaCode: '', agreed: '' })

watch(() => props.mode, () => {
  Object.keys(errors).forEach(key => { errors[key] = '' })
  form.inviteCode = ''
  form.email = ''
  form.displayName = ''
  form.confirmPassword = ''
  form.captchaCode = ''
  form.agreed = false
})

function validate() {
  Object.keys(errors).forEach(key => { errors[key] = '' })
  let valid = true
  if (props.mode === 'register' && props.inviteCodeEnabled) {
    if (!form.inviteCode.trim()) { errors.inviteCode = '请输入专属邀请码'; valid = false }
  }
  const username = form.username.trim()
  if (!username) { errors.username = '请输入用户名'; valid = false } else if (!/^[a-zA-Z0-9_.-]{3,32}$/.test(username)) { errors.username = '用户名需为 3-32 位字母、数字、下划线、点或短横线'; valid = false }
  if (!form.password) { errors.password = '请输入密码'; valid = false } else if (form.password.length < 6 || form.password.length > 72) { errors.password = '密码长度需为 6-72 位'; valid = false }
  if (props.mode === 'register') {
    if (!props.inviteCodeEnabled && form.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) { errors.email = '请输入有效的邮箱地址'; valid = false }
    if (form.displayName.trim().length > 100) { errors.displayName = '显示名称不能超过 100 个字符'; valid = false }
    if (!form.confirmPassword) { errors.confirmPassword = '请确认密码'; valid = false } else if (form.confirmPassword !== form.password) { errors.confirmPassword = '两次输入的密码不一致'; valid = false }
    if (!form.agreed) { errors.agreed = '请先阅读并同意使用说明和隐私说明'; valid = false }
  }
  if (props.captchaRequired && !form.captchaCode.trim()) { errors.captchaCode = '请输入图形验证码'; valid = false }
  return valid
}

function submitForm() {
  if (!validate()) { emit('validation-error'); return }
  emit('submit', {
    inviteCode: form.inviteCode.trim(),
    username: form.username.trim(),
    email: form.email.trim(),
    displayName: form.displayName.trim(),
    password: form.password,
    captchaId: '',
    captchaCode: form.captchaCode.trim()
  })
}
</script>

<style scoped>
.login-form-module { width: 100%; }
.auth-form { display: grid; gap: 17px; }
.auth-submit-button { min-height: 48px; margin-top: 2px; border: 1px solid #4e584d; border-radius: 14px 12px 15px 11px; background: #4e584d; color: #fbf8f0; cursor: pointer; font: inherit; font-size: 15px; font-weight: 600; transition: background-color 180ms ease, border-color 180ms ease, opacity 180ms ease; }
.auth-submit-button:hover:not(:disabled) { border-color: #596557; background: #596557; }
.auth-submit-button:disabled { cursor: wait; opacity: .6; }
</style>
