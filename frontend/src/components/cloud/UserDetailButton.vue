<template>
  <el-button class="user-detail-btn" :icon="User" link @click="showUserInfo">
    用户详情
  </el-button>
</template>

<script setup lang="ts">
import { ElMessageBox, ElMessage } from 'element-plus'
import { User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

async function showUserInfo() {
  const info = userStore.userInfo
  if (!info) {
    ElMessage.warning('用户信息不可用')
    return
  }
  const lines = [
    `用户名：${info.username || '-'}`,
    info.displayName ? `显示名：${info.displayName}` : null,
    info.id ? `用户 ID：${info.id}` : null,
  ].filter(Boolean)

  ElMessageBox.alert(lines.join('\n'), '用户详情', {
    confirmButtonText: '关闭',
    showClose: false,
  })
}
</script>

<style scoped>
.user-detail-btn {
  font-size: 13px;
  color: #9ca3af;
}
.user-detail-btn:hover {
  color: #409eff;
}
</style>
