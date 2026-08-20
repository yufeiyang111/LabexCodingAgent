<template>
  <p v-if="message" class="auth-feedback" :class="`auth-feedback--${kind}`" role="alert" aria-live="polite">{{ message }}</p>
</template>

<script setup>
defineProps({
  message: { type: String, default: '' },
  kind: { type: String, default: 'error' }
})
</script>

<style scoped>
.auth-feedback { margin: 0 0 16px; border-left: 2px solid #a24e42; padding: 8px 12px; color: #8e463d; font-size: 13px; line-height: 1.5; }
.auth-feedback--success { border-color: #5d675b; color: #4f5c4e; }
.auth-feedback--info { border-color: #987562; color: #80604f; }
</style>
