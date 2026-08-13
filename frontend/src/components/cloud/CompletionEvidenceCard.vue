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
const title = computed(() => props.evidence?.satisfied ? '服务器完成证据已满足' : '服务器拒绝完成：证据不足')
const changed = computed(() => list('changedFiles'))
const passed = computed(() => list('successfulVerifications'))
const failed = computed(() => list('failedVerifications'))
const environment = computed(() => list('environmentVerifications'))
const risks = computed(() => list('unresolvedRisks'))
const list = key => Array.isArray(props.evidence?.[key]) ? props.evidence[key].filter(Boolean) : []
</script>

<style scoped>
.completion-evidence { margin: 10px 0; padding: 13px; border: 1px solid #557064; border-radius: 11px; background: #202b28; color: #dfeae5; }.completion-evidence.blocked { border-color:#9c654e; background:#312620; color:#f1dfd5; }.completion-evidence header { display:flex; align-items:center; gap:8px; }.completion-evidence header span { width:22px; height:22px; display:grid; place-items:center; border-radius:50%; background:#43836a; color:white; font-weight:800; }.completion-evidence.blocked header span { background:#d56b3e; }.evidence-grid { display:grid; grid-template-columns:repeat(2,minmax(140px,1fr)); gap:9px; margin-top:11px; }.evidence-grid section { padding:9px; border-radius:8px; background:#ffffff09; }.evidence-grid h4 { margin:0 0 6px; font-size:12px; }.evidence-grid p,.evidence-grid ul { margin:0; color:#b9c8c1; font-size:11px; line-height:1.6; }.evidence-grid ul { padding-left:17px; }.evidence-grid li { overflow-wrap:break-word; word-break:break-all; }.evidence-grid .danger p,.evidence-grid .danger ul { color:#efb29a; }.evidence-grid .warning p,.evidence-grid .warning ul { color:#e4c07c; }
@media (max-width:560px) { .evidence-grid { grid-template-columns:1fr; } }
</style>
