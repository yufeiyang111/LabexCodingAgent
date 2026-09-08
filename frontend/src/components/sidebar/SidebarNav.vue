<template>
  <nav class="sn-nav" role="tablist" aria-label="侧边栏视图切换">
    <button
      type="button"
      class="sn-btn"
      :class="{ active: view === 'files' }"
      :aria-selected="view === 'files'"
      role="tab"
      title="文件"
      @click="$emit('change', 'files')"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
      <span>文件</span>
    </button>
    <button
      type="button"
      class="sn-btn"
      :class="{ active: view === 'conversations' }"
      :aria-selected="view === 'conversations'"
      role="tab"
      title="会话"
      @click="$emit('change', 'conversations')"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
      <span>会话</span>
    </button>
    <button
      type="button"
      class="sn-btn"
      :class="{ active: view === 'search' }"
      :aria-selected="view === 'search'"
      role="tab"
      title="搜索"
      @click="$emit('change', 'search')"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.2" y2="16.2"/></svg>
      <span>搜索</span>
    </button>
  </nav>
</template>

<script setup>
defineProps({
  view: { type: String, default: 'files' }
})
defineEmits(['change'])
</script>

<style scoped>
.sn-nav {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 6px 6px;
  border-bottom: 1px solid var(--theme-border, #f0f0f0);
  background: var(--theme-surface-muted, #fafafa);
  flex-shrink: 0;
  min-width: 0;
  container-type: inline-size;
}
.sn-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  flex: 1 1 0%;
  min-width: 0;
  height: 28px;
  padding: 0 4px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--theme-text-muted, #6b7280);
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition: all 0.15s ease;
}
.sn-btn svg {
  flex-shrink: 0;
}
.sn-btn span {
  white-space: nowrap;
  word-break: keep-all;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1;
  font-size: 12px;
}
.sn-btn:hover {
  background: rgba(0, 0, 0, 0.04);
  color: var(--theme-text, #374151);
}
.sn-btn.active {
  background: var(--theme-surface, #ffffff);
  border-color: var(--theme-border, #e4e4e7);
  color: var(--theme-accent, #4f46e5);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

@container (max-width: 175px) {
  .sn-btn {
    padding: 0 2px;
    gap: 0;
  }
  .sn-btn span {
    display: none;
  }
}

:global(html[data-theme="dark"] .sn-nav) {
  background: #141720;
  border-bottom-color: #272a37;
}
:global(html[data-theme="dark"] .sn-btn) {
  color: #8c96a8;
}
:global(html[data-theme="dark"] .sn-btn:hover) {
  background: rgba(255, 255, 255, 0.08);
  color: #edf1fb;
}
:global(html[data-theme="dark"] .sn-btn.active) {
  background: #1f2538;
  border-color: #3b486d;
  color: #818cf8;
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
}
</style>
