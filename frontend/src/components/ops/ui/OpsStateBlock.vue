<template>
  <div v-if="loading" class="ob-state ob-loading">{{ loadingText }}</div>
  <div v-else-if="error" class="ob-state ob-error">{{ error }}</div>
  <div v-else-if="empty" class="ob-state ob-empty">{{ emptyText }}</div>
  <slot v-else />
</template>

<script setup>
defineProps({
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  empty: { type: Boolean, default: false },
  loadingText: { type: String, default: '加载中…' },
  emptyText: { type: String, default: '暂无数据' }
})
</script>

<style scoped>
.ob-state { padding: 32px 16px; text-align: center; font-size: 12px; color: #9ca3af; }
.ob-error { color: #dc2626; }
</style>
