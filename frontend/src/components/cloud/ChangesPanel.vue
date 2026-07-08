<template>
  <div class="changes-panel">
    <div class="cp-header">
      <div class="cp-title">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#6366f1" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        <span>Modified Files</span>
      </div>
      <span class="cp-count" v-if="allChanges.length > 0">{{ allChanges.length }} files</span>
    </div>

    <div v-if="allChanges.length === 0" class="cp-empty">
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#555" stroke-width="1.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
      <span>No file changes yet</span>
    </div>

    <div v-else class="cp-file-list">
      <div v-for="(change, i) in allChanges" :key="change.changeId || i" class="cp-file-row" :class="{ active: expandedId === (change.changeId || i) }" @click="toggleFile(change)">
        <span class="cp-status-icon" :class="'cp-' + (change.changeType || 'modify')">
          <svg v-if="change.changeType === 'create'" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          <svg v-else-if="change.changeType === 'delete'" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><line x1="5" y1="12" x2="19" y2="12"/></svg>
          <svg v-else width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
        </span>
        <span class="cp-file-name" :title="change.relativePath || change.file">{{ shortenPath(change.relativePath || change.file) }}</span>
        <span class="cp-status-badge" :class="'cp-status-' + (change.status || 'applied')">{{ change.status || 'applied' }}</span>
        <span v-if="change.snapshotStatus === 'captured'" class="cp-snapshot-badge" title="Git snapshot rollback enabled">Git</span>
        <button class="cp-undo-btn" v-if="change.status === 'applied'" @click.stop="undoChange(change)" title="Undo this change">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/></svg>
        </button>
      </div>
    </div>

    <!-- Expanded detail -->
    <Transition name="cp-slide">
      <div v-if="expandedChange" class="cp-detail">
        <div class="cp-detail-header">
          <span class="cp-detail-path">{{ expandedChange.relativePath || expandedChange.file }}</span>
          <div class="cp-detail-actions">
            <button class="cp-detail-btn" :class="{ active: detailMode === 'diff' }" @click="detailMode = 'diff'">Diff</button>
            <button class="cp-detail-btn" :class="{ active: detailMode === 'after' }" @click="detailMode = 'after'">After</button>
            <button class="cp-detail-btn" :class="{ active: detailMode === 'before' }" @click="detailMode = 'before'">Before</button>
          </div>
        </div>
        <div class="cp-code-wrap">
          <!-- Diff view -->
          <template v-if="detailMode === 'diff'">
            <pre v-if="expandedChange.diff" class="cp-code-block"><template v-for="(line, li) in parseDiff(expandedChange.diff)" :key="li"><span class="cp-code-line" :class="'cp-type-' + line.type"><span class="cp-ln">{{ line.oldLn || '' }}</span><span class="cp-ln">{{ line.newLn || '' }}</span><span class="cp-code-text">{{ line.prefix }}{{ line.text }}</span></span></template></pre>
            <div v-else class="cp-no-diff">No diff available</div>
          </template>
          <!-- After view (current content with highlights) -->
          <template v-else-if="detailMode === 'after'">
            <pre v-if="expandedChange.afterContent" class="cp-code-block"><template v-for="(line, li) in afterLines" :key="li"><span class="cp-code-line" :class="'cp-type-' + line.type"><span class="cp-ln">{{ li + 1 }}</span><span class="cp-code-text">{{ line.text }}</span></span></template></pre>
            <div v-else class="cp-no-diff">No content available</div>
          </template>
          <!-- Before view (original content with highlights) -->
          <template v-else-if="detailMode === 'before'">
            <pre v-if="expandedChange.beforeContent" class="cp-code-block"><template v-for="(line, li) in beforeLines" :key="li"><span class="cp-code-line" :class="'cp-type-' + line.type"><span class="cp-ln">{{ li + 1 }}</span><span class="cp-code-text">{{ line.text }}</span></span></template></pre>
            <div v-else class="cp-no-diff">No original content available</div>
          </template>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { projectApi } from '@/api'
import { ElMessageBox, ElMessage } from 'element-plus'

const props = defineProps({
  changes: { type: Array, default: () => [] },
  projectId: { type: [Number, String], default: null },
  refreshKey: { type: [Number, String], default: 0 }
})

const emit = defineEmits(['revert', 'undo'])

const expandedId = ref(null)
const expandedChange = ref(null)
const detailMode = ref('diff')
const backendChanges = ref([])

