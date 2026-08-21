<template>
  <div class="opr">
    <span class="opr-num">{{ page }}</span>
    <button class="opr-btn" :disabled="page <= 1" @click="$emit('change', page - 1)">上一页</button>
    <button class="opr-btn" :disabled="page * pageSize >= total" @click="$emit('change', page + 1)">下一页</button>
    <span class="opr-total">共 {{ total }} 条</span>
  </div>
</template>

<script setup>
defineProps({
  page: { type: Number, default: 1 },
  total: { type: Number, default: 0 },
  pageSize: { type: Number, default: 20 }
})

defineEmits(['change'])
</script>

<style scoped>
.opr { display: flex; align-items: center; gap: 8px; padding: 10px 0 2px; font-size: 12px; color: #6b7280; }
.opr-btn { border: 1px solid #e5e7eb; background: #fff; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-family: inherit; color: #374151; cursor: pointer; }
.opr-btn:hover:not(:disabled) { background: #f9fafb; }
.opr-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.opr-num { font-variant-numeric: tabular-nums; }
</style>
