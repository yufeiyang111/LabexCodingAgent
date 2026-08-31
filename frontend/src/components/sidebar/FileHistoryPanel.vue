<template>
  <Teleport to="body">
    <Transition name="fhp" appear>
      <div v-if="visible" class="fhp-overlay" @click.self="$emit('close')">
        <div class="fhp-panel" role="dialog" aria-modal="true">
          <header class="fhp-header">
            <div class="fhp-title-wrap">
              <h3>时间线 · 改动历史</h3>
              <span class="fhp-path" :title="path">{{ path }}</span>
            </div>
            <button class="fhp-close" title="关闭 (Esc)" @click="$emit('close')">✕</button>
          </header>

          <div class="fhp-body">
            <div v-if="loading && items.length === 0" class="fhp-status">加载中…</div>
            <div v-else-if="error" class="fhp-status error">{{ error }}</div>
            <div v-else-if="items.length === 0" class="fhp-status">该文件还没有 Agent 改动记录</div>
            <template v-else>
              <button
                v-for="item in items"
                :key="item.changeId"
                class="fhp-item"
                :class="{ selected: item.changeId === selectedId }"
                @click="selectChange(item)"
              >
                <span class="fhp-type" :data-type="changeTypeKey(item)">{{ changeTypeLabel(item) }}</span>
                <span class="fhp-meta">
                  <span class="fhp-time">{{ formatTime(item.createTime) }}</span>
                  <span class="fhp-sub">{{ statusLabel(item) }}</span>
                </span>
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" :style="{ transform: selectedId === item.changeId ? '' : 'rotate(-90deg)' }"><polyline points="6 9 12 15 18 9"/></svg>
              </button>
              <div v-if="hasMore" class="fhp-more-row">
                <button v-if="!loading" class="fhp-more-btn" @click="loadPage(false)">加载更多</button>
                <span v-else class="fhp-status">加载中…</span>
              </div>
            </template>

            <div v-if="selectedId" class="fhp-diff-area">
              <div v-if="diffLoading" class="fhp-status">加载 diff…</div>
              <template v-else>
                <DiffViewer v-if="diffText" :diff="diffText" />
                <div v-else class="fhp-status">此改动没有可显示的 diff（可能是新建/删除操作）</div>
              </template>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import DiffViewer from '@/components/cloud/DiffViewer.vue'
import { projectApi } from '@/api'