const allChanges = computed(() => {
  const merged = [...backendChanges.value]
  for (const c of props.changes) {
    if (!merged.find(m => (m.relativePath || m.file) === c.file)) {
      merged.push({
        changeId: null,
        relativePath: c.file,
        file: c.file,
        changeType: c.status || 'modify',
        diff: c.patch,
        beforeContent: '',
        afterContent: '',
        status: 'applied',
        additions: c.additions,
        deletions: c.deletions
      })
    }
  }
  return merged
})

async function loadBackendChanges() {
  if (props.projectId) {
    try {
      const r = await projectApi.agentChanges(props.projectId)
      backendChanges.value = r.data || []
    } catch (e) { /* ignore */ }
  }
}

onMounted(loadBackendChanges)

watch(() => props.refreshKey, loadBackendChanges)

function toggleFile(change) {
  const id = change.changeId || change.file
  if (expandedId.value === id) {
    expandedId.value = null
    expandedChange.value = null
  } else {
    expandedId.value = id
    expandedChange.value = change
    detailMode.value = 'diff'
  }
}

function shortenPath(p) {
  if (!p) return ''
  const parts = p.replace(/\\/g, '/').split('/')
  if (parts.length <= 2) return p
  return '...' + '/' + parts.slice(-2).join('/')
}

const afterLines = computed(() => {
  if (!expandedChange.value) return []
  return parseContent(expandedChange.value.afterContent, expandedChange.value.diff, 'after')
})

const beforeLines = computed(() => {
  if (!expandedChange.value) return []
  return parseContent(expandedChange.value.beforeContent, expandedChange.value.diff, 'before')
})

function parseDiff(diff) {
  if (!diff) return []
  const lines = diff.split('\n')
  const result = []
  let oldLn = 0, newLn = 0
  for (const line of lines) {
    if (line.startsWith('@@')) {
      const m = line.match(/@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/)
      if (m) { oldLn = parseInt(m[1]); newLn = parseInt(m[2]) }
      result.push({ type: 'hunk', text: line, prefix: '', oldLn: '', newLn: '' })
    } else if (line.startsWith('---') || line.startsWith('+++') || line.startsWith('diff ')) {
      continue
    } else if (line.startsWith('+')) {
      result.push({ type: 'add', text: line.slice(1), prefix: '+', oldLn: '', newLn: newLn++ })
    } else if (line.startsWith('-')) {
      result.push({ type: 'del', text: line.slice(1), prefix: '-', oldLn: oldLn++, newLn: '' })
    } else if (line.startsWith(' ')) {
      result.push({ type: 'ctx', text: line.slice(1), prefix: ' ', oldLn: oldLn++, newLn: newLn++ })
    } else {
      result.push({ type: 'ctx', text: line, prefix: '', oldLn: '', newLn: '' })
    }
  }
  return result
}

function parseContent(content, diff, mode) {
  if (!content) return []
  const lines = content.split('\n')
  const addedLines = new Set()
  const removedLines = new Set()
  if (diff) {
    let ln = 0
    for (const line of diff.split('\n')) {
      if (line.startsWith('@@')) {
        const m = line.match(/@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/)
        if (m) ln = mode === 'before' ? parseInt(m[1]) : parseInt(m[2])
      } else if (line.startsWith('+') && mode === 'after') {
        addedLines.add(ln++)
      } else if (line.startsWith('-') && mode === 'before') {
        removedLines.add(ln++)
      } else if (line.startsWith(' ')) {
        ln++
      } else if (line.startsWith('+') && mode === 'before') {
        // skip added lines in before view
      } else if (line.startsWith('-') && mode === 'after') {
        // skip removed lines in after view
      }
    }
  }
  return lines.map((text, i) => {
    let type = 'ctx'
    if (mode === 'after' && addedLines.has(i)) type = 'add'
    if (mode === 'before' && removedLines.has(i)) type = 'del'
    return { text, type }
  })
}

async function undoChange(change) {
  if (!change.changeId) {
    ElMessage.warning('Cannot undo: no change ID')
    return
  }
  try {
    await ElMessageBox.confirm(
        `Undo change to "${shortenPath(change.relativePath || change.file)}"?`,
      'Confirm Undo',
      { confirmButtonText: 'Undo', cancelButtonText: 'Cancel', type: 'warning' }
    )
    await projectApi.agentUndoDiff(props.projectId, change.changeId)
    backendChanges.value = backendChanges.value.filter(c => c.changeId !== change.changeId)
    expandedId.value = null
    expandedChange.value = null
    ElMessage.success('Change undone')
    emit('undo', change)
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('Undo failed: ' + (e?.response?.data?.message || e?.message))
  }
}
</script>

