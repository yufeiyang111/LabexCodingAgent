<template>
  <div class="sp-panel">
    <div class="sp-input-row">
      <div class="sp-input-wrap">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.2" y2="16.2"/></svg>
        <input
          v-model="keyword"
          class="sp-input"
          type="text"
          placeholder="在文件夹中查找…"
          @keyup.enter="runSearch()"
        />
        <button v-if="keyword" class="sp-clear" title="清空" @click="reset">✕</button>
      </div>
    </div>
    <div class="sp-options">
      <label class="sp-option" title="区分大小写">
        <input v-model="caseSensitive" type="checkbox" /> Aa
      </label>
      <label class="sp-option" title="正则表达式">
        <input v-model="regex" type="checkbox" /> .*
      </label>
      <input
        v-model="include"
        class="sp-include"
        type="text"
        placeholder="仅包含文件（如 .java）"
      />
    </div>

    <div class="sp-results">
      <div v-if="searching" class="sp-status">搜索中…</div>
      <div v-else-if="error" class="sp-status error">{{ error }}</div>
      <div v-else-if="!searched" class="sp-status hint">输入关键词开始搜索（跳过 node_modules 等依赖目录）</div>
      <div v-else-if="grouped.length === 0" class="sp-status">无匹配结果</div>
      <template v-for="group in grouped" :key="group.path">
        <div class="sp-file-header" :title="group.path">
          <span class="sp-file-name">{{ fileNameOf(group.path) }}</span>
          <span class="sp-file-path">{{ parentPathOf(group.path) }}</span>
          <span class="sp-file-count">{{ group.lines.length }}</span>
        </div>
        <button
          v-for="(hit, hi) in group.lines"
          :key="group.path + ':' + hi"
          class="sp-hit"
          @click="$emit('open', { path: group.path, line: hit.line })"
        >
          <span class="sp-hit-line">{{ hit.line }}</span>
          <span class="sp-hit-text">{{ hit.text }}</span>
        </button>
      </template>
      <div v-if="truncated" class="sp-truncated">结果已达上限，仅显示前 {{ hits.length }} 条</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fileNameOf, parentPathOf } from '@/constants/workspaceFiles'

/** 在文件夹中查找：防抖触发后端受限搜索，结果按文件分组，点击跳转到对应行。 */
const props = defineProps({
  projectId: { type: [Number, String], default: null },
  dir: { type: String, default: '' }
})

const emit = defineEmits(['open'])

const SEARCH_DEBOUNCE_MS = 350
const MIN_KEYWORD_LENGTH = 1

const keyword = ref('')
const caseSensitive = ref(false)
const regex = ref(false)
const include = ref('')
const searching = ref(false)
const searched = ref(false)
const error = ref('')
const hits = ref([])
const truncated = ref(false)

let debounceTimer = null
let searchSeq = 0

const grouped = computed(() => {
  const groups = new Map()
  hits.value.forEach(hit => {
    if (!groups.has(hit.path)) groups.set(hit.path, { path: hit.path, lines: [] })
    groups.get(hit.path).lines.push({ line: hit.line, text: hit.text })
  })
  return [...groups.values()]
})

watch([keyword, caseSensitive, regex, include], () => {
  clearTimeout(debounceTimer)
  if (!keyword.value.trim()) {
    reset()
    return
  }
  debounceTimer = setTimeout(() => runSearch(), SEARCH_DEBOUNCE_MS)
})

async function runSearch() {
  const trimmed = keyword.value.trim()
  const projectId = props.projectId
  if (!projectId || trimmed.length < MIN_KEYWORD_LENGTH) return
  const seq = ++searchSeq
  searching.value = true
  error.value = ''
  try {
    const response = await projectApiSafe().searchFiles(projectId, trimmed, {
      dir: props.dir,
      caseSensitive: caseSensitive.value,
      regex: regex.value,
      include: include.value.trim() || undefined,
      silent: true
    })
    if (seq !== searchSeq) return
    hits.value = response.data?.hits || []
    truncated.value = Boolean(response.data?.truncated)
    searched.value = true
  } catch (err) {
    if (seq !== searchSeq) return
    error.value = err?.response?.data?.message || err?.message || '搜索失败'
    hits.value = []
    searched.value = true
  } finally {
    if (seq === searchSeq) searching.value = false
  }
}

