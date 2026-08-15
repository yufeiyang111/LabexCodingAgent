<template>
  <div class="ws-sidebar-panel cp-panel">
    <div class="cp-header">
      <span>会话列表</span>
      <button class="cp-new-btn" type="button" title="新建会话" @click="$emit('create')">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
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
        <div class="cp-item-main">
          <span class="cp-item-title">{{ conversation.title || '新对话' }}</span>
          <span class="cp-item-time">{{ conversation.createTime?.substring(0, 16) || '' }}</span>
        </div>
        <div class="cp-item-actions">
          <button class="cp-act-btn" type="button" title="从此会话创建分支" @click.stop="$emit('fork', conversation)">分支</button>
          <button class="cp-act-btn" type="button" title="压缩上下文" @click.stop="$emit('compact', conversation)">压缩</button>
          <button class="cp-act-btn danger" type="button" title="删除" @click.stop="$emit('delete', conversation)">
            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
      </div>
      <div v-if="conversations.length === 0" class="cp-empty">暂无历史会话</div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  conversations: { type: Array, default: () => [] },
  currentConversationId: { type: [String, Number], default: null }
})
defineEmits(['select', 'fork', 'compact', 'delete', 'create'])
</script>

<style scoped>
.cp-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.cp-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  font-size: 11px;
  font-weight: 600;
  color: #9ca3af;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}
.cp-new-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #fff;
  color: #4f46e5;
  font: inherit;
  font-size: 11px;
  font-weight: 500;
  text-transform: none;
  letter-spacing: 0;
  cursor: pointer;
  transition: all 0.15s ease;
}
.cp-new-btn:hover { background: #eef2ff; border-color: #c7d2fe; }
.cp-list { flex: 1; overflow-y: auto; padding: 6px 4px; display: flex; flex-direction: column; gap: 2px; }
.cp-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  border-radius: 6px;
  cursor: pointer;
  border: 2px solid transparent;
  transition: background 0.12s ease;
}
.cp-item:hover { background: #f3f4f6; }
.cp-item.active { background: #eef2ff; border-color: #c7d2fe; }
.cp-item-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.cp-item-title { font-size: 13px; color: #374151; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cp-item.active .cp-item-title { color: #4338ca; font-weight: 500; }
.cp-item-time { font-size: 11px; color: #9ca3af; }
.cp-item-actions { display: none; align-items: center; gap: 2px; flex-shrink: 0; }
.cp-item:hover .cp-item-actions { display: flex; }
.cp-act-btn {
  border: none;
  background: transparent;
  color: #6b7280;
  font: inherit;
  font-size: 11px;
  padding: 3px 6px;
  border-radius: 4px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
}
.cp-act-btn:hover { background: #e5e7eb; color: #374151; }
.cp-act-btn.danger { color: #9ca3af; }
.cp-act-btn.danger:hover { background: #fee2e2; color: #ef4444; }
.cp-empty { padding: 24px 12px; color: #9ca3af; font-size: 13px; text-align: center; }

:global([data-theme="dark"]) .cp-header { color: #787c99; border-bottom-color: #383a50; }
:global([data-theme="dark"]) .cp-new-btn { background: #1f2033; border-color: #383a50; color: #7aa2f7; }
:global([data-theme="dark"]) .cp-new-btn:hover { background: #282a3a; }
:global([data-theme="dark"]) .cp-item { color: #a9b1d6; }
:global([data-theme="dark"]) .cp-item:hover { background: #282a3a; }
:global([data-theme="dark"]) .cp-item.active { background: #1a1d3a; border-color: #2e3a5e; }
:global([data-theme="dark"]) .cp-item-title { color: #c0caf5; }
:global([data-theme="dark"]) .cp-item.active .cp-item-title { color: #7aa2f7; }
:global([data-theme="dark"]) .cp-item-time { color: #787c99; }
:global([data-theme="dark"]) .cp-act-btn { color: #a9b1d6; }
:global([data-theme="dark"]) .cp-act-btn:hover { background: #383a50; color: #c0caf5; }
:global([data-theme="dark"]) .cp-act-btn.danger:hover { background: #4a2430; color: #f7768e; }
:global([data-theme="dark"]) .cp-empty { color: #787c99; }
</style>