/**
 * 文件时间线：展示当前文件的 Agent 改动历史（复用 t_agent_file_change 数据），
 * 点击条目按需加载单条 diff。列表只取元数据列，diff 单独请求，避免大字段拖垮首屏。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  projectId: { type: [Number, String], default: null },
  path: { type: String, default: '' }
})

const emit = defineEmits(['close'])

const PAGE_SIZE = 50

const loading = ref(false)
const error = ref('')
const items = ref([])
const total = ref(0)
const page = ref(1)
const selectedId = ref('')
const diffText = ref('')
const diffLoading = ref(false)

const hasMore = computed(() => items.value.length < total.value)

watch(() => [props.visible, props.path, props.projectId], ([visible]) => {
  if (!visible) return
  reset()
  loadPage(true)
})

function reset() {
  items.value = []
  total.value = 0
  page.value = 1
  selectedId.value = ''
  diffText.value = ''
  error.value = ''
}

async function loadPage(resetList) {
  if (!props.projectId || !props.path || loading.value) return
  loading.value = true
  try {
    if (resetList) page.value = 1
    const response = await projectApi.fileHistory(props.projectId, props.path, page.value, PAGE_SIZE)
    const data = response.data || {}
    total.value = Number(data.total) || 0
    const freshItems = data.items || []
    items.value = resetList ? freshItems : [...items.value, ...freshItems]
    page.value = Number(data.page) || page.value
    if (!resetList) page.value++
  } catch (err) {
    error.value = err?.response?.data?.message || err?.message || '历史加载失败'
  } finally {
    loading.value = false
  }
}

async function selectChange(item) {
  if (selectedId.value === item.changeId) {
    selectedId.value = ''
    diffText.value = ''
    return
  }
  selectedId.value = item.changeId
  diffText.value = ''
  diffLoading.value = true
  try {
    const response = await projectApi.fileHistoryDiff(props.projectId, item.changeId)
    diffText.value = response.data?.diff || ''
  } catch (err) {
    diffText.value = ''
    error.value = err?.response?.data?.message || err?.message || 'diff 加载失败'
  } finally {
    diffLoading.value = false
  }
}

function changeTypeKey(item) {
  const type = String(item.changeType || '').toLowerCase()
  if (type.includes('create') || type.includes('add')) return 'created'
  if (type.includes('delete') || type.includes('remove')) return 'deleted'
  return 'modified'
}

function changeTypeLabel(item) {
  const key = changeTypeKey(item)
  return key === 'created' ? '新建' : key === 'deleted' ? '删除' : '修改'
}

function statusLabel(item) {
  const status = String(item.status || '').toLowerCase()
  if (status === 'applied') return '已应用'
  if (status === 'pending') return '待确认'
  if (status === 'rejected') return '已拒绝'
  if (status === 'undone') return '已回滚'
  return item.status || ''
}

function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.fhp-overlay { position: fixed; inset: 0; z-index: 9990; background: rgba(15,15,20,0.45); display: flex; align-items: center; justify-content: center; }
.fhp-panel { background: #fff; border-radius: 14px; box-shadow: 0 20px 60px rgba(0,0,0,0.25); width: min(720px, calc(100vw - 48px)); height: min(560px, calc(100vh - 96px)); display: flex; flex-direction: column; overflow: hidden; }
.fhp-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px 10px; border-bottom: 1px solid #eef0f3; }
.fhp-title-wrap { min-width: 0; }
.fhp-header h3 { margin: 0; font-size: 15px; color: #18181b; }
.fhp-path { display: block; font-size: 12px; color: #9ca3af; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 520px; direction: rtl; text-align: left; }
.fhp-close { border: none; background: transparent; font-size: 14px; color: #9ca3af; cursor: pointer; padding: 4px 8px; border-radius: 6px; flex-shrink: 0; }
.fhp-close:hover { background: #f4f4f5; color: #18181b; }
.fhp-body { flex: 1; overflow-y: auto; padding: 10px 14px 16px; }
.fhp-status { padding: 16px 8px; text-align: center; color: #9ca3af; font-size: 12.5px; }
.fhp-status.error { color: #ef4444; word-break: break-all; }
.fhp-item { display: flex; align-items: center; gap: 10px; width: 100%; border: none; background: none; padding: 9px 10px; border-radius: 8px; cursor: pointer; text-align: left; transition: background 0.12s; }
.fhp-item:hover { background: #f7f7f8; }
.fhp-item.selected { background: #eef2ff; }
.fhp-type { font-size: 11px; padding: 2px 9px; border-radius: 999px; flex-shrink: 0; }
.fhp-type[data-type="created"] { background: #dcfce7; color: #15803d; }
.fhp-type[data-type="modified"] { background: #dbeafe; color: #1d4ed8; }
.fhp-type[data-type="deleted"] { background: #fee2e2; color: #b91c1c; }
.fhp-meta { flex: 1; display: flex; align-items: baseline; gap: 10px; min-width: 0; }
.fhp-time { font-size: 12.5px; color: #374151; font-variant-numeric: tabular-nums; }
.fhp-sub { font-size: 11px; color: #a1a1aa; }
.fhp-item svg { color: #c4c8d0; flex-shrink: 0; transition: transform 0.15s ease; }
.fhp-more-row { text-align: center; padding: 8px 0 2px; }
.fhp-more-btn { border: 1px solid #e4e4e7; background: #fff; border-radius: 8px; padding: 6px 16px; font: inherit; font-size: 12px; cursor: pointer; color: #374151; }
.fhp-more-btn:hover { background: #f4f4f5; }
.fhp-diff-area { margin-top: 10px; border-top: 1px dashed #ececf0; padding-top: 10px; }

:global([data-theme="dark"]) .fhp-panel { background: #1b1c2e; }
:global([data-theme="dark"]) .fhp-header { border-bottom-color: #383a50; }
:global([data-theme="dark"]) .fhp-header h3 { color: #c0caf5; }
:global([data-theme="dark"]) .fhp-close:hover { background: #282a3a; color: #c0caf5; }
:global([data-theme="dark"]) .fhp-item:hover { background: #23253a; }
:global([data-theme="dark"]) .fhp-item.selected { background: #24305a; }
:global([data-theme="dark"]) .fhp-time { color: #a9b1d6; }
:global([data-theme="dark"]) .fhp-more-btn { background: #1f2033; border-color: #383a50; color: #a9b1d6; }

.fhp-enter-active, .fhp-leave-active { transition: opacity 0.16s ease; }
.fhp-enter-from, .fhp-leave-to { opacity: 0; }
</style>
