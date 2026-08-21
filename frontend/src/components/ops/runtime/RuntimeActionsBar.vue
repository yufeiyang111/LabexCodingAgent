<template>
  <div class="rab">
    <div class="rab-row">
      <span class="rab-title">受控操作</span>
      <input v-model="taskId" name="task-id" class="rab-input" type="text" placeholder="任务 ID" />
      <OpsConfirmButton label="取消任务" danger confirm-text="确认取消?" :disabled="!taskId || acting" @confirm="onCancel" />
      <OpsConfirmButton label="重试任务" :disabled="!taskId || acting" @confirm="onRetry" />
      <OpsConfirmButton label="释放过期租约" :disabled="acting" @confirm="onRecover" />
    </div>
    <p v-if="message" class="rab-msg" :class="{ fail: failed }">{{ message }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { monitorApi } from '@/api'
import { useMonitorOperator } from '@/composables/ops/useMonitorOperator'
import OpsConfirmButton from '@/components/ops/ui/OpsConfirmButton.vue'

const emit = defineEmits(['401'])

const { withOperator } = useMonitorOperator()

const taskId = ref('')
const acting = ref(false)
const message = ref('')
const failed = ref(false)

function idemKey(action) {
  return action + '-' + taskId.value.trim() + '-' + Date.now().toString(36)
}

async function run(fn) {
  acting.value = true
  failed.value = false
  try {
    const op = await withOperator(fn)
    message.value = op?.status === 'SUCCEEDED' ? (op.result || '操作成功') : (op.failureReason || '操作失败')
    failed.value = op?.status !== 'SUCCEEDED'
  } catch (e) {
    if (e?.status === 401) {
      emit('401')
      return
    }
    failed.value = true
    message.value = e?.message || '操作失败'
  } finally {
    acting.value = false
  }
}

function onCancel() {
  run((body) => monitorApi.cancelTask(taskId.value.trim(), { idempotencyKey: idemKey('cancel'), ...body }))
}

function onRetry() {
  run((body) => monitorApi.retryTask(taskId.value.trim(), { idempotencyKey: idemKey('retry'), ...body }))
}

function onRecover() {
  run((body) => monitorApi.recoverLeases({ idempotencyKey: 'recover-leases-' + Date.now().toString(36), ...body }))
}
</script>

<style scoped>
.rab { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 12px 16px; }
.rab-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rab-title { font-size: 13px; font-weight: 600; color: #374151; margin-right: 4px; }
.rab-input { width: 120px; padding: 6px 10px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; }
.rab-msg { font-size: 12px; color: #059669; margin: 8px 0 0; }
.rab-msg.fail { color: #dc2626; }
</style>
