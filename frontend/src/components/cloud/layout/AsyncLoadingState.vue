<template>
  <div class="ws-async-loading" :class="{ 'is-error': error }" role="status">
    <span>{{ error ? errorText : loadingText }}</span>
    <button v-if="error" type="button" @click="$emit('retry')">重试</button>
  </div>
</template>

<script setup>
defineProps({
  loadingText: { type: String, default: '正在加载…' },
  errorText: { type: String, default: '加载失败，请重试' },
  error: { type: Boolean, default: false }
})
defineEmits(['retry'])
</script>

<style scoped>
.ws-async-loading {
  display: grid;
  place-items: center;
  gap: 10px;
  min-height: 180px;
  height: 100%;
  color: #64748b;
  font-size: 13px;
}
.ws-async-loading button {
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  padding: 4px 10px;
  background: transparent;
  cursor: pointer;
}
.ws-async-loading.is-error { color: #b91c1c; }
</style>
