<template>
  <span class="ost" :class="type">{{ text }}</span>
</template>

<script setup>
defineProps({
  text: { type: String, required: true },
  type: { type: String, default: 'info' }
})
</script>

<style scoped>
.ost { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; }
.ost.danger { background: #fef2f2; color: #dc2626; }
.ost.warning { background: #fffbeb; color: #d97706; }
.ost.success { background: #ecfdf5; color: #059669; }
.ost.info { background: #f1f5f9; color: #64748b; }
</style>
