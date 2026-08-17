<template>
  <div class="center-diff-container" :class="{ 'is-dark': isDark }">
    <!-- Diff 顶部栏 (1:1 复刻图一: 📄 文件名 路径 +245 -63 ⌄) -->
    <div class="center-diff-header">
      <div class="header-left">
        <span class="file-icon">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        </span>
        <span class="file-name">{{ fileName }}</span>
        <span class="file-dir" v-if="fileDir">{{ fileDir }}</span>
      </div>

      <div class="header-right">
        <!-- 统计徽章 (+245 -63 ⌄) -->
        <div class="diff-stats-badge" title="代码行增删统计">
          <span class="stat-add" v-if="additions > 0">+{{ additions }}</span>
          <span class="stat-del" v-if="deletions > 0">-{{ deletions }}</span>
          <span class="stat-chevron">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="6 9 12 15 18 9"/></svg>
          </span>
        </div>

        <!-- 极简快捷操作按钮 -->
        <div class="diff-actions-group">
          <button
            type="button"
            class="diff-btn btn-accept"
            @click="emit('accept-all')"
            title="接受并保留本文件的所有变更"
          >
            保留
          </button>
          <button
            type="button"
            class="diff-btn btn-revert"
            @click="emit('revert-all')"
            title="回退并丢弃本文件的所有变更"
          >
            回退
          </button>
          <button
            type="button"
            class="diff-btn btn-editor"
            @click="emit('switch-to-editor')"
            title="切换至普通代码编辑器"
          >
            编辑源码
          </button>
        </div>
      </div>
    </div>

    <!-- Diff 内容代码行展示 (1:1 复刻图一结构与高亮配色) -->
    <div class="center-diff-scroll">
      <div v-if="!parsedLines.length" class="center-diff-empty">
        暂无该文件的代码变动对比
      </div>

      <table v-else class="center-diff-table">
        <tbody>
          <template v-for="(group, gIdx) in parsedGroups" :key="gIdx">
            <!-- 变更块分隔头 (Hunk Header) -->
            <tr v-if="group.separator" class="diff-hunk-row">
              <td class="diff-ln diff-ln-hunk" colspan="2">
                <span class="hunk-expand-icon" title="变更代码块">
                  <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor"><path d="M19 11h-6V5h-2v6H5v2h6v6h2v-6h6z"/></svg>
                </span>
              </td>
              <td class="diff-code-cell hunk-text-cell">
                <span class="diff-hunk-text">{{ group.separator }}</span>
              </td>
            </tr>

            <!-- 代码行 (复刻图一：浅绿新增 / 浅红删除 / 真实行号 / JetBrains Mono) -->
            <tr
              v-for="(line, lIdx) in group.lines"
              :key="gIdx + '-' + lIdx"
              class="diff-row"
              :class="{
                'is-add': line.type === '+',
                'is-del': line.type === '-',
                'is-ctx': line.type === ' '
              }"
              @dblclick="handleRowDblClick(line, gIdx)"
              :title="line.type === '+' ? '双击快速保留此行' : (line.type === '-' ? '双击快速回退此行' : '')"
            >
              <td class="diff-ln diff-ln-old">{{ line.oldLn || '' }}</td>
              <td class="diff-ln diff-ln-new">{{ line.newLn || '' }}</td>
              <td class="diff-code-cell">
                <span class="diff-code-content">{{ line.text }}</span>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  diff: {
    type: String,
    default: ''
  },
  filePath: {
    type: String,
    default: ''
  },
  isDark: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits([
  'switch-to-editor',
  'accept-all',
  'revert-all',
  'accept-chunk',
  'revert-chunk',
  'keep-original'
])

const fileName = computed(() => {
  if (!props.filePath) return 'Untitled'
  const normalized = props.filePath.replace(/\\/g, '/')
  const parts = normalized.split('/')
  return parts.pop() || normalized
})

const fileDir = computed(() => {
  if (!props.filePath) return ''
  const normalized = props.filePath.replace(/\\/g, '/')
  const parts = normalized.split('/')
  parts.pop()
  return parts.join('/')
})

