<template>
  <div class="ws-sidebar-panel cp-panel">
    <div class="cp-header">
      <div class="cp-header-title">
        <span class="icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
        </span>
        <span>会话历史</span>
      </div>
      <button class="cp-new-btn" type="button" title="新建会话" @click="$emit('create')">
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        <span>新建</span>
      </button>
    </div>

    <div class="cp-list">
      <div
        v-for="conversation in conversations"
        :key="conversation.conversationId"
        class="cp-item"
        :class="{ active: currentConversationId === conversation.conversationId }"
        @click="$emit('select', conversation)"
      >
        <div class="cp-item-indicator" v-if="currentConversationId === conversation.conversationId"></div>
        <div class="cp-item-main">
          <span class="cp-item-title">{{ conversation.title || '新对话' }}</span>
          <span class="cp-item-time">{{ formatTime(conversation.createTime) }}</span>
        </div>
        <div class="cp-item-actions">
          <button class="cp-act-btn" type="button" title="从此会话创建分支" @click.stop="$emit('fork', conversation)">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="6" y1="3" x2="6" y2="15"/><circle cx="18" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M18 9a9 9 0 0 1-9 9"/></svg>
          </button>
          <button class="cp-act-btn" type="button" title="压缩上下文" @click.stop="$emit('compact', conversation)">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/></svg>
          </button>
          <button class="cp-act-btn danger" type="button" title="删除会话" @click.stop="$emit('delete', conversation)">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
      </div>
      <div v-if="conversations.length === 0" class="cp-empty">
        <span class="icon" style="color: #a1a1aa; margin-bottom: 4px;">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
        </span>
        <span>暂无历史会话</span>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  conversations: { type: Array, default: () => [] },
  currentConversationId: { type: [String, Number], default: null }
})
defineEmits(['select', 'fork', 'compact', 'delete', 'create'])

function formatTime(isoStr) {
  if (!isoStr) return ''
  try {
    const d = new Date(isoStr)
    if (isNaN(d.getTime())) return isoStr.substring(0, 16).replace('T', ' ')
    const now = new Date()
    const isToday = d.toDateString() === now.toDateString()
    const hours = String(d.getHours()).padStart(2, '0')
    const minutes = String(d.getMinutes()).padStart(2, '0')
    if (isToday) return `今天 ${hours}:${minutes}`
    const yesterday = new Date(now)
    yesterday.setDate(now.getDate() - 1)
    if (d.toDateString() === yesterday.toDateString()) return `昨天 ${hours}:${minutes}`
    const month = d.getMonth() + 1
    const date = d.getDate()
    return `${month}月${date}日 ${hours}:${minutes}`
  } catch {
    return isoStr.substring(0, 16).replace('T', ' ')
  }
}
</script>

<style scoped>
.cp-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-sidebar, #ffffff);
  border-right: 1px solid var(--border-light, #e4e4e7);
  user-select: none;
  min-width: 0;
}

.cp-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border-light, #e4e4e7);
  background: var(--bg-sidebar, #ffffff);
  flex-shrink: 0;
}

.cp-header-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-secondary, #3f3f46);
  letter-spacing: 0.2px;
}

.cp-new-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  border: 1px solid var(--border-medium, #d4d4d8);
  border-radius: 6px;
  background: var(--bg-app, #ffffff);
  color: var(--text-primary, #09090b);
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}

.cp-new-btn:hover {
  background: var(--text-primary, #09090b);
  color: #ffffff;
  border-color: var(--text-primary, #09090b);
}

.cp-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px 8px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.cp-item {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
  background: transparent;
  transition: all 0.12s ease;
}

.cp-item:hover {
  background: var(--bg-hover, #f4f4f5);
}

.cp-item.active {
  background: var(--bg-card-subtle, #f4f4f5);
  border-color: var(--border-medium, #d4d4d8);
}

.cp-item-indicator {
  position: absolute;
  left: 0;
  top: 6px;
  bottom: 6px;
  width: 3px;
  border-radius: 0 2px 2px 0;
  background: var(--text-primary, #09090b);
}

.cp-item-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.cp-item-title {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-primary, #09090b);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cp-item.active .cp-item-title {
  font-weight: 600;
  color: var(--text-primary, #09090b);
}

.cp-item-time {
  font-size: 11px;
  color: var(--text-muted, #71717a);
  font-family: 'Inter', -apple-system, sans-serif;
}

.cp-item-actions {
  display: none;
  align-items: center;
  gap: 3px;
  flex-shrink: 0;
  margin-left: 6px;
}

.cp-item:hover .cp-item-actions {
  display: flex;
}

.cp-act-btn {
  border: 1px solid var(--border-light, #e4e4e7);
  background: var(--bg-app, #ffffff);
  color: var(--text-secondary, #3f3f46);
  width: 22px;
  height: 22px;
  border-radius: 4px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  transition: all 0.12s ease;
}

.cp-act-btn:hover {
  background: var(--text-primary, #09090b);
  color: #ffffff;
  border-color: var(--text-primary, #09090b);
}

.cp-act-btn.danger:hover {
  background: #ef4444;
  color: #ffffff;
  border-color: #ef4444;
}

.cp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px 12px;
  color: var(--text-muted, #71717a);
  font-size: 12px;
  text-align: center;
}

:global(html[data-theme="dark"] .cp-panel) {
  background: #181b24;
  border-right-color: #2e3547;
}
:global(html[data-theme="dark"] .cp-header) {
  background: #181b24;
  border-bottom-color: #2e3547;
}
:global(html[data-theme="dark"] .cp-header-title) {
  color: #cdd6f4;
}
:global(html[data-theme="dark"] .cp-new-btn) {
  background: #202432;
  border-color: #3b486d;
  color: #edf1fb;
}
:global(html[data-theme="dark"] .cp-new-btn:hover) {
  background: #2e364c;
  color: #ffffff;
}
:global(html[data-theme="dark"] .cp-item:hover) {
  background: rgba(255, 255, 255, 0.06);
}
:global(html[data-theme="dark"] .cp-item.active) {
  background: rgba(99, 102, 241, 0.22);
  border-color: rgba(99, 102, 241, 0.45);
}
:global(html[data-theme="dark"] .cp-item-indicator) {
  background: #818cf8;
}
:global(html[data-theme="dark"] .cp-item-title) {
  color: #edf1fb;
}
:global(html[data-theme="dark"] .cp-item.active .cp-item-title) {
  color: #ffffff;
}
:global(html[data-theme="dark"] .cp-item-time) {
  color: #8c96a8;
}
:global(html[data-theme="dark"] .cp-act-btn) {
  background: #202432;
  border-color: #303747;
  color: #a6adc8;
}
:global(html[data-theme="dark"] .cp-act-btn:hover) {
  background: #2e364c;
  color: #ffffff;
}
:global(html[data-theme="dark"] .cp-act-btn.danger:hover) {
  background: #ef4444;
  color: #ffffff;
}
</style>
