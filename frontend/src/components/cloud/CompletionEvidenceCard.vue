<template>
  <section class="completion-evidence" :class="evidence.satisfied ? 'satisfied' : 'blocked'" :aria-label="title">
    <header><span aria-hidden="true">{{ evidence.satisfied ? '✓' : '!' }}</span><strong>{{ title }}</strong></header>
    <div class="evidence-grid">
      <section><h4>改动文件</h4><ul v-if="changed.length"><li v-for="item in changed" :key="item">{{ item }}</li></ul><p v-else>无文件改动（信息型任务）</p></section>
      <section><h4>成功验证</h4><ul v-if="passed.length"><li v-for="item in passed" :key="item">{{ item }}</li></ul><p v-else>暂无成功验证记录</p></section>
      <section v-if="failed.length" class="danger"><h4>失败验证</h4><ul><li v-for="item in failed" :key="item">{{ item }}</li></ul></section>
      <section v-if="environment.length" class="warning"><h4>环境受阻验证（不计为通过）</h4><ul><li v-for="item in environment" :key="item">{{ item }}</li></ul></section>
      <section v-if="risks.length" class="danger"><h4>未解决风险</h4><ul><li v-for="item in risks" :key="item">{{ item }}</li></ul></section>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
const props = defineProps({ evidence: { type: Object, required: true } })
const title = computed(() => {
  if (props.evidence?.satisfied) return '服务器完成证据已满足'
  return props.evidence?.finalResponseVisible
    ? '服务器验证未满足（已展示模型答复）'
    : '服务器拒绝完成：证据不足'
})
const changed = computed(() => list('changedFiles'))
const passed = computed(() => list('successfulVerifications'))
const failed = computed(() => list('failedVerifications'))
const environment = computed(() => list('environmentVerifications'))
const risks = computed(() => list('unresolvedRisks'))
const list = key => Array.isArray(props.evidence?.[key]) ? props.evidence[key].filter(Boolean) : []
</script>

<style scoped>
.completion-evidence {
  margin: 10px 0;
  padding: 13px;
  border: 1px solid #86efac;
  border-radius: 10px;
  background: var(--ai-bg, #ffffff);
  color: var(--ai-text, #0f172a);
  box-shadow: var(--ai-shadow-sm, 0 1px 3px rgba(0, 0, 0, 0.03));
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.completion-evidence:hover {
  border-color: #4ade80;
  box-shadow: 0 4px 16px -2px rgba(34, 197, 94, 0.12), 0 2px 6px -1px rgba(0, 0, 0, 0.04);
  transform: translateY(-1px);
}
.completion-evidence.blocked {
  border-color: #fca5a5;
  background: var(--ai-bg, #ffffff);
  color: var(--ai-text, #0f172a);
}
.completion-evidence.blocked:hover {
  border-color: #f87171;
  box-shadow: 0 4px 16px -2px rgba(239, 68, 68, 0.12), 0 2px 6px -1px rgba(0, 0, 0, 0.04);
}
.completion-evidence header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.completion-evidence header span {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #16a34a;
  color: white;
  font-weight: 800;
}
.completion-evidence.blocked header span {
  background: #dc2626;
}
.evidence-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(140px, 1fr));
  gap: 9px;
  margin-top: 11px;
}
.evidence-grid section {
  padding: 9px;
  border-radius: 8px;
  background: var(--ai-bg-tertiary, #f8fafc);
  border: 1px solid var(--ai-border, #f1f5f9);
}
.evidence-grid h4 {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--ai-text, #334155);
}
.evidence-grid p,
.evidence-grid ul {
  margin: 0;
  color: var(--ai-text-muted, #64748b);
  font-size: 11px;
  line-height: 1.6;
}
.evidence-grid ul {
  padding-left: 17px;
}
.evidence-grid li {
  overflow-wrap: break-word;
  word-break: break-all;
}
.evidence-grid .danger {
  background: #fef2f2;
  border-color: #fee2e2;
}
.evidence-grid .danger h4 {
  color: #991b1b;
}
.evidence-grid .danger p,
.evidence-grid .danger ul {
  color: #b91c1c;
}
.evidence-grid .warning {
  background: #fffbeb;
  border-color: #fef3c7;
}
.evidence-grid .warning h4 {
  color: #92400e;
}
.evidence-grid .warning p,
.evidence-grid .warning ul {
  color: #b45309;
}

/* 暗色主题增强 */
:root[data-theme='dark'] .completion-evidence {
  background: #1e1e2e;
  border-color: #059669;
  color: #cdd6f4;
}
:root[data-theme='dark'] .completion-evidence.blocked {
  background: #1e1e2e;
  border-color: #e11d48;
  color: #cdd6f4;
}
:root[data-theme='dark'] .evidence-grid section {
  background: #181825;
  border-color: #313244;
}
:root[data-theme='dark'] .evidence-grid h4 {
  color: #cdd6f4;
}
:root[data-theme='dark'] .evidence-grid p,
:root[data-theme='dark'] .evidence-grid ul {
  color: #a6adc8;
}
:root[data-theme='dark'] .evidence-grid .danger {
  background: rgba(243, 139, 168, 0.12);
  border-color: rgba(243, 139, 168, 0.3);
}
:root[data-theme='dark'] .evidence-grid .danger h4 {
  color: #f38ba8;
}
:root[data-theme='dark'] .evidence-grid .danger p,
:root[data-theme='dark'] .evidence-grid .danger ul {
  color: #f5c2e7;
}
:root[data-theme='dark'] .evidence-grid .warning {
  background: rgba(249, 226, 175, 0.12);
  border-color: rgba(249, 226, 175, 0.3);
}
:root[data-theme='dark'] .evidence-grid .warning h4 {
  color: #f9e2af;
}
:root[data-theme='dark'] .evidence-grid .warning p,
:root[data-theme='dark'] .evidence-grid .warning ul {
  color: #fab387;
}

@media (max-width: 560px) {
  .evidence-grid {
    grid-template-columns: 1fr;
  }
}
</style>