function handleRowDblClick(line, gIdx) {
  if (line.type === '+') {
    emit('accept-chunk', { groupIndex: gIdx, line })
    ElMessage.success(`已确认保留第 ${line.newLn} 行`)
  } else if (line.type === '-') {
    emit('revert-chunk', { groupIndex: gIdx, line })
    ElMessage.info(`已标记回退第 ${line.oldLn} 行`)
  }
}

const parsedLines = computed(() => {
  if (!props.diff) return []
  const lines = props.diff.split('\n')
  const result = []
  let oldLn = 0
  let newLn = 0

  for (const line of lines) {
    if (line.startsWith('@@')) {
      const m = line.match(/@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/)
      if (m) {
        oldLn = parseInt(m[1], 10)
        newLn = parseInt(m[2], 10)
      }
      result.push({ type: '@', text: line, hunk: true })
    } else if (line.startsWith('---') || line.startsWith('+++') || line.startsWith('diff --git')) {
      continue
    } else if (line.startsWith('-')) {
      result.push({ type: '-', text: line.slice(1), oldLn: oldLn++ })
    } else if (line.startsWith('+')) {
      result.push({ type: '+', text: line.slice(1), newLn: newLn++ })
    } else if (line.startsWith(' ')) {
      result.push({ type: ' ', text: line.slice(1), oldLn: oldLn++, newLn: newLn++ })
    } else if (line.trim().length > 0) {
      result.push({ type: ' ', text: line, oldLn: oldLn++, newLn: newLn++ })
    }
  }
  return result
})

const parsedGroups = computed(() => {
  const groups = []
  let current = { lines: [] }
  for (const line of parsedLines.value) {
    if (line.hunk) {
      if (current.lines.length) groups.push(current)
      groups.push({ separator: line.text, lines: [] })
      current = { lines: [] }
    } else {
      if (groups.length && !groups[groups.length - 1].lines.length && groups[groups.length - 1].separator) {
        groups[groups.length - 1].lines.push(line)
      } else {
        current.lines.push(line)
      }
    }
  }
  if (current.lines.length) groups.push(current)
  return groups
})

const additions = computed(() => parsedLines.value.filter(l => l.type === '+').length)
const deletions = computed(() => parsedLines.value.filter(l => l.type === '-').length)
</script>

<style scoped>
.center-diff-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  background: #f8fafc;
  color: #1e293b;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  overflow: hidden;
  user-select: text;
}

/* 顶部栏 (1:1 复刻图一: 📄 CenterDiffViewer.vue frontend/src/components/cloud +245 -63 ⌄) */
.center-diff-header {
  height: 38px;
  min-height: 38px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f1f5f9;
  border-bottom: 1px solid #e2e8f0;
  user-select: none;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow: hidden;
}

.file-icon {
  display: inline-flex;
  align-items: center;
  color: #64748b;
}

.file-name {
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
}

.file-dir {
  font-size: 12px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.diff-stats-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  font-weight: 600;
}

.stat-add {
  color: #16a34a;
}

.stat-del {
  color: #dc2626;
}

.stat-chevron {
  display: inline-flex;
  align-items: center;
  color: #94a3b8;
}

.diff-actions-group {
  display: flex;
  align-items: center;
  gap: 6px;
}

.diff-btn {
  padding: 2.5px 8px;
  border-radius: 4px;
  border: 1px solid #cbd5e1;
  background: #ffffff;
  color: #334155;
  font-size: 11.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.12s ease;
}

.diff-btn:hover {
  background: #f8fafc;
  color: #0f172a;
  border-color: #94a3b8;
}

.btn-accept:hover {
  background: #f0fdf4;
  color: #15803d;
  border-color: #86efac;
}

.btn-revert:hover {
  background: #fef2f2;
  color: #b91c1c;
  border-color: #fca5a5;
}

/* Diff 代码表格区域 */
.center-diff-scroll {
  flex: 1;
  overflow: auto;
  background: #ffffff;
}

