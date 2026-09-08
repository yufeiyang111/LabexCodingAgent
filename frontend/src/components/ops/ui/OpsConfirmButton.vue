<template>
  <button type="button" class="oc-btn" :class="[variant, { danger: danger, confirmed }]" :disabled="disabled || running" @click="onClick">
    <template v-if="confirmed && confirmText">确定{{ running ? '…' : '' }}</template>
    <template v-else>{{ running ? '处理中…' : (label || text) }}</template>
  </button>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  label: { type: String, default: '' },
  text: { type: String, default: '' },
  confirmText: { type: String, default: '' },
  variant: { type: String, default: 'default' },
  danger: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['confirm'])

const confirmed = ref(false)
const running = ref(false)
let resetTimer = null

function onClick() {
  if (!props.confirmText) {
    run()
    return
  }
  if (!confirmed.value) {
    confirmed.value = true
    if (resetTimer) clearTimeout(resetTimer)
    resetTimer = setTimeout(() => { confirmed.value = false }, 3000)
    return
  }
  run()
}

async function run() {
  confirmed.value = false
  running.value = true
  try {
    await emit('confirm')
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.oc-btn { border: 1px solid #e5e7eb; background: #fff; padding: 5px 12px; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; cursor: pointer; }
.oc-btn:hover:not(:disabled) { background: #f9fafb; }
.oc-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.oc-btn.danger { color: #dc2626; border-color: #fecaca; }
.oc-btn.danger.confirmed { background: #dc2626; border-color: #dc2626; color: #fff; }
.oc-btn.primary { background: #4f46e5; border-color: #4f46e5; color: #fff; }
.oc-btn.primary:hover:not(:disabled) { background: #4338ca; }
</style>
