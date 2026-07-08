<template>
  <div class="diff-viewer">
    <div class="diff-header" @click="collapsed = !collapsed">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#6366f1" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
      <span class="diff-file">{{ fileName }}</span>
      <span class="diff-stats">
        <span class="diff-add">+{{ additions }}</span>
        <span class="diff-del">-{{ deletions }}</span>
      </span>
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2" :style="{ transform: collapsed ? '' : 'rotate(180deg)' }"><polyline points="6 9 12 15 18 9"/></svg>
    </div>
    <Transition name="diff-slide">
      <div v-if="!collapsed" class="diff-body">
        <table class="diff-table">
          <tbody>
            <template v-for="(group, gi) in parsedGroups" :key="gi">
              <tr v-if="group.separator" class="diff-separator">
                <td colspan="3" class="diff-sep-cell">{{ group.separator }}</td>
              </tr>
              <tr v-for="(line, li) in group.lines" :key="gi + '-' + li"
                  class="diff-line"
                  :class="{ 'diff-add-line': line.type === '+', 'diff-del-line': line.type === '-', 'diff-ctx-line': line.type === ' ' }">
                <td class="diff-ln diff-ln-old">{{ line.oldLn || '' }}</td>
                <td class="diff-ln diff-ln-new">{{ line.newLn || '' }}</td>
                <td class="diff-code">
                  <span class="diff-prefix">{{ line.type }}</span><span>{{ line.text }}</span>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  diff: { type: String, default: '' }
})

const collapsed = ref(false)

const parsedLines = computed(() => {
  if (!props.diff) return []
  const lines = props.diff.split('\n')
  const result = []
  let oldLn = 0, newLn = 0

  for (const line of lines) {
    if (line.startsWith('@@')) {
      const m = line.match(/@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/)
      if (m) { oldLn = parseInt(m[1]); newLn = parseInt(m[2]) }
      result.push({ type: '@', text: line, hunk: true })
    } else if (line.startsWith('---') || line.startsWith('+++') || line.startsWith('diff ')) {
      continue
    } else if (line.startsWith('-')) {
      result.push({ type: '-', text: line.slice(1), oldLn: oldLn++ })
    } else if (line.startsWith('+')) {
      result.push({ type: '+', text: line.slice(1), newLn: newLn++ })
    } else if (line.startsWith(' ')) {
      result.push({ type: ' ', text: line.slice(1), oldLn: oldLn++, newLn: newLn++ })
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

const fileName = computed(() => {
  const m = props.diff.match(/\+\+\+ [ab]\/(.+)/)
  return m ? m[1] : 'file'
})

const additions = computed(() => parsedLines.value.filter(l => l.type === '+').length)
const deletions = computed(() => parsedLines.value.filter(l => l.type === '-').length)
</script>

<style scoped>
.diff-viewer {
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  overflow: hidden;
  font-size: 12px;
}
.diff-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: #f9fafb;
  cursor: pointer;
  user-select: none;
  border-bottom: 1px solid #f0f0f0;
}
.diff-header:hover { background: #f3f4f6; }
.diff-file {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  color: #6366f1;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.diff-stats {
  display: flex;
  gap: 6px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
}
.diff-add { color: #16a34a; }
.diff-del { color: #dc2626; }
.diff-body { overflow-x: auto; }
.diff-table {
  width: 100%;
  border-collapse: collapse;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  line-height: 1.5;
}
.diff-sep-cell {
  background: #f3f4f6;
  color: #6b7280;
  padding: 2px 10px;
  font-size: 10px;
}
.diff-line { vertical-align: top; }
.diff-ln {
  width: 40px;
  min-width: 40px;
  padding: 0 6px;
  text-align: right;
  color: #9ca3af;
  user-select: none;
  vertical-align: top;
  font-size: 10px;
  border-right: 1px solid #f0f0f0;
}
.diff-code {
  padding: 0 8px;
  white-space: pre-wrap;
  word-break: break-all;
}
.diff-prefix {
  user-select: none;
  color: #9ca3af;
  margin-right: 4px;
}
.diff-add-line { background: #f0fdf4; }
.diff-add-line .diff-code { color: #166534; }
.diff-add-line .diff-ln { background: #dcfce7; }
.diff-del-line { background: #fef2f2; }
.diff-del-line .diff-code { color: #991b1b; }
.diff-del-line .diff-ln { background: #fee2e2; }
.diff-ctx-line { background: #fff; }

.diff-slide-enter-active { transition: all 0.2s ease; }
.diff-slide-leave-active { transition: all 0.15s ease; }
.diff-slide-enter-from, .diff-slide-leave-to { opacity: 0; max-height: 0; }
</style>