.center-diff-empty {
  padding: 80px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
  font-family: 'Inter', sans-serif;
}

.center-diff-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
  line-height: 1.6;
}

/* Hunk 头部 */
.diff-hunk-row {
  background: #f1f5f9;
  border-top: 1px solid #e2e8f0;
  border-bottom: 1px solid #e2e8f0;
}

.diff-ln-hunk {
  text-align: center;
  color: #3b82f6;
  padding: 3px 0;
  user-select: none;
}

.hunk-expand-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 2px;
  background: #0284c7;
  color: #ffffff;
}

.hunk-text-cell {
  color: #64748b;
  font-size: 11.5px;
  font-weight: 500;
}

/* 代码行结构 */
.diff-row {
  display: table-row;
}

.diff-ln {
  width: 38px;
  min-width: 38px;
  max-width: 38px;
  padding: 0 6px;
  text-align: right;
  color: #94a3b8;
  font-size: 11.5px;
  user-select: none;
  vertical-align: top;
  border-right: 1px solid #f1f5f9;
}

.diff-code-cell {
  padding: 0 10px;
  white-space: pre;
  word-break: normal;
  display: flex;
  align-items: center;
  min-height: 22px;
}

.diff-code-content {
  flex: 1;
}

/* 新增行 (1:1 复刻图一: 浅绿色底色 + 深一点的行号底色) */
.diff-row.is-add {
  background: #e6ffec;
  color: #1e293b;
}

.diff-row.is-add .diff-ln-new {
  background: #ccffd8;
  color: #166534;
  font-weight: 600;
}

.diff-row.is-add .diff-ln-old {
  background: #e6ffec;
}

/* 删除行 (1:1 复刻图一: 浅粉红底色 + 深一点的行号底色) */
.diff-row.is-del {
  background: #ffebe9;
  color: #1e293b;
}

.diff-row.is-del .diff-ln-old {
  background: #ffd7d5;
  color: #991b1b;
  font-weight: 600;
}

.diff-row.is-del .diff-ln-new {
  background: #ffebe9;
}

/* 常规上下文行 */
.diff-row.is-ctx {
  background: #ffffff;
  color: #334155;
}

.diff-row.is-ctx .diff-ln {
  background: #f8fafc;
  color: #94a3b8;
}

.diff-row:hover {
  filter: brightness(0.97);
}

/* 暗色模式适配 */
.center-diff-container.is-dark {
  background: #0d1117;
  color: #c9d1d9;
}

.center-diff-container.is-dark .center-diff-header {
  background: #161b22;
  border-color: #30363d;
}

.center-diff-container.is-dark .file-name {
  color: #f0f6fc;
}

.center-diff-container.is-dark .file-dir {
  color: #8b949e;
}

.center-diff-container.is-dark .diff-btn {
  background: #21262d;
  border-color: #30363d;
  color: #c9d1d9;
}

.center-diff-container.is-dark .diff-btn:hover {
  background: #30363d;
  color: #ffffff;
}

.center-diff-container.is-dark .center-diff-scroll {
  background: #0d1117;
}

.center-diff-container.is-dark .diff-hunk-row {
  background: #161b22;
  border-color: #30363d;
}

.center-diff-container.is-dark .diff-row.is-ctx {
  background: #0d1117;
  color: #c9d1d9;
}

.center-diff-container.is-dark .diff-row.is-ctx .diff-ln {
  background: #0d1117;
  color: #6e7681;
  border-color: #21262d;
}

.center-diff-container.is-dark .diff-row.is-add {
  background: rgba(46, 160, 67, 0.18);
  color: #e6edf3;
}

.center-diff-container.is-dark .diff-row.is-add .diff-ln-new {
  background: rgba(46, 160, 67, 0.35);
  color: #7ee787;
}

.center-diff-container.is-dark .diff-row.is-del {
  background: rgba(248, 81, 73, 0.18);
  color: #e6edf3;
}

.center-diff-container.is-dark .diff-row.is-del .diff-ln-old {
  background: rgba(248, 81, 73, 0.35);
  color: #ff7b72;
}
</style>
