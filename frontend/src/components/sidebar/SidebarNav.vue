<template>
  <nav class="sn-nav" role="tablist" aria-label="侧边栏视图切换">
    <button
      type="button"
      class="sn-btn"
      :class="{ active: view === 'files' }"
      :aria-selected="view === 'files'"
      role="tab"
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
      @click="$emit('change', 'conversations')"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
      <span>会话</span>
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
  padding: 6px 8px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}
.sn-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  flex: 1;
  height: 28px;
  padding: 0 10px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #6b7280;
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}
.sn-btn:hover { background: #eceef1; color: #374151; }
.sn-btn.active { background: #e0e7ff; color: #4f46e5; }
:global([data-theme="dark"]) .sn-nav { border-bottom-color: #383a50; }
:global([data-theme="dark"]) .sn-btn { color: #a9b1d6; }
:global([data-theme="dark"]) .sn-btn:hover { background: #282a3a; color: #c0caf5; }
:global([data-theme="dark"]) .sn-btn.active { background: #2e3a5e; color: #7aa2f7; }
</style>
