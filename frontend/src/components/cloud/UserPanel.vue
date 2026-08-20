<template>
  <div class="user-panel">
    <div class="user-info">
      <span class="user-name">{{ displayName }}</span>
    </div>
    <div class="user-actions">
      <UserDetailButton />
      <LogoutButton />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'
import UserDetailButton from './UserDetailButton.vue'
import LogoutButton from './LogoutButton.vue'

const userStore = useUserStore()

const displayName = computed(() => {
  const info = userStore.userInfo
  if (!info) return '未登录'
  return info.displayName || info.username || '用户'
})
</script>

<style scoped>
.user-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-top: 1px solid var(--theme-border);
  background: var(--theme-surface-muted);
  flex-shrink: 0;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--theme-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 120px;
}

.user-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
</style>
