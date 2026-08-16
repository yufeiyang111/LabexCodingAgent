<template>
  <div v-if="attachments.length > 0" class="agent-image-attachments" :class="`is-${variant}`" :aria-label="ariaLabel">
    <article v-for="attachment in attachments" :key="attachment.id || attachment.name" class="agent-image-attachment">
      <button
        type="button"
        class="agent-image-thumbnail"
        :title="attachment.name"
        :aria-label="`预览图片：${attachment.name}`"
        @click="$emit('preview', attachment)"
      >
        <img :src="attachment.previewUrl || attachment.dataUrl || attachment.url || attachment.src" :alt="attachment.name" />
      </button>
      <button
        v-if="removable"
        type="button"
        class="agent-image-remove"
        :aria-label="`移除图片：${attachment.name}`"
        title="移除图片"
        @click="$emit('remove', attachment.id)"
      >
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true">
          <path d="m6 6 12 12M18 6 6 18" />
        </svg>
      </button>
      <span v-if="showNames" class="agent-image-name">{{ attachment.name }}</span>
    </article>
  </div>
</template>

<script setup>
defineProps({
  attachments: { type: Array, default: () => [] },
  variant: { type: String, default: 'input' },
  removable: { type: Boolean, default: false },
  showNames: { type: Boolean, default: true },
  ariaLabel: { type: String, default: '图片附件' }
})

defineEmits(['preview', 'remove'])
</script>

<style scoped>
.agent-image-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.agent-image-attachments.is-input {
  padding: 2px 0;
}

.agent-image-attachments.is-message {
  margin: 0 0 10px;
}

.agent-image-attachment {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 76px;
  min-width: 0;
}

.agent-image-attachments.is-message .agent-image-attachment {
  width: 96px;
}

.agent-image-thumbnail {
  width: 100%;
  aspect-ratio: 1;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--ai-border);
  border-radius: 8px;
  background: var(--ai-bg-elevated);
  cursor: zoom-in;
}

.agent-image-thumbnail:focus-visible,
.agent-image-remove:focus-visible {
  outline: 2px solid var(--ai-accent);
  outline-offset: 2px;
}

.agent-image-placeholder {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  color: var(--ai-text-faint);
  font-size: 11px;
  background: var(--ai-bg-secondary);
}

.agent-image-thumbnail.is-expired,
.agent-image-thumbnail.is-unavailable {
  cursor: not-allowed;
  border-style: dashed;
}

.agent-image-thumbnail img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.agent-image-remove {
  position: absolute;
  top: -5px;
  right: -5px;
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  padding: 0;
  border: 1px solid var(--ai-border);
  border-radius: 50%;
  background: var(--ai-bg);
  color: var(--ai-text-secondary);
  cursor: pointer;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.14);
}

.agent-image-remove:hover {
  color: #dc2626;
  border-color: #fecaca;
  background: #fef2f2;
}

.agent-image-name {
  overflow: hidden;
  margin-top: 4px;
  color: var(--ai-text-faint);
  font-size: 10px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 640px) {
  .agent-image-attachment { width: 64px; }
  .agent-image-attachments.is-message .agent-image-attachment { width: 76px; }
}
</style>
