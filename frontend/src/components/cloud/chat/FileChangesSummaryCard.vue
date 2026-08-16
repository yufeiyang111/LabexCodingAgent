<template>
  <div
    v-if="filesCount > 0 || totalAdditions > 0 || totalDeletions > 0"
    class="clean-changes-summary-card"
    :class="{ collapsed: isCollapsed }"
  >
    <div class="summary-card-header">
      <div class="summary-stats-left" @click="toggleCollapse">
        <span class="icon summary-chevron" :class="{ rotated: !isCollapsed }">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
        </span>
        <span class="summary-files-count">{{ filesCount }} {{ filesCount === 1 ? 'file' : 'files' }} changed</span>
        <span class="summary-diff-counts">
          <span v-if="totalAdditions > 0" class="diff-add-text">+{{ totalAdditions }}</span>
          <span v-if="totalDeletions > 0" class="diff-del-text">-{{ totalDeletions }}</span>
        </span>
      </div>

      <button
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
    <div class="smooth-accordion" :class="{ open: !isCollapsed }">
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
              <span class="icon file-status-icon" :class="file.status">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
              </span>
              <span class="file-path-text">{{ file.path }}</span>
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
  return props.changes.map(c => ({
    path: c.path || c.filePath || c.filename || 'unknown',
    status: c.status || 'modified',
    additions: c.additions || c.addedLines || 0,
    deletions: c.deletions || c.deletedLines || 0,
  }))
})

const filesCount = computed(() => {
  return fileList.value.length > 0 ? fileList.value.length : (props.additions > 0 || props.deletions > 0 ? 1 : 0)
})

const totalAdditions = computed(() => {
  if (props.additions > 0) return props.additions
  return fileList.value.reduce((sum, f) => sum + (f.additions || 0), 0)
})

const totalDeletions = computed(() => {
  if (props.deletions > 0) return props.deletions
  return fileList.value.reduce((sum, f) => sum + (f.deletions || 0), 0)
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
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
  user-select: none;
  margin-top: 4px;
}

.clean-changes-summary-card:hover {
  border-color: #d4d4d8;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
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
}

.file-status-icon {
  color: #ea580c;
}

.file-path-text {
  color: #09090b;
  font-weight: 500;
}

.file-row-right {
  display: flex;
  align-items: center;
  gap: 6px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
</style>
