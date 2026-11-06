<template>
  <pre v-if="lines.length" class="diff-lines"><template v-for="(line, index) in lines" :key="index"><span class="diff-line" :class="'diff-' + line.type">{{ line.text }}</span></template></pre>
  <div v-else class="diff-lines-empty">{{ emptyLabel }}</div>
</template>

<script setup>
// 唯一职责：把统一 diff 文本渲染成带增删配色的行。
// 两个 git 面板都要显示 diff，逻辑与配色必须一致，因此单独成一个组件。
import { computed } from 'vue'

import { diffToLines } from '@/composables/git/sourceControlModel'

const props = defineProps({
  diff: { type: String, default: '' },
  emptyLabel: { type: String, default: '没有可显示的 diff' }
})

const lines = computed(() => diffToLines(props.diff))
</script>

<style scoped>
.diff-lines {
  margin: 0;
  padding: 0 0 6px;
  overflow: auto;
  font-family: var(--wabi-font-mono);
  font-size: 11px;
  line-height: 1.5;
}
.diff-line {
  display: block;
  padding: 0 12px;
  white-space: pre;
}
.diff-add { background: var(--wabi-green-light); color: var(--wabi-text); }
.diff-del { background: var(--wabi-danger-light); color: var(--wabi-text); }
.diff-meta { color: var(--wabi-text-muted); }
.diff-hunk { color: #6b7f9e; }
.diff-context { color: var(--wabi-text-secondary); }
.diff-lines-empty {
  padding: 8px 12px 10px;
  color: var(--wabi-text-muted);
  font-size: 11px;
}
html[data-theme='dark'] .diff-add { background: rgba(34, 197, 94, 0.12); color: #86efac; }
html[data-theme='dark'] .diff-del { background: rgba(239, 68, 68, 0.12); color: #fca5a5; }
html[data-theme='dark'] .diff-meta { color: #818cf8; }
html[data-theme='dark'] .diff-hunk { color: #38bdf8; }
html[data-theme='dark'] .diff-context { color: #a1a1aa; }
html[data-theme='dark'] .diff-lines-empty { color: #71717a; }
</style>
