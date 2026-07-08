<template>
  <main class="login-page">
    <section class="login-visual">
      <AppIcon :size="48" />
      <h1>LabexAgent</h1>
      <p>独立云代码空间，登录后管理自己的项目、模型配置和 Agent 会话。</p>
    </section>

    <section class="login-panel">
      <div class="tabs">
        <button :class="{ active: mode === 'login' }" @click="mode = 'login'">登录</button>
        <button :class="{ active: mode === 'register' }" @click="mode = 'register'">注册</button>
      </div>

      <form @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input v-model.trim="form.username" autocomplete="username" placeholder="3-32 位字母、数字或 ._-" />
        </label>
        <label v-if="mode === 'register'">
          <span>昵称</span>
          <input v-model.trim="form.displayName" autocomplete="name" placeholder="可选" />
        </label>
        <label>
          <span>密码</span>
          <input v-model="form.password" type="password" autocomplete="current-password" placeholder="至少 6 位" />
        </label>
        <button class="submit-btn" type="submit" :disabled="loading">
          {{ loading ? '处理中...' : mode === 'login' ? '登录' : '创建账号' }}
        </button>
      </form>
    </section>
  </main>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppIcon from '@/components/AppIcon.vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const mode = ref('login')
const loading = ref(false)
const form = reactive({
  username: '',
  displayName: '',
  password: ''
})

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    if (mode.value === 'register') {
      await userStore.register(form)
      ElMessage.success('注册成功')
    } else {
      await userStore.login(form)
      ElMessage.success('登录成功')
    }
    router.replace('/projects')
  } catch (error) {
    ElMessage.error(error.message || '操作失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 420px;
  background: #f5f7fb;
  color: #172033;
}

.login-visual {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 64px clamp(40px, 8vw, 112px);
  background:
    linear-gradient(135deg, rgba(37, 99, 235, 0.1), transparent 40%),
    #eef3ff;
}

.login-visual h1 {
  margin: 24px 0 12px;
  font-size: 42px;
  font-weight: 750;
  letter-spacing: 0;
}

.login-visual p {
  max-width: 560px;
  color: #526070;
  font-size: 17px;
  line-height: 1.8;
}

.login-panel {
  align-self: center;
  margin: 32px;
  padding: 28px;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.08);
}

.tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 24px;
}

.tabs button {
  height: 38px;
  border: 1px solid #d8dee8;
  background: #f8fafc;
  border-radius: 6px;
  cursor: pointer;
  color: #526070;
}

.tabs button.active {
  border-color: #2563eb;
  background: #2563eb;
  color: #ffffff;
}

form {
  display: grid;
  gap: 16px;
}

label {
  display: grid;
  gap: 7px;
  color: #334155;
  font-size: 14px;
}

input {
  height: 40px;
  border: 1px solid #d8dee8;
  border-radius: 6px;
  padding: 0 12px;
  font-size: 14px;
  outline: none;
}

input:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.submit-btn {
  height: 42px;
  border: 0;
  border-radius: 6px;
  background: #111827;
  color: #ffffff;
  font-weight: 650;
  cursor: pointer;
}

.submit-btn:disabled {
  opacity: 0.65;
  cursor: wait;
}

@media (max-width: 820px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-visual {
    min-height: 260px;
    padding: 36px 24px;
  }

  .login-panel {
    margin: 20px;
  }
}
</style>
