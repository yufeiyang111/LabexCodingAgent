<template>
  <div class="center-diff-container" :class="{ 'is-dark': isDark }">
    <!-- Diff 顶部信息与切换栏 -->
    <div class="center-diff-toolbar">
      <div class="toolbar-left">
        <span class="diff-tag">DIFF</span>
        <span class="file-path">{{ filePath }}</span>
        <span class="stats-badge" v-if="additions > 0 || deletions > 0">
          <span class="stat-add" v-if="additions > 0">+{{ additions }}</span>
          <span class="stat-del" v-if="deletions > 0">-{{ deletions }}</span>
        </span>
      </div>
      <div class="toolbar-right">
        <button
          type="button"
          class="diff-view-btn active"
          title="查看代码变更对比"
        >
          变更对比
        </button>
        <button
          type="button"
          class="diff-view-btn"
          title="切换至普通代码编辑器"
          @click="$emit('switch-to-editor')"
        >
          编辑源码
        </button>
      </div>
    </div>

    <!-- Diff 内容代码行展示 (1:1 复刻用户截图) -->
    <div class="center-diff-scroll">
      <div v-if="!parsedLines.length" class="center-diff-empty">
        暂无该文件的代码变动对比
      </div>
      <table v-else class="center-diff-table">
        <tbody>
          <template v-for="(group, gIdx) in parsedGroups" :key="gIdx">
            <tr v-if="group.separator" class="diff-hunk-separator">
              <td colspan="3" class="diff-hunk-text">{{ group.separator }}</td>
            </tr>
            <tr
              v-for="(line, lIdx) in group.lines"
              :key="gIdx + '-' + lIdx"
              class="diff-row"
              :class="{
                'is-add': line.type === '+',
                'is-del': line.type === '-',
                'is-ctx': line.type === ' '
              }"
            >
              <td class="diff-ln diff-ln-old">{{ line.oldLn || '' }}</td>
              <td class="diff-ln diff-ln-new">{{ line.newLn || '' }}</td>
              <td class="diff-code-cell">
                <span class="diff-marker">{{ line.type }}</span>
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
    default: true
  }
})

defineEmits(['switch-to-editor'])

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
      groups.push({ separator: line.text })
      current = { lines: [] }
    } else {
      current.lines.push(line)
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
  background: #18181b;
  color: #f8fafc;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  overflow: hidden;
  user-select: text;
}

.center-diff-toolbar {
  height: 38px;
  min-height: 38px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #1f1f23;
  border-bottom: 1px solid #27272a;
  user-select: none;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow: hidden;
}

.diff-tag {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 4px;
  background: #3b82f6;
  color: #ffffff;
}

.file-path {
  font-size: 12.5px;
  font-weight: 600;
  color: #e4e4e7;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stats-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
}

.stat-add {
  color: #4ade80;
  font-weight: 600;
}

.stat-del {
  color: #f87171;
  font-weight: 600;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.diff-view-btn {
  padding: 4px 10px;
  border-radius: 5px;
  border: 1px solid #3f3f46;
  background: #27272a;
  color: #a1a1aa;
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.12s ease;
}

.diff-view-btn:hover {
  background: #3f3f46;
  color: #ffffff;
}

.diff-view-btn.active {
  background: #18181b;
  border-color: #3b82f6;
  color: #ffffff;
  font-weight: 600;
}

.center-diff-scroll {
  flex: 1;
  overflow: auto;
  background: #141416;
}

.center-diff-empty {
  padding: 40px;
  text-align: center;
  color: #71717a;
  font-size: 13px;
}

.center-diff-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
  line-height: 1.55;
}

.diff-hunk-separator {
  background: #1e293b;
}

.diff-hunk-text {
  padding: 4px 12px;
  color: #38bdf8;
  font-weight: 600;
  font-size: 11.5px;
  border-top: 1px solid #334155;
  border-bottom: 1px solid #334155;
}

.diff-row {
  display: table-row;
}

.diff-row:hover {
  filter: brightness(1.1);
}

.diff-ln {
  width: 44px;
  min-width: 44px;
  max-width: 44px;
  padding: 0 8px;
  text-align: right;
  color: #52525b;
  font-size: 11px;
  user-select: none;
  border-right: 1px solid #27272a;
  vertical-align: top;
}

.diff-code-cell {
  padding: 0 10px;
  white-space: pre;
  word-break: normal;
  display: flex;
  align-items: center;
  min-height: 20px;
}

.diff-marker {
  display: inline-block;
  width: 16px;
  min-width: 16px;
  font-weight: 700;
  user-select: none;
}

.diff-code-content {
  flex: 1;
}

/* Added lines (Light green background & green text, matching user screenshot) */
.diff-row.is-add {
  background: rgba(34, 197, 94, 0.12);
  color: #4ade80;
}

.diff-row.is-add .diff-ln-new {
  color: #22c55e;
  background: rgba(34, 197, 94, 0.18);
}

.diff-row.is-add .diff-marker {
  color: #22c55e;
}

/* Deleted lines (Light red background & red text, matching user screenshot) */
.diff-row.is-del {
  background: rgba(239, 68, 68, 0.12);
  color: #f87171;
}

.diff-row.is-del .diff-ln-old {
  color: #ef4444;
  background: rgba(239, 68, 68, 0.18);
}

.diff-row.is-del .diff-marker {
  color: #ef4444;
}

.diff-row.is-ctx {
  color: #e4e4e7;
}
</style>
