<template>
  <span class="auth-entry">
    <button
      type="button"
      class="auth-entry__btn"
      :class="{ 'auth-entry__btn--primary': primary }"
      :title="label"
      @click="openLogin"
    >
      {{ label }}
    </button>
    <button
      v-if="showRegister"
      type="button"
      class="auth-entry__btn"
      title="创建账号"
      @click="openRegister"
    >
      创建账号
    </button>
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'
import { useAuthModalStore } from '@/stores/authModal'

/**
 * 登录入口按钮。
 *
 * 用途：在未登录的公开页面上按需唤起登录 / 注册弹窗，避免把认证组件常驻渲染在页面里。
 * 已登录时自身不渲染任何内容（由调用方决定是否 v-if，这里再做一层兜底）。
 */
const props = defineProps({
  /** 主按钮样式，用于顶栏等需要强调的场景 */
  primary: { type: Boolean, default: false },
  /** 是否同时提供「创建账号」次按钮 */
  showRegister: { type: Boolean, default: false },
  label: { type: String, default: '登录' }
})

const userStore = useUserStore()
const authModal = useAuthModalStore()

const emit = defineEmits(['open'])

const canPrompt = computed(() => !userStore.isLoggedIn)

function openLogin() {
  if (!canPrompt.value) return
  authModal.open('login')
  emit('open', 'login')
}

function openRegister() {
  if (!canPrompt.value) return
  authModal.open('register')
  emit('open', 'register')
}

defineExpose({ canPrompt })
</script>

<style scoped>
.auth-entry {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.auth-entry__btn {
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  padding: 6px 14px;
  background: #fff;
  color: #374151;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  line-height: 1.4;
  white-space: nowrap;
  transition: border-color 0.18s ease, background-color 0.18s ease, color 0.18s ease;
}

.auth-entry__btn:hover {
  border-color: var(--theme-accent, #4f46e5);
  color: var(--theme-accent, #4f46e5);
  background: var(--theme-accent-soft, #eef2ff);
}

.auth-entry__btn--primary {
  border-color: transparent;
  background: var(--theme-accent, #4f46e5);
  color: #fff;
}

.auth-entry__btn--primary:hover {
  border-color: transparent;
  background: var(--theme-accent-strong, #4338ca);
  color: #fff;
}

@media (max-width: 640px) {
  .auth-entry__btn {
    padding: 5px 11px;
    font-size: 12px;
  }
}
</style>
