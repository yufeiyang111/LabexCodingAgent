<template>
  <div class="user-detail-root">
    <el-button class="user-detail-btn" :icon="User" link @click="showUserInfo">
      账号设置
    </el-button>
    <el-dialog v-model="dialogVisible" title="账号设置" width="460px" destroy-on-close>
      <div class="user-detail-content">
        <div class="ud-row">
          <span class="ud-label">用户名</span>
          <span class="ud-value">{{ userStore.userInfo?.username || '-' }}</span>
          <el-button type="primary" link size="small" @click="editUsername">修改</el-button>
        </div>
        <div class="ud-row">
          <span class="ud-label">邮箱</span>
          <span class="ud-value">{{ userStore.userInfo?.email || '未设置' }}</span>
          <template v-if="userStore.userInfo?.email">
            <el-button type="primary" link size="small" @click="editEmail">修改</el-button>
            <el-button type="danger" link size="small" @click="clearEmail">删除</el-button>
          </template>
          <el-button v-else type="primary" link size="small" @click="editEmail">设置</el-button>
        </div>
        <div class="oauth-section">
          <h4>第三方登录</h4>
          <div v-if="loading" class="oauth-loading">正在读取绑定状态…</div>
          <div v-else class="oauth-list">
            <div v-for="item in providers" :key="item.key" class="oauth-item">
              <div class="oauth-item-left">
                <span class="oauth-icon-wrap">
                  <OAuthProviderIcon :provider="item.key" />
                </span>
                <div>
                  <strong>{{ item.label }}</strong>
                  <p v-if="bindings[item.key]?.bound" class="oauth-bound">
                    已绑定：{{ bindings[item.key].displayName || '第三方账号' }}
                    <span v-if="bindings[item.key].email">（{{ bindings[item.key].email }}）</span>
                  </p>
                  <p v-else class="oauth-unbound">尚未绑定</p>
                </div>
              </div>
              <el-button
                v-if="bindings[item.key]?.bound"
                type="danger"
                link
                :loading="busyProvider === item.key"
                @click="unbind(item.key, item.label)"
              >
                解绑
              </el-button>
              <el-button
                v-else
                type="primary"
                link
                :disabled="busyProvider !== ''"
                @click="bind(item.key)"
              >
                绑定
              </el-button>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User } from '@element-plus/icons-vue'
import { authApi } from '@/api'
import { useUserStore } from '@/stores/user'
import OAuthProviderIcon from '@/components/auth/OAuthProviderIcon.vue'

const userStore = useUserStore()
const dialogVisible = ref(false)
const loading = ref(false)
const busyProvider = ref('')
const bindings = ref<Record<string, { bound?: boolean; displayName?: string; email?: string }>>({})
const providers = [
  { key: 'github', label: 'GitHub' },
  { key: 'google', label: 'Google' }
]

async function showUserInfo() {
  if (!userStore.userInfo) {
    ElMessage.warning('用户信息不可用')
    return
  }
  dialogVisible.value = true
  await loadBindings()
}

async function loadBindings() {
  loading.value = true
  try {
    const response = await authApi.getOAuthBindings({ silent: true })
    bindings.value = response.data || {}
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取第三方绑定状态失败')
  } finally {
    loading.value = false
  }
}

async function editUsername() {
  const current = userStore.userInfo?.username || ''
  try {
    const { value } = await ElMessageBox.prompt(
      '请输入新用户名（3-32 位小写字母/数字/下划线/点/短横线）',
      '修改用户名',
      {
        inputValue: current,
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValidator: (v) => /^[a-z0-9_.-]{3,32}$/.test(v) ? true : '用户名格式不正确',
        inputType: 'text'
      }
    )
    await authApi.updateUsername(value)
    Object.assign(userStore.userInfo, { username: value })
    ElMessage.success('用户名已修改')
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e instanceof Error ? e.message : '修改用户名失败')
  }
}

async function editEmail() {
  const current = userStore.userInfo?.email || ''
  try {
    const { value } = await ElMessageBox.prompt(
      '请输入邮箱地址',
      '设置邮箱',
      {
        inputValue: current,
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValidator: (v) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v) ? true : '请输入有效的邮箱地址',
        inputType: 'text'
      }
    )
    await authApi.updateEmail(value)
    Object.assign(userStore.userInfo, { email: value })
    ElMessage.success('邮箱已保存')
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e instanceof Error ? e.message : '保存邮箱失败')
  }
}

async function clearEmail() {
  try {
    await ElMessageBox.confirm('确定删除邮箱吗？', '删除邮箱', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await authApi.clearEmail()
    Object.assign(userStore.userInfo, { email: '' })
    ElMessage.success('邮箱已删除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除邮箱失败')
  }
}

async function bind(provider: string) {
  busyProvider.value = provider
  try {
    const response = await authApi.startOAuthBinding(provider)
    const authorizationUrl = String(response.data?.authorizationUrl || '')
    if (!authorizationUrl) {
      throw new Error('第三方授权地址不可用，请重试')
    }
    window.location.assign(authorizationUrl)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发起第三方绑定失败')
    busyProvider.value = ''
  }
}

async function unbind(provider: string, label: string) {
  try {
    await ElMessageBox.confirm(`确定解绑 ${label} 吗？`, '解绑第三方账号', {
      confirmButtonText: '确认解绑',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  busyProvider.value = provider
  try {
    await authApi.unbindOAuth(provider)
    ElMessage.success(`${label} 已解绑`)
    await loadBindings()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '解绑失败')
  } finally {
    busyProvider.value = ''
  }
}
</script>

<style scoped>
.user-detail-btn {
  font-size: 13px;
  color: var(--theme-text-muted);
}
.user-detail-btn:hover {
  color: var(--theme-accent);
}
.user-detail-content {
  color: #374151;
}
.ud-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 13px;
}
.ud-label {
  color: #6b7280;
  min-width: 40px;
}
.ud-value {
  color: #111827;
  flex: 1;
}
.oauth-section {
  margin-top: 16px;
  border-top: 1px solid #ebeef5;
  padding-top: 14px;
}
.oauth-section h4 {
  margin: 0 0 10px;
  font-size: 13px;
}
.oauth-loading,
.oauth-bound,
.oauth-unbound {
  margin: 5px 0 0;
  color: #909399;
  font-size: 12px;
}
.oauth-list {
  display: grid;
  gap: 10px;
}
.oauth-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 10px 12px;
}
.oauth-item-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.oauth-icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex-shrink: 0;
}
</style>