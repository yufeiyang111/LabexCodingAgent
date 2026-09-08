<template>
  <Teleport to="body">
    <div
      v-if="visible"
      ref="menuRef"
      class="file-context-menu"
      :style="{ top: adjusted.y + 'px', left: adjusted.x + 'px' }"
      @click.stop
      @contextmenu.prevent
    >
      <div v-if="isDir" class="fcm-item" @click="handleAction('new-file')">
        <span class="fcm-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="12" y1="18" x2="12" y2="12"/><line x1="9" y1="15" x2="15" y2="15"/></svg>
        </span>
        <span>新建文件</span>
      </div>
      <div v-if="isDir" class="fcm-item" @click="handleAction('new-folder')">
        <span class="fcm-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/><line x1="12" y1="11" x2="12" y2="17"/><line x1="9" y1="14" x2="15" y2="14"/></svg>
        </span>
        <span>新建文件夹</span>
      </div>
      <div v-if="isDir" class="fcm-divider"></div>

      <div v-if="canPreview" class="fcm-item" @click="handleAction('preview-image')">
        <span class="fcm-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
        </span>
        <span>预览图片</span>
      </div>
      <div v-if="!isDir" class="fcm-item" @click="handleAction('add-to-chat')">
        <span class="fcm-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
        </span>
        <span>添加到聊天</span>
      </div>
      <div class="fcm-item" @click="handleAction('copy-path')">
        <span class="fcm-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>
        </span>
        <span>复制路径</span>
      </div>
      <div class="fcm-divider"></div>

      <div class="fcm-item" @click="handleAction('copy')">
        <span class="fcm-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
        </span>
        <span>复制</span>
      </div>
      <div class="fcm-item" @click="handleAction('cut')">
        <span class="fcm-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="6" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><line x1="20" y1="4" x2="8.12" y2="15.88"/><line x1="14.47" y1="14.48" x2="20" y2="20"/><line x1="8.12" y1="8.12" x2="12" y2="12"/></svg>
        </span>
        <span>剪切</span>
      </div>
      <div v-if="isDir" class="fcm-item" :class="{ disabled: !hasClipboard }" @click="hasClipboard && handleAction('paste')">
        <span class="fcm-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><rect x="8" y="2" width="8" height="4" rx="1" ry="1"/></svg>
        </span>
        <span>粘贴</span>
      </div>
      <div class="fcm-divider"></div>

      <div v-if="!isDir" class="fcm-item" @click="handleAction('download')">
        <span class="fcm-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        </span>
        <span>下载</span>
      </div>
      <div class="fcm-item" @click="handleAction('rename')">
        <span class="fcm-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
        </span>
        <span>重命名</span>
      </div>
      <div class="fcm-item danger" @click="handleAction('delete')">
        <span class="fcm-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
        </span>
        <span>删除</span>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { isImagePath } from '../../constants/workspaceFiles.js'

const VIEWPORT_MARGIN = 8

const props = defineProps({
  visible: { type: Boolean, default: false },
  x: { type: Number, default: 0 },
  y: { type: Number, default: 0 },
  node: { type: Object, default: null },
  hasClipboard: { type: Boolean, default: false }
})

const emit = defineEmits(['action', 'close'])

const menuRef = ref(null)
const adjusted = ref({ x: props.x, y: props.y })

const isDir = computed(() => props.node?.type === 'directory')
const canPreview = computed(() => !isDir.value && isImagePath(props.node?.path))

function handleAction(action) {
  emit('action', { action, node: props.node })
  emit('close')
}

watch(
  [() => props.visible, () => props.x, () => props.y],
  async ([visible]) => {
    if (!visible) return
    adjusted.value = { x: props.x, y: props.y }
    await nextTick()
    const el = menuRef.value
    if (!el) return
    const menuHeight = el.offsetHeight
    const menuWidth = el.offsetWidth
    const viewHeight = window.innerHeight
    const viewWidth = window.innerWidth

    let nextY = props.y
    if (nextY + menuHeight > viewHeight - VIEWPORT_MARGIN) {
      const flipped = props.y - menuHeight
      nextY = Math.max(VIEWPORT_MARGIN, Math.min(flipped, viewHeight - menuHeight - VIEWPORT_MARGIN))
    }

    let nextX = props.x
    if (nextX + menuWidth > viewWidth - VIEWPORT_MARGIN) {
      const flipped = props.x - menuWidth
      nextX = Math.max(VIEWPORT_MARGIN, Math.min(flipped, viewWidth - menuWidth - VIEWPORT_MARGIN))
    }

    adjusted.value = { x: nextX, y: nextY }
  },
  { immediate: true }
)

function onGlobalClick(e) {
  if (props.visible && menuRef.value && !menuRef.value.contains(e.target)) {
    emit('close')
  }
}

onMounted(() => {
  window.addEventListener('click', onGlobalClick, true)
  window.addEventListener('contextmenu', onGlobalClick, true)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', onGlobalClick, true)
  window.removeEventListener('contextmenu', onGlobalClick, true)
})
</script>

<style scoped>
.file-context-menu {
  position: fixed;
  z-index: 9999;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  padding: 4px;
  min-width: 150px;
  font-size: 12.5px;
  color: #27272a;
}
.fcm-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 5px;
  cursor: pointer;
  transition: background 0.12s;
  user-select: none;
}
.fcm-item:hover {
  background: #f4f4f5;
  color: #09090b;
}
.fcm-item.disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.fcm-item.danger {
  color: #ef4444;
}
.fcm-item.danger:hover {
  background: #fef2f2;
}
.fcm-divider {
  height: 1px;
  background: #f4f4f5;
  margin: 3px 0;
}
.fcm-icon {
  width: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #71717a;
  flex-shrink: 0;
}
.fcm-item:hover .fcm-icon {
  color: #09090b;
}
.fcm-item.danger .fcm-icon,
.fcm-item.danger:hover .fcm-icon {
  color: #ef4444;
}

/* 暗色主题深度增强 */
:global(html[data-theme="dark"] .file-context-menu) {
  background: #181b24;
  border-color: #2e3547;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
  color: #edf1fb;
}
:global(html[data-theme="dark"] .fcm-item:hover) {
  background: #222736;
  color: #ffffff;
}
:global(html[data-theme="dark"] .fcm-divider) {
  background: #272a37;
}
:global(html[data-theme="dark"] .fcm-icon) {
  color: #8c96a8;
}
:global(html[data-theme="dark"] .fcm-item:hover .fcm-icon) {
  color: #ffffff;
}
:global(html[data-theme="dark"] .fcm-item.danger) {
  color: #f87171;
}
:global(html[data-theme="dark"] .fcm-item.danger:hover) {
  background: rgba(239, 68, 68, 0.15);
  color: #fca5a5;
}
:global(html[data-theme="dark"] .fcm-item.danger .fcm-icon),
:global(html[data-theme="dark"] .fcm-item.danger:hover .fcm-icon) {
  color: #f87171;
}
</style>