function reset() {
  clearTimeout(debounceTimer)
  keyword.value = ''
  hits.value = []
  searched.value = false
  truncated.value = false
  error.value = ''
  searchSeq++
}

onBeforeUnmount(() => {
  clearTimeout(debounceTimer)
})

import { projectApi } from '@/api'
function projectApiSafe() {
  return projectApi
}
</script>

<style scoped>
.sp-panel { display: flex; flex-direction: column; height: 100%; min-height: 0; flex: 1; overflow: hidden; font-size: 13px; user-select: none; }
.sp-input-row { padding: 8px 10px 4px; }
.sp-input-wrap { display: flex; align-items: center; gap: 6px; border: 1px solid #e4e4e7; border-radius: 8px; padding: 5px 8px; background: #fafafa; color: #71717a; }
.sp-input-wrap:focus-within { border-color: #6366f1; background: #fff; }
.sp-input { flex: 1; border: none; outline: none; background: transparent; font: inherit; font-size: 12.5px; color: #18181b; min-width: 0; }
.sp-clear { border: none; background: transparent; color: #9ca3af; cursor: pointer; padding: 0 2px; font-size: 11px; }
.sp-clear:hover { color: #ef4444; }
.sp-options { display: flex; align-items: center; gap: 10px; padding: 4px 12px 8px; }
.sp-option { display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px; color: #6b7280; cursor: pointer; }
.sp-option input { accent-color: #6366f1; }
.sp-include { flex: 1; min-width: 0; border: none; border-bottom: 1px dashed #d4d4d8; background: transparent; font-size: 11.5px; color: #374151; padding: 3px 0; outline: none; }
.sp-results { flex: 1; overflow-y: auto; min-height: 0; padding: 0 4px 8px; }
.sp-status { padding: 14px 10px; text-align: center; color: #9ca3af; font-size: 12px; }
.sp-status.error { color: #ef4444; word-break: break-all; }
.sp-file-header { display: flex; align-items: center; gap: 6px; padding: 6px 8px 2px; margin-top: 4px; }
.sp-file-name { font-weight: 600; color: #18181b; font-size: 12px; }
.sp-file-path { color: #a1a1aa; font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; direction: rtl; text-align: left; }
.sp-file-count { font-size: 10px; color: #6366f1; background: #eef2ff; border-radius: 999px; padding: 1px 7px; }
.sp-hit { display: flex; gap: 8px; width: calc(100% - 8px); margin: 1px 4px; padding: 3px 8px 3px 14px; border: none; background: none; border-radius: 6px; cursor: pointer; font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace; font-size: 11.5px; color: #3f3f46; text-align: left; transition: background 0.1s; }
.sp-hit:hover { background: #f4f4f5; }
.sp-hit-line { color: #a1a1aa; min-width: 28px; text-align: right; flex-shrink: 0; }
.sp-hit-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

:global(.ws-dark) .sp-input-wrap,
:global([data-theme="dark"]) .sp-input-wrap { background: #171827; border-color: #383a50; color: #787c99; }
:global(.ws-dark) .sp-input,
:global([data-theme="dark"]) .sp-input { color: #c0caf5; }
:global(.ws-dark) .sp-option,
:global([data-theme="dark"]) .sp-option { color: #787c99; }
:global(.ws-dark) .sp-include,
:global([data-theme="dark"]) .sp-include { color: #a9b1d6; border-color: #383a50; }
:global(.ws-dark) .sp-hit:hover,
:global([data-theme="dark"]) .sp-hit:hover { background: #282a3a; }
:global(.ws-dark) .sp-hit,
:global([data-theme="dark"]) .sp-hit { color: #a9b1d6; }
:global(.ws-dark) .sp-file-name,
:global([data-theme="dark"]) .sp-file-name { color: #c0caf5; }
:global(.ws-dark) .sp-file-count,
:global([data-theme="dark"]) .sp-file-count { background: #2e3a5e; color: #7aa2f7; }
</style>