<style scoped>
.changes-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #1e1e1e;
}
.cp-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid #333;
  flex-shrink: 0;
}
.cp-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 600;
  color: #ccc;
}
.cp-count { font-size: 10px; color: #666; }
.cp-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #555;
  font-size: 12px;
}
.cp-file-list {
  flex-shrink: 1;
  min-height: 0;
  max-height: 40%;
  overflow-y: auto;
  border-bottom: 1px solid #333;
}
.cp-file-row {
  display: flex; align-items: center; gap: 6px;
  padding: 4px 10px; cursor: pointer;
  transition: background 0.1s; font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
}
.cp-file-row:hover { background: #2a2d2e; }
.cp-file-row.active { background: #37373d; }
.cp-status-icon {
  width: 14px; height: 14px; display: flex; align-items: center;
  justify-content: center; border-radius: 3px; flex-shrink: 0;
}
.cp-create { background: #1e7e34; color: #fff; }
.cp-modify { background: #c08a30; color: #fff; }
.cp-delete { background: #c24038; color: #fff; }
.cp-file-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #ccc; }
.cp-status-badge {
  font-size: 9px; padding: 1px 6px; border-radius: 3px;
  text-transform: uppercase; letter-spacing: 0.3px; flex-shrink: 0;
}
.cp-status-applied { background: #1e7e34; color: #fff; }
.cp-status-pending { background: #c08a30; color: #fff; }
.cp-status-undone { background: #666; color: #ccc; }
.cp-snapshot-badge {
  font-size: 9px;
  padding: 1px 5px;
  border-radius: 3px;
  background: #16324f;
  color: #8bc4ff;
  border: 1px solid #245f92;
  flex-shrink: 0;
}
.cp-undo-btn {
  width: 18px; height: 18px; display: none; align-items: center;
  justify-content: center; border: none; background: transparent;
  color: #969696; border-radius: 3px; cursor: pointer; padding: 0;
  flex-shrink: 0; transition: all 0.1s;
}
.cp-file-row:hover .cp-undo-btn { display: flex; }
.cp-undo-btn:hover { background: #4d2020; color: #f48771; }
.cp-detail {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 120px;
  overflow: hidden;
}
.cp-detail-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4px 12px; border-bottom: 1px solid #333;
  background: #252526; flex-shrink: 0;
}
.cp-detail-path {
  font-size: 11px; color: #569cd6;
  font-family: 'JetBrains Mono', monospace;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cp-detail-actions { display: flex; gap: 2px; }
.cp-detail-btn {
  padding: 2px 8px; border: 1px solid transparent;
  background: transparent; color: #969696; border-radius: 4px;
  font-size: 10px; font-family: inherit; cursor: pointer;
  transition: all 0.1s;
}
.cp-detail-btn:hover { color: #ccc; background: #37373d; }
.cp-detail-btn.active { color: #fff; background: #094771; border-color: #007acc; }
.cp-code-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.cp-code-block { margin: 0; padding: 4px 0; font-family: 'JetBrains Mono', monospace; font-size: 11px; line-height: 1.6; }
.cp-code-line { display: flex; min-height: 18px; }
.cp-type-hunk { background: #264f78; }
.cp-type-hunk .cp-code-text { color: #9cdcfe; font-weight: 600; }
.cp-type-add { background: rgba(78, 201, 176, 0.15); }
.cp-type-add .cp-code-text { color: #b5cea8; }
.cp-type-del { background: rgba(244, 135, 113, 0.15); }
.cp-type-del .cp-code-text { color: #f48771; }
.cp-type-ctx { background: transparent; }
.cp-ln {
  width: 36px; min-width: 36px; padding: 0 8px; text-align: right;
  color: #858585; user-select: none; border-right: 1px solid #333;
}
.cp-code-text { padding: 0 10px; color: #d4d4d4; white-space: pre; }
.cp-no-diff { padding: 16px; color: #666; font-size: 12px; text-align: center; }
.cp-slide-enter-active { transition: all 0.2s ease; }
.cp-slide-leave-active { transition: all 0.15s ease; }
.cp-slide-enter-from, .cp-slide-leave-to { opacity: 0; max-height: 0; }
</style>
