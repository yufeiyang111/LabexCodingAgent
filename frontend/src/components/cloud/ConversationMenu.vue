<template>
  <div class="ai-session-dropdown" @click.stop>
    <div v-for="conversation in conversations" :key="conversation.conversationId" class="ai-session-item"
      :class="{ active: currentConversationId === conversation.conversationId }"
      @click="emit('select', conversation)">
      <span class="ai-session-title">{{ conversation.title || '新对话' }}</span>
      <span class="ai-session-time">{{ conversation.createTime?.substring(0, 16) || '' }}</span>
      <button class="ai-session-action" @click.stop="emit('fork', conversation)" title="从此会话创建分支">分支</button>
      <button class="ai-session-action" @click.stop="emit('compact', conversation)" title="压缩上下文">压缩</button>
      <button class="ai-session-del" @click.stop="emit('delete', conversation)" title="删除">
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </div>
    <div v-if="conversations.length === 0" class="ai-session-empty">暂无历史会话</div>
    <div class="ai-session-new" @click="emit('create')">
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
      <span>新建会话</span>
    </div>
  </div>
</template>

<script setup>
defineProps({
  conversations: { type: Array, default: () => [] },
  currentConversationId: { type: [String, Number], default: null }
})
const emit = defineEmits(['select', 'fork', 'compact', 'delete', 'create'])
</script>
