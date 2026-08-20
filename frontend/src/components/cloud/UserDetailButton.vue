<template>
  <div class="user-detail-root">
    <el-button class="user-detail-btn" :icon="User" link @click="showUserInfo">
      账号设置
    </el-button>
    <el-dialog v-model="dialogVisible" title="账号设置" width="460px" destroy-on-close>
      <div class="user-detail-content">
        <p class="user-summary">用户名：{{ userStore.userInfo?.username || '-' }}</p>
        <p class="user-summary">邮箱：{{ userStore.userInfo?.email || '未设置' }}</p>
        <div class="oauth-section">
          <h4>第三方登录</h4>
          <div v-if="loading" class="oauth-loading">正在读取绑定状态…</div>
          <div v-else class="oauth-list">
            <div v-for="item in providers" :key="item.key" class="oauth-item">
              <div>
                <strong>{{ item.label }}</strong>
                <p v-if="bindings[item.key]?.bound" class="oauth-bound">
                  已绑定：{{ bindings[item.key].displayName || '第三方账号' }}
                  <span v-if="bindings[item.key].email">（{{ bindings[item.key].email }}）</span>
                </p>
                <p v-else class="oauth-unbound">尚未绑定</p>
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
.user-summary {
  margin: 0 0 8px;
  font-size: 13px;
}
.oauth-section {
  margin-top: 20px;
  border-top: 1px solid #ebeef5;
  padding-top: 14px;
}
.oauth-section h4 {
  margin: 0 0 10px;
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
</style>

