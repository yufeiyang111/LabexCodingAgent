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
.context-limit-card {
  margin: 4px 0 12px;
  padding: 15px;
  border: 1px solid #fdba74;
  border-radius: 10px;
  background: #ffffff;
  color: #0f172a;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.context-limit-card:hover {
  border-color: #fb923c;
  box-shadow: 0 4px 16px -2px rgba(249, 115, 22, 0.12), 0 2px 6px -1px rgba(0, 0, 0, 0.04);
  transform: translateY(-1px);
}
.context-limit-heading {
  display: flex;
  gap: 11px;
  align-items: flex-start;
}
.context-limit-heading strong {
  display: block;
  font-size: 14px;
  color: #9a3412;
}
.context-limit-heading p {
  margin: 4px 0 0;
  color: #475569;
  line-height: 1.55;
  font-size: 12.5px;
}
.context-limit-icon {
  flex: 0 0 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #ea580c;
  color: #fff;
  font-weight: 800;
  font-size: 13px;
}
.context-limit-budget {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin: 12px 0 0;
}
.context-limit-budget div {
  padding: 8px 10px;
  border-radius: 8px;
  background: #fff7ed;
  border: 1px solid #ffedd5;
}
.context-limit-budget dt {
  color: #9a3412;
  font-size: 11px;
}
.context-limit-budget dd {
  margin: 3px 0 0;
  font-weight: 700;
  color: #c2410c;
  font-variant-numeric: tabular-nums;
  font-size: 13px;
}
.context-limit-remediation {
  margin-top: 12px;
  color: #475569;
  font-size: 12px;
}
.context-limit-remediation ul {
  margin: 6px 0 0;
  padding-left: 19px;
  line-height: 1.7;
}
button {
  margin-top: 13px;
  border: 1px solid #ea580c;
  border-radius: 6px;
  background: #ea580c;
  color: #fff;
  padding: 6px 14px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}
button:hover:not(:disabled) {
  background: #c2410c;
  border-color: #c2410c;
}
button:disabled {
  opacity: 0.6;
  cursor: wait;
}
@media (max-width: 560px) {
  .context-limit-budget {
    grid-template-columns: 1fr;
  }
}
</style>
