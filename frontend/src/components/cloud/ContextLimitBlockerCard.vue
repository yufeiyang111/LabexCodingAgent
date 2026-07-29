<template>
  <section class="context-limit-card" role="alert" aria-live="assertive">
    <div class="context-limit-heading">
      <span class="context-limit-icon" aria-hidden="true">!</span>
      <div>
        <strong>上下文容量阻塞</strong>
        <p>{{ blocker.message || '当前模型无法容纳本轮上下文。' }}</p>
      </div>
    </div>
    <dl v-if="budget" class="context-limit-budget">
      <div><dt>静态上下文</dt><dd>{{ format(budget.staticTokens) }}</dd></div>
      <div><dt>可压缩上下文</dt><dd>{{ format(budget.reducibleTokens) }}</dd></div>
      <div><dt>预留输出</dt><dd>{{ format(budget.reservedOutputTokens) }}</dd></div>
      <div><dt>输入容量</dt><dd>{{ format(budget.inputCapacityTokens) }}</dd></div>
    </dl>
    <div v-if="remediation.length" class="context-limit-remediation">
      <span>需要先调整配置：</span>
      <ul><li v-for="item in remediation" :key="item">{{ item }}</li></ul>
    </div>
    <button v-if="taskId" type="button" :disabled="retrying" @click="$emit('retry')">
      {{ retrying ? '正在恢复任务...' : '配置调整后重试' }}
    </button>
  </section>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ blocker: { type: Object, required: true }, taskId: { type: [Number, String], default: null }, retrying: Boolean })
defineEmits(['retry'])
const budget = computed(() => props.blocker?.budget || null)
const remediation = computed(() => Array.isArray(props.blocker?.remediation) ? props.blocker.remediation.filter(Boolean) : [])
const format = value => Number.isFinite(Number(value)) ? `${Number(value).toLocaleString('zh-CN')} tokens` : '—'
</script>
<style scoped>
.context-limit-card { margin:4px 0 12px;padding:15px;border:1px solid #e18a52;border-radius:12px;background:linear-gradient(135deg,#3b2b22,#2a2524);color:#f7ede7;box-shadow:0 12px 28px #0005}.context-limit-heading{display:flex;gap:11px;align-items:flex-start}.context-limit-heading strong{display:block;font-size:15px}.context-limit-heading p{margin:5px 0 0;color:#e8c7b4;line-height:1.55}.context-limit-icon{flex:0 0 25px;height:25px;display:grid;place-items:center;border-radius:50%;background:#e47b45;color:#fff;font-weight:800}.context-limit-budget{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:13px 0 0}.context-limit-budget div{padding:9px 10px;border-radius:8px;background:#ffffff0b}.context-limit-budget dt{color:#bca99e;font-size:11px}.context-limit-budget dd{margin:4px 0 0;font-weight:700;font-variant-numeric:tabular-nums}.context-limit-remediation{margin-top:12px;color:#dcc8bd;font-size:12px}.context-limit-remediation ul{margin:6px 0 0;padding-left:19px;line-height:1.7}button{margin-top:13px;border:1px solid #f0a476;border-radius:8px;background:#d96d38;color:#fff;padding:8px 13px;cursor:pointer}button:disabled{opacity:.6;cursor:wait}@media(max-width:560px){.context-limit-budget{grid-template-columns:1fr}}
</style>
