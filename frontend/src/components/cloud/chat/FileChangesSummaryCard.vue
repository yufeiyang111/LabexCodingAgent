<template>
  <div
    class="clean-changes-summary-card"
    :class="{ collapsed: isCollapsed, 'is-no-change': filesCount === 0 }"
  >
    <div class="summary-card-header">
      <div class="summary-stats-left" @click="filesCount > 0 && toggleCollapse()">
        <span v-if="filesCount > 0" class="icon summary-chevron" :class="{ rotated: !isCollapsed }">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
        </span>
        <span v-else class="icon no-change-check">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#71717a" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/></svg>
        </span>
        <span class="summary-files-count" v-if="filesCount > 0">{{ filesCount }} {{ filesCount === 1 ? 'file' : 'files' }} changed</span>
        <span class="summary-files-count no-change-text" v-else>No changes</span>
        <span class="summary-diff-counts" v-if="filesCount > 0">
          <span v-if="totalAdditions > 0" class="diff-add-text">+{{ totalAdditions }}</span>
          <span v-if="totalDeletions > 0" class="diff-del-text">-{{ totalDeletions }}</span>
        </span>
      </div>

      <button
        v-if="filesCount > 0"
        type="button"
        class="btn-review-diff"
        @click="emit('review-all')"
        title="在标签页打开完整 Diff 审查"
      >
        <span class="icon">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="8" y1="12" x2="16" y2="12"/><line x1="12" y1="8" x2="12" y2="16"/></svg>
        </span>
        <span>Review</span>
      </button>
    </div>

    <!-- 平滑风琴展开文件明细列表 -->
    <div v-if="filesCount > 0" class="smooth-accordion" :class="{ open: !isCollapsed }">
      <div class="smooth-accordion-inner">
        <div class="summary-files-list">
          <div
            v-for="file in fileList"
            :key="file.path"
            class="summary-file-row"
            @click="emit('open-file-diff', file)"
            title="点击在标签页打开代码 Diff"
          >
            <div class="file-row-left">
              <span class="file-icon-dot">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
              </span>
              <span class="file-name-text">{{ file.name }}</span>
              <span class="file-dir-text" v-if="file.dir">{{ file.dir }}</span>
            </div>
            <div class="file-row-right">
              <span v-if="file.additions > 0" class="diff-add-text">+{{ file.additions }}</span>
              <span v-if="file.deletions > 0" class="diff-del-text">-{{ file.deletions }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { splitWorkspacePath } from '@/utils/pathUtils'

const props = defineProps({
  changes: {
    type: Array,
    default: () => [],
  },
  additions: {
    type: Number,
    default: 0,
  },
  deletions: {
    type: Number,
    default: 0,
  },
})

const emit = defineEmits(['review-all', 'open-file-diff'])

const isCollapsed = ref(false)

const fileList = computed(() => {
  if (!props.changes || props.changes.length === 0) return []
  return props.changes
    .map(c => {
      const rawPath = (c.relativePath || c.file || c.path || c.filePath || c.filename || c.name || '')
      if (!rawPath) return null
      const { name: splitName, dir, path: normPath } = splitWorkspacePath(rawPath)
      const name = (c.name && c.name !== 'unknown' && !c.name.includes('/') && !c.name.includes('\\')) ? c.name : splitName
      let adds = c.additions || c.addedLines || 0
      let dels = c.deletions || c.deletedLines || 0
      if (!adds && !dels && typeof c.patch === 'string' && c.patch) {
        for (const line of c.patch.split('\n')) {
          if (line.startsWith('+') && !line.startsWith('+++')) adds++
          if (line.startsWith('-') && !line.startsWith('---')) dels++
        }
      }
      return {
        ...c,
        path: normPath,
        relativePath: normPath,
        file: normPath,
        rawPath,
        name,
        dir,
        status: c.status || 'modified',
        additions: adds,
        deletions: dels,
      }
    })
    .filter(Boolean)
})

const filesCount = computed(() => {
  return fileList.value.length
})

const totalAdditions = computed(() => {
  const sumFromFiles = fileList.value.reduce((sum, f) => sum + (f.additions || 0), 0)
  return sumFromFiles > 0 ? sumFromFiles : (props.additions || 0)
})

const totalDeletions = computed(() => {
  const sumFromFiles = fileList.value.reduce((sum, f) => sum + (f.deletions || 0), 0)
  return sumFromFiles > 0 ? sumFromFiles : (props.deletions || 0)
})

function toggleCollapse() {
  isCollapsed.value = !isCollapsed.value
}
</script>

<style scoped>
.clean-changes-summary-card {
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  user-select: none;
  margin-top: 4px;
}

.clean-changes-summary-card:hover {
  border-color: #d4d4d8;
  box-shadow: 0 4px 16px -2px rgba(0, 0, 0, 0.08), 0 2px 6px -1px rgba(0, 0, 0, 0.04);
  transform: translateY(-1px);
}

.summary-card-header {
  padding: 8px 12px;
  background: #fafafa;
  border-bottom: 1px solid #e4e4e7;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.summary-stats-left {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.summary-chevron {
  color: #71717a;
  transition: transform 0.22s cubic-bezier(0.16, 1, 0.3, 1);
}

.summary-chevron.rotated {
  transform: rotate(90deg);
}

.summary-files-count {
  font-size: 12.5px;
  font-weight: 600;
  color: #09090b;
}

.summary-diff-counts {
  display: inline-flex;
  gap: 5px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11.5px;
  font-weight: 600;
}

.diff-add-text {
  color: #1a7f37;
}

.diff-del-text {
  color: #cf222e;
}

.btn-review-diff {
  height: 24px;
  padding: 0 9px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 5px;
  font-size: 11.5px;
  font-weight: 500;
  color: #3f3f46;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  transition: all 0.12s;
}

.btn-review-diff:hover {
  background: #f4f4f5;
  border-color: #d4d4d8;
  color: #09090b;
}

/* 风琴动画 */
.smooth-accordion {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.26s cubic-bezier(0.16, 1, 0.3, 1),
              opacity 0.2s ease;
  opacity: 0;
}

.smooth-accordion.open {
  grid-template-rows: 1fr;
  opacity: 1;
}

.smooth-accordion-inner {
  overflow: hidden;
}

.summary-files-list {
  padding: 4px 0;
  display: flex;
  flex-direction: column;
}

.summary-file-row {
  padding: 6px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.1s;
}

.summary-file-row:hover {
  background: #f4f4f5;
}

.file-row-left {
  display: flex;
  align-items: center;
  gap: 7px;
  font-family: 'JetBrains Mono', monospace;
  min-width: 0;
  flex: 1;
}

.file-icon-dot {
  display: inline-flex;
  align-items: center;
  color: #ea580c;
}

.file-name-text {
  color: #09090b;
  font-weight: 500;
}

.file-dir-text {
  color: #71717a;
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-row-right {
  display: flex;
  align-items: center;
  gap: 6px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  flex-shrink: 0;
}

.clean-changes-summary-card.is-no-change {
  background: #fafafa;
  border-color: #e4e4e7;
}

.clean-changes-summary-card.is-no-change .summary-card-header {
  border-bottom: none;
  background: transparent;
}

.no-change-check {
  color: #71717a;
}

.no-change-text {
  color: #71717a;
  font-weight: 500;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
</style>
