<template>
  <el-button class="logout-btn" :icon="SwitchButton" link @click="handleLogout">
    退出登录
  </el-button>
</template>

<script setup lang="ts">
import { ElMessageBox, ElMessage } from 'element-plus'
import { SwitchButton } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useRouter } from 'vue-router'

const userStore = useUserStore()
const router = useRouter()

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<style scoped>
.logout-btn {
  font-size: 13px;
  color: var(--theme-text-muted);
}
.logout-btn:hover {
  color: var(--theme-accent);
}
</style>
